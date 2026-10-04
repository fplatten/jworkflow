package org.jworkflow.workbench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single Spring AI adapter for the fixed {@code gpt-6-astra} model over the OpenAI Responses API (AI-01). Requests
 * always use {@link #MODEL} with {@code store=false}; a runtime model option is ignored, never honored. {@link #stream}
 * is a cancellable Server-Sent Events implementation; Workbench never executes returned function calls itself.
 */
public final class ResponsesChatModel implements ChatModel {

	public static final String MODEL = "gpt-6-astra";
	/** Metadata key marking an incremental text chunk; the final response of a stream lacks it. */
	public static final String DELTA = "workbench.delta";

	private static final String OUTPUT_ITEMS = "openai.responses.output";
	private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

	private final RestClient client;
	private final RetryTemplate retryTemplate;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ToolCallingChatOptions options;
	private final String reasoningEffort;
	private final Streaming streaming;

	/** Streaming transport: HTTP client, Responses endpoint, runtime-only key lookup and Spring AI retry settings. */
	public record Streaming(HttpClient http, URI responses, Supplier<String> apiKey, RetrySettings retry) {}

	/** {@code spring.ai.retry.*}: total attempts (first try included) and exponential backoff bounds. */
	public record RetrySettings(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
		public RetrySettings {
			if (maxAttempts < 1) throw new IllegalArgumentException("spring.ai.retry.max-attempts must be at least 1");
		}
		Duration delay(int failedAttempt) {
			double millis = initialInterval.toMillis() * Math.pow(multiplier, failedAttempt - 1);
			return Duration.ofMillis((long) Math.min(millis, maxInterval.toMillis()));
		}
	}

	public ResponsesChatModel(RestClient client, RetryTemplate retryTemplate, String model, String reasoningEffort) {
		this(client, retryTemplate, model, reasoningEffort, null);
	}

	public ResponsesChatModel(RestClient client, RetryTemplate retryTemplate, String model, String reasoningEffort, Streaming streaming) {
		if (!MODEL.equals(model)) {
			throw new IllegalArgumentException("Workbench uses the fixed model " + MODEL + "; spring.ai.openai.chat.model must not select another model.");
		}
		this.streaming = streaming;
		this.client = client;
		this.retryTemplate = retryTemplate;
		this.options = ToolCallingChatOptions.builder().model(model).build();
		this.reasoningEffort = reasoningEffort.trim();
		if (!this.reasoningEffort.isEmpty() && !EFFORTS.contains(this.reasoningEffort)) {
			throw new IllegalArgumentException("Astra reasoning effort must be low, medium, high, xhigh, or max; "
					+ "leave spring.ai.openai.chat.reasoning-effort unset to use the API default.");
		}
	}

	@Override
	public ToolCallingChatOptions getOptions() {
		return options;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		String body = json.writeValueAsString(request(prompt));
		String response = retryTemplate.invoke(() -> client.post().uri("/responses")
				.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
		if (response == null || response.isBlank()) {
			throw new IllegalStateException("OpenAI Responses API returned an empty response");
		}
		return response(json.readTree(response));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		if (streaming == null) return Flux.error(new UnsupportedOperationException("Streaming transport is not configured"));
		return Flux.create(sink -> {
			AtomicBoolean cancelled = new AtomicBoolean();
			AtomicReference<CompletableFuture<?>> inflight = new AtomicReference<>();
			Thread worker = Thread.ofVirtual().name("assistant-stream").start(() -> {
				try { streamAttempts(prompt, sink, cancelled, inflight); }
				catch (AssistantFailure failure) { if (!cancelled.get()) sink.error(failure); }
				catch (RuntimeException failure) { if (!cancelled.get()) sink.error(new AssistantFailure(AssistantFailure.Kind.MALFORMED, "The provider response could not be read: " + failure.getClass().getSimpleName())); }
			});
			sink.onDispose(() -> {
				cancelled.set(true);
				CompletableFuture<?> request = inflight.get();
				if (request != null) request.cancel(true);
				worker.interrupt();
			});
		});
	}

	private void streamAttempts(Prompt prompt, FluxSink<ChatResponse> sink, AtomicBoolean cancelled, AtomicReference<CompletableFuture<?>> inflight) {
		String key = streaming.apiKey().get();
		if (key == null || key.isBlank()) throw new AssistantFailure(AssistantFailure.Kind.UNAVAILABLE, "AI is unavailable: OPENAI_API_KEY is not set for this Workbench process. Manual authoring is unaffected.");
		ObjectNode body = request(prompt);
		body.put("stream", true);
		HttpRequest request = HttpRequest.newBuilder(streaming.responses()).header("Authorization", "Bearer " + key)
				.header("Content-Type", "application/json").header("Accept", "text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
		RetrySettings retry = streaming.retry();
		for (int attempt = 1; ; attempt++) {
			if (cancelled.get()) return;
			AssistantFailure failure;
			try {
				var future = streaming.http().sendAsync(request, HttpResponse.BodyHandlers.ofLines());
				inflight.set(future);
				HttpResponse<Stream<String>> response = future.get();
				int status = response.statusCode();
				if (status == 200) { readEvents(response.body(), sink, cancelled); return; }
				String detail;
				try (Stream<String> lines = response.body()) { detail = errorCode(String.join("\n", lines.limit(200).toList())); }
				failure = classify(status, detail);
				// Only transient failures before any streamed output are retried, so a retry never replays output.
				if (!(status == 429 || status >= 500)) throw failure;
			}
			catch (AssistantFailure fatal) { throw fatal; }
			catch (InterruptedException | java.util.concurrent.CancellationException interrupted) { Thread.currentThread().interrupt(); return; }
			catch (java.util.concurrent.ExecutionException network) {
				if (cancelled.get()) return;
				failure = new AssistantFailure(AssistantFailure.Kind.PROVIDER, "Could not reach the AI provider (" + network.getCause().getClass().getSimpleName() + ").");
			}
			if (attempt >= retry.maxAttempts()) throw failure;
			try { Thread.sleep(retry.delay(attempt)); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
		}
	}

	private void readEvents(Stream<String> lines, FluxSink<ChatResponse> sink, AtomicBoolean cancelled) {
		StringBuilder data = new StringBuilder();
		try (lines) {
			var iterator = lines.iterator();
			while (iterator.hasNext()) {
				if (cancelled.get()) return;
				String line = iterator.next();
				if (line.startsWith("data:")) { data.append(line.substring(5).strip()); continue; }
				if (!line.isEmpty() || data.isEmpty()) continue;
				String payload = data.toString(); data.setLength(0);
				if (payload.equals("[DONE]")) continue;
				if (handleEvent(json.readTree(payload), sink)) return;
			}
		}
		catch (java.io.UncheckedIOException interrupted) {
			if (cancelled.get()) return;
			throw new AssistantFailure(AssistantFailure.Kind.INTERRUPTED, "The AI response stream was interrupted before it completed.");
		}
		if (!cancelled.get()) throw new AssistantFailure(AssistantFailure.Kind.INTERRUPTED, "The AI response stream ended before it completed.");
	}

	/** Returns true when the stream is complete. */
	private boolean handleEvent(JsonNode event, FluxSink<ChatResponse> sink) {
		switch (event.path("type").asText()) {
			case "response.output_text.delta", "response.refusal.delta" -> {
				String delta = event.path("delta").asText();
				if (!delta.isEmpty()) sink.next(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(delta).properties(Map.of(DELTA, true)).build()))));
				return false;
			}
			case "response.completed" -> {
				sink.next(response(event.path("response")));
				sink.complete();
				return true;
			}
			case "response.failed", "response.incomplete" -> throw new AssistantFailure(AssistantFailure.Kind.PROVIDER,
					"The AI provider did not complete the response (" + event.path("type").asText().substring(9) + ": " + errorCode(event.path("response").toString()) + ").");
			case "error" -> throw classify(500, event.path("code").asText(event.path("error").path("code").asText("error")));
			default -> { return false; }
		}
	}

	private static AssistantFailure classify(int status, String code) {
		if (status == 401 || status == 403) return new AssistantFailure(AssistantFailure.Kind.AUTHENTICATION, "The AI provider rejected the API key (HTTP " + status + "). Check OPENAI_API_KEY; manual authoring is unaffected.");
		if (status == 404 || code.contains("model_not_found")) return new AssistantFailure(AssistantFailure.Kind.MODEL_UNAVAILABLE, "The fixed model " + MODEL + " is not available to this API key. Workbench does not switch to another model.");
		if (status == 429) return new AssistantFailure(AssistantFailure.Kind.RATE_LIMITED, "The AI provider is rate limiting requests (HTTP 429" + (code.isBlank() ? "" : ", " + code) + "). Try again later.");
		if (status >= 500) return new AssistantFailure(AssistantFailure.Kind.PROVIDER, "The AI provider failed (HTTP " + status + (code.isBlank() ? "" : ", " + code) + ").");
		return new AssistantFailure(AssistantFailure.Kind.PROVIDER, "The AI provider refused the request (HTTP " + status + (code.isBlank() ? "" : ", " + code) + ").");
	}

	/** Extracts only a provider error code, never echoing request or response text into messages. */
	private String errorCode(String body) {
		try {
			JsonNode node = json.readTree(body);
			String code = node.path("error").path("code").asText(node.path("error").path("type").asText(""));
			return code.matches("[A-Za-z0-9_.-]{0,64}") ? code : "";
		} catch (RuntimeException notJson) { return ""; }
	}

	private ObjectNode request(Prompt prompt) {
		var runtime = prompt.getOptions();
		var request = json.createObjectNode();
		// Fixed model (AI-01): a runtime option can never select a different or fallback model.
		request.put("model", MODEL);
		request.put("store", false);
		request.putArray("include").add("reasoning.encrypted_content");
		if (!reasoningEffort.isEmpty()) {
			request.putObject("reasoning").put("effort", reasoningEffort);
		}
		if (runtime != null && runtime.getMaxTokens() != null) {
			request.put("max_output_tokens", runtime.getMaxTokens());
		}
		ArrayNode input = request.putArray("input");
		for (Message message : prompt.getInstructions()) {
			addMessage(input, message);
		}
		if (runtime instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null
				&& !toolOptions.getToolCallbacks().isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (var callback : toolOptions.getToolCallbacks()) {
				var definition = callback.getToolDefinition();
				var tool = tools.addObject();
				tool.put("type", "function");
				tool.put("name", definition.name());
				tool.put("description", definition.description());
				tool.set("parameters", json.readTree(definition.inputSchema()));
				// Keep Spring's existing schemas, including optional parameters.
				tool.put("strict", false);
			}
		}
		return request;
	}

	private void addMessage(ArrayNode input, Message message) {
		if (message instanceof AssistantMessage assistant) {
			if (!assistant.getMedia().isEmpty()) {
				throw new IllegalArgumentException("Responses shell adapter supports text and function tools only");
			}
			if (assistant.getMetadata().get(OUTPUT_ITEMS) instanceof ArrayNode output) {
				// Replay all original items in order, including encrypted reasoning and call IDs.
				input.addAll(output.deepCopy());
				return;
			}
			if (assistant.getText() != null && !assistant.getText().isEmpty()) {
				input.addObject().put("role", "assistant").put("content", assistant.getText());
			}
			for (var call : assistant.getToolCalls()) {
				input.addObject().put("type", "function_call").put("call_id", call.id())
						.put("name", call.name()).put("arguments", call.arguments());
			}
		}
		else if (message instanceof ToolResponseMessage toolResponse) {
			for (var result : toolResponse.getResponses()) {
				input.addObject().put("type", "function_call_output").put("call_id", result.id())
						.put("output", result.responseData());
			}
		}
		else {
			if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
				throw new IllegalArgumentException("Responses shell adapter supports text and function tools only");
			}
			input.addObject().put("role", message.getMessageType().getValue()).put("content", message.getText());
		}
	}

	private ChatResponse response(JsonNode response) {
		String status = response.path("status").asText();
		if (!"completed".equals(status)) {
			throw new IllegalStateException("OpenAI response " + response.path("id").asText() + " was " + status
					+ ": " + response.path("error") + " " + response.path("incomplete_details"));
		}
		if (!(response.get("output") instanceof ArrayNode output)) {
			throw new IllegalStateException("OpenAI Responses API response is missing output items");
		}
		var text = new StringBuilder();
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (JsonNode item : output) {
			switch (item.path("type").asText()) {
				case "function_call" -> calls.add(new AssistantMessage.ToolCall(
						item.path("call_id").asText(), "function", item.path("name").asText(),
						item.path("arguments").asText()));
				case "message" -> {
					for (JsonNode content : item.path("content")) {
						String type = content.path("type").asText();
						if ("output_text".equals(type) || "refusal".equals(type)) {
							if (!text.isEmpty()) {
								text.append('\n');
							}
							text.append(content.path("refusal".equals(type) ? "refusal" : "text").asText());
						}
					}
				}
				case "reasoning" -> { /* Preserved below for the next request. */ }
				default -> throw new IllegalStateException("Unsupported Responses output item: " + item.path("type").asText());
			}
		}
		if (calls.isEmpty() && text.isEmpty()) {
			throw new IllegalStateException("OpenAI Responses API returned no text or function calls");
		}
		var assistant = AssistantMessage.builder().content(text.toString()).toolCalls(calls)
				.properties(Map.of(OUTPUT_ITEMS, output.deepCopy())).build();
		var metadata = ChatResponseMetadata.builder().id(response.path("id").asText())
				.model(response.path("model").asText());
		if (response.hasNonNull("usage")) {
			var usage = response.get("usage");
			metadata.usage(new DefaultUsage(usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt(),
					usage.path("total_tokens").asInt(), usage));
		}
		var generationMetadata = ChatGenerationMetadata.builder()
				.finishReason(calls.isEmpty() ? "stop" : "tool_calls").build();
		return new ChatResponse(List.of(new Generation(assistant, generationMetadata)), metadata.build());
	}
}

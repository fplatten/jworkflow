package org.jworkflow.workbench;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Builds the fixed-model adapter without contacting the provider (AI-01, SEC-04). The API key is read from the
 * runtime environment for each request, so it is never captured in build output, assets or startup state.
 */
@Configuration(proxyBeanMethods = false)
class ResponsesConfiguration {

	@Bean
	ResponsesChatModel responsesChatModel(Environment environment,
			@Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
			@Value("${spring.ai.openai.chat.model:" + ResponsesChatModel.MODEL + "}") String model,
			@Value("${spring.ai.openai.chat.reasoning-effort:}") String reasoningEffort,
			@Value("${spring.ai.openai.timeout:300s}") Duration timeout,
			@Value("${spring.ai.retry.max-attempts:4}") int maxAttempts,
			@Value("${spring.ai.retry.backoff.initial-interval:2s}") Duration initialInterval,
			@Value("${spring.ai.retry.backoff.multiplier:5}") double multiplier,
			@Value("${spring.ai.retry.backoff.max-interval:3m}") Duration maxInterval) {
		String apiBase = baseUrl.replaceAll("/+$", "");
		if (!apiBase.endsWith("/v1")) {
			apiBase += "/v1";
		}
		var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		var requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(timeout);
		var client = RestClient.builder().baseUrl(apiBase).requestFactory(requestFactory)
				.requestInterceptor((request, body, execution) -> {
					request.getHeaders().setBearerAuth(apiKey(environment));
					return execution.execute(request, body);
				}).build();
		var retry = new ResponsesChatModel.RetrySettings(maxAttempts, initialInterval, multiplier, maxInterval);
		var streaming = new ResponsesChatModel.Streaming(http, URI.create(apiBase + "/responses"), () -> apiKey(environment), retry);
		return new ResponsesChatModel(client, retryTemplate(maxAttempts, initialInterval), model, reasoningEffort, streaming);
	}

	private static String apiKey(Environment environment) {
		String key = environment.getProperty("spring.ai.openai.api-key", "");
		return key == null ? "" : key.strip();
	}

	static RetryTemplate retryTemplate(int maxAttempts, Duration delay) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("spring.ai.retry.max-attempts must be at least 1");
		}
		return new RetryTemplate(RetryPolicy.builder().maxRetries(maxAttempts - 1L).delay(delay)
				.multiplier(2).maxDelay(Duration.ofSeconds(20))
				.predicate(failure -> failure instanceof ResourceAccessException
						|| failure instanceof RestClientResponseException response
						&& (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError()))
				.build());
	}
}

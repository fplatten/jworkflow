package org.jworkflow.events;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit entry for event processing or publication status. Repositories append attempts rather than
 *  editing earlier history.
 * @param statusId identity of the append-only status record
 * @param eventId event identity associated with the message or history row
 * @param attemptId identity of the immutable attempt record
 * @param attemptNumber one-based attempt number
 * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
 *      outcome
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param correlationId identity shared by related commands and events
 * @param scope processing boundary represented by the status attempt
 * @param handlerId subscriber or handler identity used for audit
 * @param destination registered publication destination
 * @param status observed event-processing outcome
 * @param retryCount number of publication retries already recorded
 * @param nextRetryAt next retry deadline, when another attempt is scheduled
 * @param retryEligible whether another attempt is permitted
 * @param terminal whether this attempt ends processing rather than scheduling another retry
 * @param lastErrorCode stable code of the latest failure
 * @param lastErrorMessage human-readable detail of the latest failure
 * @param createdAt creation time
 */
public record EventStatusAttempt(
        UUID statusId,
        UUID eventId,
        UUID attemptId,
        int attemptNumber,
        String idempotencyKey,
        WorkflowInstanceId workflowInstanceId,
        String correlationId,
        EventStatusScope scope,
        String handlerId,
        String destination,
        EventStatusValue status,
        int retryCount,
        Instant nextRetryAt,
        boolean retryEligible,
        boolean terminal,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt
) {
    /**
     * Creates this value from the supplied components.
     * @param statusId identity of the append-only status record
     * @param eventId event identity associated with the message or history row
     * @param attemptId identity of the immutable attempt record
     * @param attemptNumber one-based attempt number
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *      outcome
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param correlationId identity shared by related commands and events
     * @param scope processing boundary represented by the status attempt
     * @param handlerId subscriber or handler identity used for audit
     * @param destination registered publication destination
     * @param status observed event-processing outcome
     * @param retryCount number of publication retries already recorded
     * @param nextRetryAt next retry deadline, when another attempt is scheduled
     * @param retryEligible whether another attempt is permitted
     * @param terminal whether this attempt ends processing rather than scheduling another retry
     * @param lastErrorCode stable code of the latest failure
     * @param lastErrorMessage human-readable detail of the latest failure
     * @param createdAt creation time
     * @throws NullPointerException if eventId, scope, status is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public EventStatusAttempt {
        statusId = statusId == null ? UUID.randomUUID() : statusId;
        Objects.requireNonNull(eventId, "eventId");
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        Objects.requireNonNull(scope, "scope");
        handlerId = handlerId == null ? "" : handlerId;
        destination = destination == null ? "" : destination;
        Objects.requireNonNull(status, "status");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * Creates an append-only failure observation for a subscriber invocation.
     * @param event event to deliver or inspect
     * @param handlerId subscriber or handler identity used for audit
     * @param attemptNumber one-based attempt number
     * @param error failure detail to record or report
     * @return the resulting event status attempt
     * @throws NullPointerException if event, error is null
     */
    public static EventStatusAttempt listenerFailure(
            WorkflowEvent event,
            String handlerId,
            int attemptNumber,
            Throwable error
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(error, "error");
        return new EventStatusAttempt(
                null,
                event.metadata().eventId(),
                null,
                attemptNumber,
                null,
                event.metadata().workflowInstanceId(),
                event.metadata().correlationId(),
                EventStatusScope.LISTENER,
                handlerId,
                "",
                EventStatusValue.FAILED,
                attemptNumber - 1,
                null,
                true,
                false,
                error.getClass().getName(),
                error.getMessage(),
                null);
    }
}

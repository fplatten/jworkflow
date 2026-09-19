package org.jworkflow.persistence;

import java.util.Optional;

/**
 * Stores immutable replay results by complete idempotency key. Repository deduplication alone does not serialize
 * whole commands; engines must protect lookup, handler execution and persistence in one transaction.
 */
public interface CommandResultRepository {
    /**
     * Stores a command replay result or returns the validated existing result for a matching complete
     * key/type/hash. Conflicting reuse fails.
     * @param result result data associated with the operation
     * @return the resulting command result record
     */
    CommandResultRecord save(CommandResultRecord result);
    /**
     * Looks up the stored command result by its complete idempotency key.
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *     outcome
     * @return the matching value, or an empty optional when absent
     */
    Optional<CommandResultRecord> find(String idempotencyKey);
}

package org.jworkflow.persistence;

import java.util.Optional;

public interface CommandResultRepository {
    CommandResultRecord save(CommandResultRecord result);
    Optional<CommandResultRecord> find(String idempotencyKey);
}

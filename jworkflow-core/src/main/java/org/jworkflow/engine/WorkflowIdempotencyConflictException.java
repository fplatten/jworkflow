package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class WorkflowIdempotencyConflictException extends WorkflowCommandException {
    public WorkflowIdempotencyConflictException(String idempotencyKey) {
        super("idempotency_conflict", "Idempotency key reused with a different command payload: " + idempotencyKey);
    }
}

package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public class WorkflowCommandException extends RuntimeException {
    private final String errorCode;

    public WorkflowCommandException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowCommandException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

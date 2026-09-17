package org.jworkflow.model;

import java.util.List;

public record ListenerInvocation(
        String listenerId,
        String methodName,
        List<ListenerArgument> arguments
) {
    public ListenerInvocation {
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("listenerId is required");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName is required");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}

package org.jworkflow.model;

import java.util.List;

/**
 * Registered listener ID, method name and declarative arguments for a step invocation. It is data interpreted by
 * the engine, not an executable submitted closure.
 * @param listenerId registered infrastructure listener identity
 * @param methodName listener method to invoke
 * @param arguments named immutable arguments supplied to a registered predicate
 */
public record ListenerInvocation(
        String listenerId,
        String methodName,
        List<ListenerArgument> arguments
) {
    /**
     * Creates this value from the supplied components.
     * @param listenerId registered infrastructure listener identity
     * @param methodName listener method to invoke
     * @param arguments named immutable arguments supplied to a registered predicate
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

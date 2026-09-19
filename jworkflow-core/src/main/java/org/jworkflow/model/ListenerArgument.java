package org.jworkflow.model;

/**
 * Restricted argument model for a DSL listener call: current event, current context or an immutable literal.
 */
public sealed interface ListenerArgument permits ListenerArgument.CurrentEvent, ListenerArgument.CurrentContext, ListenerArgument.Literal {
    /**
     * Passes the current integration event to a declared listener method.
     */
    record CurrentEvent() implements ListenerArgument {}
    /**
     * Passes the current workflow step context to a declared listener method.
     */
    record CurrentContext() implements ListenerArgument {}
    /**
     * Passes a defensively copied declarative literal to a listener method.
     * @param value the value to encode or copy
     */
    record Literal(Object value) implements ListenerArgument {}
}

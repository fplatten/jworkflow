package org.jworkflow.model;

public sealed interface ListenerArgument permits ListenerArgument.CurrentEvent, ListenerArgument.CurrentContext, ListenerArgument.Literal {
    record CurrentEvent() implements ListenerArgument {}
    record CurrentContext() implements ListenerArgument {}
    record Literal(Object value) implements ListenerArgument {}
}

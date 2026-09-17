package org.jworkflow.inbox;

import java.util.Objects;

public record InboxInsertResult(InboxMessage message, boolean inserted) {
    public InboxInsertResult { Objects.requireNonNull(message, "message"); }
}

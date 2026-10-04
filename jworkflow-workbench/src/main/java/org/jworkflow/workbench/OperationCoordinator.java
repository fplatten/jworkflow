package org.jworkflow.workbench;

/**
 * The one active operation slot shared by the terminal, the assistant and /build and /test (OPS-01, WB-23). Requests
 * while busy are rejected, never queued. Only the ticket holder can release the slot, so a late completion cannot
 * release a newer operation. Source apply/revert is blocked while a build or test runs (OPS-04).
 */
public final class OperationCoordinator {
    public enum Kind { AI, BUILD, TEST }
    public record Ticket(long id, Kind kind) {}

    private Ticket active;
    private Runnable canceller;
    private long next;

    /** Returns a ticket, or null when another operation holds the slot. */
    public synchronized Ticket tryBegin(Kind kind, Runnable cancel) {
        if (active != null) return null;
        active = new Ticket(++next, kind);
        canceller = cancel;
        return active;
    }

    /** Releases the slot only for the current holder; returns false for a superseded ticket. */
    public synchronized boolean end(Ticket ticket) {
        if (active == null || ticket == null || active.id() != ticket.id()) return false;
        active = null; canceller = null;
        return true;
    }

    public synchronized Kind active() { return active == null ? null : active.kind(); }

    public synchronized boolean sourceWritesBlocked() { return active != null && active.kind() != Kind.AI; }

    /** Runs the active operation's cancellation outside the lock; returns its kind, or null when idle. */
    public Kind cancelActive() {
        Runnable cancel; Kind kind;
        synchronized (this) { if (active == null) return null; cancel = canceller; kind = active.kind(); }
        if (cancel != null) cancel.run();
        return kind;
    }
}

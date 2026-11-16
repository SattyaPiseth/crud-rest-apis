package sattya.piseth.crudrestapis.notification.application;

/** A key/event ID was reused with different semantics. Future HTTP adapters should map this to 409. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("The idempotency key or event ID was already used with a different payload");
    }
}

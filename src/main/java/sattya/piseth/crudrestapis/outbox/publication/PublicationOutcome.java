package sattya.piseth.crudrestapis.outbox.publication;

/** Bounded, sanitized classifications; adapters must not return destination error text. */
public enum PublicationOutcome { ACKNOWLEDGED, RETRYABLE, PERMANENT, UNKNOWN }

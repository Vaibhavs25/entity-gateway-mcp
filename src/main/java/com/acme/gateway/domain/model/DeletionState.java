package com.acme.gateway.domain.model;

/** Saga states — see architecture doc §6.2 for the transition diagram. */
public enum DeletionState {
    PENDING_DELETE,       // intent durable (TX1 committed); FTP not yet confirmed
    FTP_DELETED,          // file gone on FTP (TX2); DB finalization pending — Scenario A window
    COMPLETED,            // fully converged (TX3)
    FTP_FAILED_RETRYING,  // Scenario B: file intact, retry scheduled with backoff
    FAILED_MANUAL;        // retries exhausted / permanent error; operator action required

    public boolean isTerminal() { return this == COMPLETED || this == FAILED_MANUAL; }
}

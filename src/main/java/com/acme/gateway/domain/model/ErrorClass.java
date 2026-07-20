package com.acme.gateway.domain.model;

/** Classification of the most recent FTP failure recorded on a ledger row. */
public enum ErrorClass { NONE, RETRYABLE_NETWORK, RETRYABLE_FILE_LOCKED, PERMANENT }

package com.acme.gateway.domain.model;

/**
 * ACTIVE              — normal, visible to query tools.
 * DELETE_IN_PROGRESS  — tombstone set by TX1 of the deletion saga; hidden from
 *                       reads so no agent can observe "gone on FTP but alive
 *                       in DB" mid-saga.
 * DELETED             — terminal; never returned by any tool.
 */
public enum SignatureFileStatus { ACTIVE, DELETE_IN_PROGRESS, DELETED }

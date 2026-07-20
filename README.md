![CI](https://github.com/Vaibhavs25/entity-gateway-mcp/actions/workflows/ci.yml/badge.svg)
# entity-gateway-mcp

Secure MCP server (Streamable HTTP) exposing a strict DML-only tool surface
over MS SQL Server + FTP. Java 21 / Spring Boot 4.1 / Spring AI 2.0.

## Run locally
```bash
export DB_HOST=localhost DB_USER=sa DB_PASSWORD=... DB_NAME=entitydb DB_TRUST_SERVER_CERT=true
export FTP_HOST=localhost FTP_USER=ftp FTP_PASSWORD=...
export OIDC_ISSUER_URI=https://idp.example.com/realms/agents
mvn spring-boot:run
```
MCP endpoint: `POST/GET/DELETE http://localhost:8443/mcp` (Streamable HTTP).

## Smoke test with MCP Inspector
```bash
npx @modelcontextprotocol/inspector
# transport: Streamable HTTP, URL http://localhost:8443/mcp
# add header: Authorization: Bearer <jwt with roles ["AGENT_OPERATOR"]>
```

## Tool surface (contract: mcp-gateway-architecture.md §7)
| Tool | Role | Notes |
|---|---|---|
| search_profiles | READER+ | paged, typed filters only |
| get_profile | READER+ | includes optimistic-lock `version` |
| list_signature_files | READER+ | DB system of record; never scans FTP |
| update_profile_status | WRITER+ | whitelisted transitions, CAS via expectedVersion |
| delete_signature_file | OPERATOR | distributed saga; idempotent via requestId |
| get_deletion_status | READER+ | saga observability |

## Deletion saga (Scenario A / B)
Intent-first ordering: TX1 (`PENDING_DELETE` + tombstone) → FTP delete →
TX2 (`FTP_DELETED`) → TX3 (`COMPLETED`). No DB transaction is ever open
across an FTP call (`DeletionTxSteps` = REQUIRES_NEW steps;
`SignatureDeletionOrchestrator` holds none). `DeletionReconciler`
(ShedLock-guarded) re-drives stuck rows: FTP-failure rows retry with backoff
(Scenario B), `FTP_DELETED` rows get DB-only finalization (Scenario A).
FTP 550 is idempotent success. Failure matrix: architecture doc §9.

## Test suite (failure-matrix coverage)

| §9 matrix row | Test |
|---|---|
| Happy path + TX ordering | `SignatureDeletionOrchestratorTest.happyPath_completesInline`, `DeletionSagaIntegrationTest.happyPath` |
| Row 3 — Scenario B (FTP down, retry heals) | `scenarioB_retryableFtpFailure_failsClosed` (unit), `scenarioB_ftpDown_thenReconcilerHeals` (IT) |
| Scenario B exhaustion → FAILED_MANUAL | `scenarioB_permanentFailure_escalatesToManual` (unit), `scenarioB_exhaustion_escalatesToManual` (IT) |
| Row 4 — FTP 550 = idempotent success | `ftpAlreadyAbsent_isIdempotentSuccess` (unit), 550-classification in `CommonsNetFtpGatewayTest` |
| Row 5 — crash between FTP 250 and TX2 | `crashBetweenFtpAndTx2_healsViaIdempotent550` (IT) |
| Row 6 — Scenario A (TX3 fails, DB-only healing) | `scenarioA_finalizationFailure_leavesLedgerHealable` + `scenarioA_healing_neverRecontactsFtp` (unit), `scenarioA_finalizationFails_thenReconcilerHeals` (IT — heals with FTP deliberately STOPPED) |
| Row 7 — duplicate requestId | `duplicateRequestId_returnsOriginalReceipt_neverDeletesTwice` (unit), `duplicateRequestId_exactlyOnce` (IT, real UNIQUE constraint) |
| Terminal-state inertness | `failedManual_isInert` (unit) |
| FTP reply-code → ErrorClass contract | `CommonsNetFtpGatewayTest` (real protocol via MockFtpServer) |

Run: `mvn test` (unit + FTP adapter tests) · `mvn verify` with Docker running
(Testcontainers pulls `mcr.microsoft.com/mssql/server:2022-latest` for the IT).

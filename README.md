# Stratigen Scouting API

Java/Spring Boot control plane for report request lifecycle, contract-safe status delivery, and final scouting report retrieval.

## Module Role

`scouting-api` is the control-plane service in the Stratigen architecture. It:
- validates and accepts report generation requests,
- applies idempotent request handling,
- exposes lifecycle/status/report endpoints,
- maps workflow + error taxonomy into additive v1 contracts,
- serves report artifacts first, with legacy report fallback during migration.

## Responsibilities

- Request validation (`GenerateReportRequest`) and normalization.
- Idempotent request creation keyed by prompt hash.
- Lifecycle/status mapping to workflow states.
- Public response contract enrichment (`contractVersion`, `workflowState`, `errorCode`, `retryable`, lineage fields).
- Artifact-first read path (`report_artifacts`) with legacy fallback (`scouting_reports`).

## Architecture Snapshot

```text
POST /api/reports/generate
    -> creates/reuses report_request + queued report_job
        -> worker progresses lifecycle + persists planes/artifacts
            -> API status/read endpoints return persisted read model
```

Execution ownership split:
- API: orchestration state mapping + public contract output.
- Worker: ingestion, features, synthesis, composition execution.

## Public Endpoints

Base URL (local): `http://localhost:8080/api`

| Method | Path | Purpose | Notes |
|---|---|---|---|
| `GET` | `/health/check` | Health probe | Returns `status` + timestamp |
| `POST` | `/reports/generate` | Submit report request | Body: `{ "userPrompt": string }` (10-500 chars) |
| `GET` | `/reports/{id}/status` | Poll lifecycle/status | Includes additive contract fields |
| `GET` | `/reports/{id}` | Fetch completed report | Returns structured scouting report contract |

For full request/response examples, see [API_TEST_COMMANDS.md](./API_TEST_COMMANDS.md).

## Contract Versioning

Current contract versions:
- `report-status.v1`
- `scouting-report.v1`

Root contract references:
- [Report Status Contract v1](../contracts/schemas/report-status.v1.schema.json)
- [Scouting Report Contract v1](../contracts/schemas/scouting-report.v1.schema.json)
- [Contracts Overview](../contracts/README.md)

## Local Development

### Prerequisites

- Java 24
- PostgreSQL
- Maven Wrapper (`mvnw` / `mvnw.cmd`)

### Required Environment Variable

- `DB_PASSWORD` (used by `spring.datasource.password=${DB_PASSWORD}`)

### Default Runtime Settings

From `src/main/resources/application.properties`:
- Port: `8080`
- DB URL: `jdbc:postgresql://localhost:5432/grid_scouting_db`
- DB user: `postgres`
- JPA DDL mode: `validate`

### Run

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

## Database and Migrations

Migrations live under:
- `src/main/resources/migrations/`

Important current milestones:
- `011_add_report_jobs_orchestration.sql`: job lifecycle/orchestration table.
- `012_add_storage_planes.sql`: raw/normalized/feature/report storage planes.

`spring.jpa.hibernate.ddl-auto=validate` means schema must be migrated before app startup.

## Testing

Run full test suite:

```bash
./mvnw test
```

Focused lifecycle/contract tests:

```bash
./mvnw "-Dtest=ReportServiceTest,ReportWorkflowHandshakeIntegrationTest" test
```

## Current Status / Known Limits

- `/api/reports/**` and `/api/health/**` are currently unauthenticated in security configuration.
- CORS currently allows localhost frontend origins only (`http://localhost:3000`, `http://127.0.0.1:3000`).
- API lifecycle visibility is polling-based today (no realtime push/SSE/WebSocket channel yet).
- Legacy compatibility path remains active while migration to artifact-first read model continues.

## Related Docs

- [Root Orchestrator README](../README.md)
- [RFC-0001: Architecture v2 Baseline](../docs/RFC-0001-architecture-v2-baseline.md)
- [RFC-0002: Storage Plane Separation](../docs/RFC-0002-storage-planes.md)

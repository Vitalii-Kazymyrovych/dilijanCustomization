# Dilijan Customization — Developer Guide

This Spring Boot service glues the VEZHA face-recognition API, Telegram, and Excel report generation for evacuation and cafeteria attendance workflows.

## Architecture at a glance
- **HTTP entrypoints**: REST controllers expose webhooks and reporting endpoints for VEZHA and operators.
  - `VezhaWebhookController` ingests VEZHA face events to add/remove unknown persons from the dedicated face list. It delegates to `UnknownPersonService` for the add/remove logic and detection lookups.  
  - `CafeteriaReportController` and `EvacuationReportController` expose report generation endpoints (per-day attendance pivot and multi-list evacuation workbooks respectively).
- **Telegram bot**: `TelegramBot` orchestrates chat flows for evacuation and attendance reports, forwards uploaded evacuation workbooks to `EvacuationStatusService`, and uses `AttendanceReportService` / `EvacuationReportService` to generate XLSX files on demand.
- **VEZHA integration**: `FaceApiRepository` wraps all VEZHA calls (detections, list CRUD, storage downloads). Higher-level services rely on it for analytics queries, unknown person management, and pulling list metadata/images.
- **Unknown-person pipeline**: `UnknownListInitializer` ensures a dedicated face list exists at startup and records its id in `UnknownListRegistry`. `UnknownPersonService` then:
  - searches recent detections around a webhook event and creates a list item when the face isn’t in any list;
  - deletes items when VEZHA signals removal;
  - performs weekly cleanup of the unknown list.
- **Evacuation domain**: `EvacuationStatusService` periodically pulls detections for time-attendance-enabled lists, determines who last entered vs. exited, and persists statuses via `EvacuationStatusRepository` (PostgreSQL). `EvacuationReportService` refreshes statuses and assembles an XLSX workbook through `ReportService`, which embeds photos and dropdowns.
- **Cafeteria attendance**: `AttendanceReportService` defines meal time windows, counts unique list item detections per meal, and passes pivot rows to `ReportService` for XLSX export. A nightly schedule can auto-run the report.
- **Configuration & infrastructure**:
  - External config lives in `config/config.yaml` (see `config/config.yaml.example`); properties are bound via `*Props` classes and injected into the beans above.
  - `HttpClientConfig` creates the authenticated VEZHA `RestTemplate`; `PostgresDataSourceConfig` wires HikariCP using `postgres.*` settings; `SchedulerConfig` sets a shared scheduler with centralized error handling.

## Key flows
- **Unknown person add/remove**
  1. VEZHA posts to `/webhooks/vezha/face-event/add|remove`.
  2. `VezhaWebhookController` converts JSON into `FaceEventDto` and invokes `UnknownPersonService`.
  3. The service queries detections near the timestamp, resolves/creates the unknown list item, or deletes it when appropriate.
- **Evacuation report**
  1. A request to `/evacuation/report?listIds=...` or a Telegram callback triggers `EvacuationReportService`.
  2. The service asks `EvacuationStatusService` to recompute statuses (using VEZHA detections) and load active list item ids from PostgreSQL.
  3. `ReportService` builds one sheet per list with status dropdowns and embedded photos; the controller streams it as XLSX.
- **Cafeteria attendance report**
  1. Scheduler or `/cafeteria/build` triggers `AttendanceReportService`.
  2. Detections are pulled for configured analytics IDs and time windows; unique person counts per meal/list are calculated.
  3. `ReportService` outputs a pivot-style XLSX with totals.

## Endpoints & schedules
- **REST**:
  - `POST /webhooks/vezha/face-event/add` and `/remove` — manage unknown list membership.
  - `POST /cafeteria/build?date=YYYY-MM-DD[&timezone=TZ][&listIds=1,2]` — write a per-day attendance report to disk and return its path.
  - `GET  /evacuation/report?listIds=1,2` — download a multi-list evacuation XLSX.
- **Schedulers** (respect `spring.task.scheduling.enabled`):
  - Unknown list cleanup: Sundays at midnight (`UnknownPersonService`).
  - Cafeteria report generation: cron from `vezha.cafe.schedule-cron` in the configured timezone.
  - Evacuation status refresh: every `evacuation.refreshMinutes` minutes.

## Configuration
Configuration is loaded from `config/config.yaml` (not committed) with defaults in `config/config.yaml.example`:
- `vezha.api.*`: base URL and token for VEZHA REST calls.
- `telegram.bot.*`: credentials for the polling bot.
- `evacuation.*`: toggle/intervals for status refresh and report eligibility.
- `vezha.cafe.*`: analytics ids, timezone, cron, excluded lists, and output directory for cafeteria XLSX.
- `unknown.*`: whether to autostart unknown list creation/cleanup.
- `postgres.*`: JDBC / psql settings for the evacuation status table.

## Package map
- `web/` — REST controllers.
- `telegram/` — Telegram bot integration.
- `repository/` — VEZHA REST client and JPA repository for evacuation statuses.
- `domain/**/service` — domain services for evacuation, attendance, unknown-person flows, and shared reporting.
- `domain/**/dto` — DTOs exchanged with VEZHA and report builders.
- `config/` — property holders and infrastructure beans (HTTP client, scheduling, datasource, Telegram, unknown list registry).
- `bootstrap/` — startup routines (unknown list creation).

## Local development
- **Run**: `./mvnw spring-boot:run`
- **Tests**: `./mvnw -B test` (scheduling and network calls are disabled by the example config). Make sure Maven Central is reachable for dependency resolution.
- Avoid hitting live VEZHA/Telegram services during tests; override config with safe endpoints/tokens when needed.

## Testing notes
- Unit tests now cover the report builders (`ReportService`, `AttendanceReportService`, `EvacuationReportService`), initialization helpers (`UnknownListInitializer`, `UnknownListRegistry`), VEZHA client pagination (`FaceApiRepository`), and evacuation status persistence logic (`EvacuationStatusService`).
- Tests rely on mocks for VEZHA/Telegram/PostgreSQL; they do not make network calls at runtime.

# Dilijan Customization — Developer Guide

This Spring Boot service glues the VEZHA face-recognition API, Telegram, and Excel report generation for evacuation and cafeteria attendance workflows.

## Technical specification
See [TECHNICAL-SPEC.md](TECHNICAL-SPEC.md) for the aligned functional specification based on the current implementation.

## Architecture at a glance
- **HTTP entrypoints**: REST controllers expose webhooks and reporting endpoints for VEZHA and operators.
  - `VezhaWebhookController` ingests VEZHA face events to add/remove unknown persons from the dedicated face list. It delegates to `UnknownPersonService` for the add/remove logic and detection lookups.  
  - `CafeteriaReportController` and `EvacuationReportController` expose report generation endpoints (per-day attendance pivot and multi-list evacuation workbooks respectively).
- **Telegram bot**: `TelegramBot` orchestrates chat flows for evacuation and attendance reports, forwards uploaded evacuation workbooks to `EvacuationStatusService`, and uses `AttendanceReportService` / `EvacuationReportService` to generate XLSX files on demand. It also posts a “Generating evacuation report” message and reports the generation duration after the file is sent.
- **VEZHA integration**: `FaceApiRepository` wraps all VEZHA calls (detections, list CRUD, storage downloads). Higher-level services rely on it for analytics queries, unknown person management, and pulling list metadata/images.
  - Detection queries send a multipart request that always contains an empty `image` part with a non-blank filename (matching the VEZHA Swagger contract) even when filtering without an uploaded file.
- **Unknown-person pipeline**: `UnknownListInitializer` ensures a dedicated face list exists at startup and records its id in `UnknownListRegistry`. `UnknownPersonService` then:
  - searches recent detections around a webhook event, downloads the detection thumbnail, probes `/face/list_items/search_by_photo` (multipart image, confidence=90) to ensure no similar faces already exist, and only then creates a list item;
  - deletes items when VEZHA signals removal;
  - performs weekly cleanup of the unknown list.
  - Startup list initialization now logs and skips if VEZHA returns an invalid response (e.g., invalid Content-Type), preventing the application from failing on boot.
- **Evacuation domain**: `EvacuationStatusService` now reads time-attendance-enabled lists, list items, and latest detections directly from VEZHA PostgreSQL (`videoanalytics.*` tables), determines who last entered vs. exited, and persists statuses via `EvacuationStatusRepository` in the local evacuation PostgreSQL. `EvacuationReportService` refreshes statuses and assembles an XLSX workbook through `ReportService`, which embeds photos and exports the status column as ☑/☐ symbols with validation so Google Sheets renders checkboxes (checked by default).
  - The evacuation status table stores the timestamps of the last entrance and exit detections per person (`entrance_time`, `exit_time`) plus a `manually_updated` flag so manual overrides are preserved until a newer detection arrives.
  - Evacuation status refresh paginates through all list items, so lists with more than 1000 people still update statuses correctly.
  - Telegram uploads of evacuation workbooks now read the list item ID from the dedicated “ID” column (column 3) produced by `ReportService`, so evacuation status updates line up with the exported report.
  - If the ID column is empty, Telegram upload parsing now falls back to the “Name” column and resolves people by exact full-name match within the same list; when such a row has no explicit status value, it is treated as “On site” (present) so adding names alone works for manual additions.
  - Telegram workbook import now also guards list-item pagination while building the full-name lookup, so uploads still complete even if VEZHA repeats the same full page and ignores pagination offsets.
- **Cafeteria attendance**: `AttendanceReportService` defines meal time windows, counts unique list item detections per meal, and passes pivot rows to `ReportService` for XLSX export. A nightly schedule can auto-run the report.
- **Configuration & infrastructure**:
  - External config lives in `config/config.yaml` (see `config/config.yaml.example`); properties are bound via `*Props` classes and injected into the beans above.
  - `HttpClientConfig` creates the authenticated VEZHA `RestTemplate`; `PostgresDataSourceConfig` wires HikariCP using `postgres.*` settings and marks the main evacuation datasource as `@Primary` so Spring Boot can always create the default JPA `entityManagerFactory` when the extra VEZHA datasource is also present; `SchedulerConfig` sets a shared scheduler with centralized error handling.
  - Evacuation DB bootstrap now ensures the `entrance_time`, `exit_time`, and `manually_updated` columns exist via `ALTER TABLE IF NOT EXISTS`, so upgrading preserves existing rows while adding new metadata.

## Key flows
- **Unknown person add/remove**
  1. VEZHA posts to `/webhooks/vezha/face-event/add|remove`.
  2. `VezhaWebhookController` converts JSON into `FaceEventDto` and invokes `UnknownPersonService`.
  3. The service queries detections near the timestamp, resolves/creates the unknown list item, or deletes it when appropriate.
- **Evacuation report**
  1. A request to `/evacuation/report?listIds=...` or a Telegram callback triggers `EvacuationReportService`.
  2. The service asks `EvacuationStatusService` to recompute statuses using VEZHA DB detections/lists and load active list item ids from the evacuation DB, including each person’s most recent entrance time.
  3. `ReportService` builds one sheet per list with status checkboxes (☑/☐ symbol cells for Google Sheets), entrance time column, and embedded photos; the controller streams it as XLSX.
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
- `vezha.api.*`: base URL and token for VEZHA REST calls. You can also set `min-detection-similarity` (defaults to `0`) to satisfy VEZHA’s detections endpoint when it requires the parameter.
- `telegram.bot.*`: credentials for the polling bot.
- `evacuation.*`: toggle/intervals for status refresh and report eligibility.
- `vezha.cafe.*`: analytics ids, timezone, cron, excluded lists, and output directory for cafeteria XLSX.
- `unknown.*`: whether to autostart unknown list creation/cleanup. Unknown-list startup initialization is now opt-in (requires explicit `unknown.autostart=true`).
- `postgres.*`: JDBC / psql settings for the evacuation status table. Invalid or blank port values now fall back to `5432` so config typos do not break report generation.
- `vezha.db.*`: direct VEZHA PostgreSQL connection used by evacuation status/report generation and cafeteria attendance generation to read `face_lists`, `face_list_items` (+ images), and `face_detections` without REST pagination overhead.

## Package map
- `web/` — REST controllers.
- `telegram/` — Telegram bot integration.
- `repository/` — VEZHA REST client, VEZHA DB reader for evacuation data, and JPA repository for evacuation statuses.
- `domain/**/service` — domain services for evacuation, attendance, unknown-person flows, and shared reporting.
- `domain/**/dto` — DTOs exchanged with VEZHA and report builders.
- `config/` — property holders and infrastructure beans (HTTP client, scheduling, datasource, Telegram, unknown list registry).
- `bootstrap/` — startup routines (unknown list creation).

## Local development
- **Run**: `./mvnw spring-boot:run`
- **Tests**: `./mvnw -B test` (scheduling and network calls are disabled by the example config). Make sure Maven Central is reachable for dependency resolution.
- Avoid hitting live VEZHA/Telegram services during tests; override config with safe endpoints/tokens when needed.

## Testing notes
- Unit tests now cover the report builders (`ReportService`, `AttendanceReportService`, `EvacuationReportService`), initialization helpers (`UnknownListInitializer`, `UnknownListRegistry`), VEZHA client pagination (`FaceApiRepository`), and evacuation status persistence logic (`EvacuationStatusService`), plus unknown-person lifecycle logic (`UnknownPersonService`), webhook endpoint behavior (`VezhaWebhookController`), global error responses (`GlobalExceptionHandler`), and PostgreSQL property fallbacks (`PostgresProps`).
- Run `./mvnw -B test` after each code change to keep feedback tight and prevent regressions.
- Pagination in `FaceApiRepository#getAllDetectionsInWindow` now ignores `total/pages` metadata and keeps requesting until a page comes back empty or partial, so detections are not missed when VEZHA reports only one page.
- `FaceApiRepository` normalizes `vezha.api.base-url` values so trailing slashes do not break detection queries.
- Detection pagination tests now stub the follow-up empty page when a full page is returned, matching the real pagination stop condition.
- Tests rely on mocks for VEZHA/Telegram/PostgreSQL; they do not make network calls at runtime.
- Mockito dependencies are provided by `spring-boot-starter-test`; no additional mockito artifacts are required.
- Core services extract helper methods/constants for readability and now guard more defensively against missing VEZHA responses or empty time-attendance settings when computing statuses and reports.
- Detection queries now send an empty multipart body (no `image` part) to mirror the browser request and avoid “image processing failed” responses when filtering by params.
- VEZHA detection failures surface the HTTP status and response body in exceptions so logs capture upstream error details (e.g., image processing errors).
- Expected failure paths (VEZHA detection errors, evacuation status queries) now log concise summaries at `WARN` without stack traces to keep test output clean while still surfacing the root cause.
- Evacuation report generation now fails fast if the status query throws, instead of sending an empty workbook when the PostgreSQL connection is misconfigured.
- Search-by-photo errors now log the HTTP status/response summary (without stack traces) so operators immediately see VEZHA’s reason, such as `ERROR_NO_FACES_DETECTED`.
- Telegram full-name fallback lookup now includes pagination safety guards to avoid infinite loops when VEZHA list-item pagination does not advance; matching continues with the names collected so far.
- Telegram workbook ingestion treats exact-name fallback rows with blank status as present (`true`) so operators can add only full names to mark additional people on site.
- `UnknownPersonService` now safely handles partially populated webhook payloads (missing list/list_item nesting) and fails fast on missing event timestamps, with regression tests covering both guards.

- Attendance reports now prefer VEZHA DB queries when `vezha.db.enabled=true` and only use VEZHA REST as a fallback, reducing API dependency during report generation.

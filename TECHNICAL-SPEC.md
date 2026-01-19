# VEZHA Custom Functionality — Technical Specification (Aligned with Current Implementation)

## Project metadata
- **Project**: Campus Face Recognition System
- **Customer**: Educational Campus
- **Base modules**: VEZHA Face Recognition API, Telegram bot, Excel reporting
- **Type of work**: Custom logic, reporting, export

## Business objectives
- Provide a near-real-time evacuation status overview of people currently **on site** during emergencies.
- Provide automated cafeteria attendance analytics per meal period.
- Maintain a dedicated **Unknown** list with deduplication to avoid duplicate unknowns.

## System context
- Functionality operates inside the Telegram bot and REST controllers.
- Reports are generated on demand (Telegram or REST) and exported as XLSX files.
- Cafeteria attendance can also be generated on a schedule.

## Actors and user roles
- **Security operator**: generates evacuation status reports and uploads updated workbooks.
- **Administrator**: configures VEZHA lists, attendance settings, analytics IDs, and schedules.

## Data sources
- **VEZHA face detections** (filtered by list id, analytics ids, and time windows).
- **Face lists** with *time attendance* enabled to define entrance/exit analytics.
- **Unknown list** for unrecognized faces managed by webhook events.

## Core concepts and definitions
- **Evacuation status (on site)**: a list item is on site if its latest detection within the lookback window belongs to an **entrance** analytics stream; otherwise it is considered evacuated/not on site.
- **Attendance (cafeteria)**: unique list items are counted once per meal window (breakfast/lunch/dinner).
- **Unknown person**: a face in the Unknown list that is created only when the detection image does **not** match an existing unknown via `search_by_photo` (confidence=90).

## Business rules and logic
- **Evacuation status calculation**
  - Only lists with time attendance enabled are eligible for evacuation reports.
  - Entrance/exit analytics ids are read from the list’s time attendance configuration.
  - The service queries detections across entrance + exit analytics and chooses the **latest** detection per person.
  - If that latest detection is from an entrance analytics id, the status is **On site** and the entrance timestamp is stored; otherwise status is **Evacuated**.
  - Statuses are persisted to PostgreSQL and refreshed on a fixed schedule and on demand.
- **Cafeteria attendance**
  - Meal windows are derived from configured breakfast/lunch/dinner times in the configured timezone.
  - For each list, detections are deduplicated by list item id within each window.
  - Lists can be excluded by name or narrowed via the request list ids.
- **Unknown list management**
  - Webhook events trigger add/remove logic.
  - Add flow: detections are searched around the event time, the best detection image is downloaded, and `search_by_photo` is used to ensure no similar face already exists before creating a new list item.
  - Weekly cleanup of the unknown list is supported.

## Functional scenarios (use cases)
- **Evacuation report**: operator selects one or more lists in Telegram or calls `GET /evacuation/report?listIds=...` and receives an XLSX workbook with current statuses.
- **Cafeteria attendance report**: operator requests attendance via Telegram or calls `POST /cafeteria/build?date=YYYY-MM-DD` to produce a daily XLSX pivot.
- **Unknown list maintenance**: VEZHA webhook adds/removes unknown persons; system performs deduplication and cleanup.

## Reports and outputs
- **Evacuation workbook (XLSX)**
  - One sheet per list.
  - Status dropdown with values: `On site`, `Evacuated`.
  - Embedded photos when available.
  - Entrance time stored and displayed per person.
- **Cafeteria attendance (XLSX)**
  - Pivot sheet with per-list totals for breakfast, lunch, dinner, and a grand total row.

## Output structure
- **Evacuation report columns**: `Status`, `Entrance time`, `Photo`, `ID`, `Name`, `Comment`.
- **Cafeteria report columns**: `Category`, `Breakfast`, `Lunch`, `Dinner`, `Total`.

## Export and integration requirements
- Reports are produced as Excel files and sent via Telegram or returned through REST.
- Cafeteria reports are saved to the configured output directory and shared via Telegram when requested.

## Performance and load expectations
- Supports lists with more than 1000 items via pagination.
- Detection queries page through results (page size 500) until no more data is returned.
- Report generation performance depends on VEZHA API response times and the number of lists/detections.

## Configuration and dependencies
- `config/config.yaml` supplies VEZHA credentials, Telegram tokens, evacuation settings, cafeteria windows, analytics ids, and output directories.
- Evacuation reports require lists to have time attendance enabled and entrance/exit analytics configured.

## Security and access control
- Telegram access is governed by the bot token and chat membership.
- REST endpoints are intended for internal use; external access should be restricted by infrastructure controls (reverse proxy / network ACLs).

## Limitations and assumptions
- Accuracy depends on correct classification of entrance/exit analytics ids in VEZHA.
- Missed detections can affect evacuation status and attendance counts.
- Attendance reporting assumes each person should be counted once per meal window per list.

## Out of scope
- UI redesign or Telegram UX overhaul.
- Face recognition model training or calibration.
- Rewriting VEZHA core detection logic.

## Acceptance criteria
- Evacuation reports include each eligible list item once (no duplicates).
- Status logic uses latest detection within the lookback window and honors entrance analytics ids.
- Cafeteria reports count unique persons once per meal window and include totals.
- Reports are exported as XLSX and delivered via Telegram/REST as configured.

## Risks and open questions
- Misconfigured time attendance analytics can invert or hide evacuation status.
- Confidence thresholds for unknown-person matching (search-by-photo) may need tuning.
- VEZHA latency can impact report generation time during peak periods.

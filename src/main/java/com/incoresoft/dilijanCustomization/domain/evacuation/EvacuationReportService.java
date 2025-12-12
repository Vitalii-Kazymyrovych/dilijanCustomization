package com.incoresoft.dilijanCustomization.domain.evacuation;

import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.service.ReportService;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.Instant;
import java.util.*;

/**
 * Gathers data for the evacuation report and delegates XLSX creation to ReportService.
 * IMPORTANT:
 *  - Presence CSV can contain multiple rows for the same employee.
 *  - We must use ONLY the LAST record per employee (by row order in CSV)
 *    to decide whether the person is currently present in the building.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvacuationReportService {

    private final FaceApiRepository repo;
    private final ReportService reportService;

    public File buildEvacuationReport(List<Long> listIds) throws Exception {
        if (listIds == null || listIds.isEmpty()) {
            throw new IllegalArgumentException("listIds cannot be empty");
        }

        // Stable order of sheets
        List<Long> sortedIds = new ArrayList<>(listIds);
        Collections.sort(sortedIds);

        Map<FaceListDto, List<ListItemDto>> data = new LinkedHashMap<>();
        long now = Instant.now().toEpochMilli();

        for (Long listId : sortedIds) {
            FaceListDto faceList = fetchFaceListMeta(listId).orElse(null);
            if (faceList == null) {
                log.warn("List {} not found in FaceLists response; skipping", listId);
                continue;
            }

            String csv = repo.downloadPresenceCsv(listId, now);
            Set<String> presentNames = parseCsvPresentNames(csv);
            if (presentNames.isEmpty()) {
                data.put(faceList, List.of());
                continue;
            }

            List<ListItemDto> allItems = fetchAllListItems(listId);

            List<ListItemDto> presentItems = allItems.stream()
                    .filter(it -> StringUtils.hasText(it.getName()))
                    .filter(it -> presentNames.contains(normalizeName(it.getName())))
                    .sorted(Comparator.comparing(li -> li.getName().toLowerCase(Locale.ROOT)))
                    .toList();

            data.put(faceList, presentItems);
        }

        File out = File.createTempFile("evacuation-", ".xlsx");
        return reportService.exportEvacuationWorkbook(data, out);
    }

    private Optional<FaceListDto> fetchFaceListMeta(Long listId) {
        return repo.getFaceLists(100)
                .getData()
                .stream()
                .filter(d -> Objects.equals(d.getId(), listId))
                .findFirst();
    }

    private List<ListItemDto> fetchAllListItems(Long listId) {
        ListItemsResponse resp = repo.getListItems(listId, "", "", 0, 1000, "asc", "name");
        return (resp != null && resp.getData() != null) ? resp.getData() : List.of();
    }

    /**
     * Parse presence CSV and return a set of employee names (normalized, lower-cased),
     * for which the LAST record in the CSV marks them as "present".
     */
    private Set<String> parseCsvPresentNames(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        // Remove UTF-8 BOM if present
        if (csv.startsWith("\uFEFF") || csv.startsWith("\ufeff")) {
            csv = csv.substring(1);
        }
        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) {
            return Set.of();
        }

        // --- Determine column indexes from header ---
        String[] header = lines[0].split(";");
        int employeeIdx = -1;
        int presentIdx = -1;
        int tsIdx = -1;

        for (int i = 0; i < header.length; i++) {
            String col = header[i].trim().toLowerCase(Locale.ROOT);
            if (employeeIdx < 0 && (col.contains("employee") || col.equals("name"))) {
                employeeIdx = i;
            }
            if (presentIdx < 0 && col.contains("present")) {
                presentIdx = i;
            }
            if (tsIdx < 0 && (col.contains("timestamp") || col.contains("time") || col.contains("date") || col.contains("created"))) {
                tsIdx = i;
            }
        }

        // Reasonable fallbacks if header names are unexpected
        if (employeeIdx < 0) employeeIdx = 1;
        if (presentIdx < 0)  presentIdx = 2;

        // Parse rows, extract timestamps, sort chronologically, then take the last record per name.
        List<PresenceRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(";");
            int maxIdx = Math.max(employeeIdx, presentIdx);
            if (tsIdx >= 0) maxIdx = Math.max(maxIdx, tsIdx);
            if (parts.length <= maxIdx) continue;

            String rawName = parts[employeeIdx].trim();
            if (rawName.isEmpty()) continue;

            String normalizedName = normalizeName(rawName);
            boolean present = parts[presentIdx].trim().equalsIgnoreCase("true");

            long ts = Long.MIN_VALUE;
            if (tsIdx >= 0) {
                ts = parseTimestampMillis(parts[tsIdx].trim());
            }
            // If timestamp wasn't found/parsed, fall back to row order (still deterministic).
            if (ts == Long.MIN_VALUE) {
                ts = i;
            }

            rows.add(new PresenceRow(normalizedName, present, ts, i));
        }

        if (rows.isEmpty()) return Set.of();

        // Sort by time ascending; tie-breaker: original row index.
        rows.sort(Comparator
                .comparingLong(PresenceRow::tsMillis)
                .thenComparingInt(PresenceRow::rowIndex));

        // Map: normalizedName -> last "present" flag (after chronological sort)
        Map<String, Boolean> lastStatusByName = new LinkedHashMap<>();
        for (PresenceRow r : rows) {
            lastStatusByName.put(r.name(), r.present());
        }

        Set<String> result = new LinkedHashSet<>();
        for (var e : lastStatusByName.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) result.add(e.getKey());
        }
        return result;
    }

    /**
     * Tries to parse different timestamp formats to epoch millis.
     */
    private long parseTimestampMillis(String raw) {
        if (!StringUtils.hasText(raw)) return Long.MIN_VALUE;

        String s = raw.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // 1) epoch millis / seconds
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                long v = Long.parseLong(s);
                if (v > 0 && v < 100_000_000_000L) return v * 1000L; // seconds -> millis
                return v; // already millis
            } catch (NumberFormatException ignore) {}
        }

        // 2) ISO instant
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignore) {}

        // 3) Common local datetime patterns (assume UTC if no zone is provided)
        List<java.time.format.DateTimeFormatter> fmts = List.of(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        );
        for (var fmt : fmts) {
            try {
                var ldt = java.time.LocalDateTime.parse(s, fmt);
                return ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignore) {}
        }

        return Long.MIN_VALUE;
    }

    private record PresenceRow(String name, boolean present, long tsMillis, int rowIndex) {}

    private String normalizeName(String s) {
        return (s == null) ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}

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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Gathers data for the evacuation report and delegates XLSX creation to ReportService.
 *
 * IMPORTANT:
 *  - Presence CSV can contain multiple rows for the same employee.
 *  - CSV row order is NOT guaranteed to be chronological.
 *  - Therefore we must use ONLY the CHRONOLOGICALLY last record per employee
 *    (based on parsed date) to decide whether the person is currently present.
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
            Map<String, List<EvacRecord>> recordsByEmployee = parsePresenceCsv(csv);
            Set<String> presentNames = resolvePresentNames(recordsByEmployee);
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
     * Parses the presence CSV into:
     *   Map<normalizedEmployeeName, List<EvacRecord>>
     *
     * CSV contains a HUMAN-READABLE DATE (not millis timestamp).
     * Example:
     *   Date;Employee;Present
     *   01-09-2023;Igor;true
     */
    private Map<String, List<EvacRecord>> parsePresenceCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Map.of();
        }

        // Remove UTF-8 BOM if present
        if (csv.startsWith("\uFEFF") || csv.startsWith("\ufeff")) {
            csv = csv.substring(1);
        }

        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) {
            return Map.of();
        }

        // --- Determine column indexes from header ---
        String[] header = lines[0].split(";", -1);
        int dateIdx = -1;
        int employeeIdx = -1;
        int presentIdx = -1;
        for (int i = 0; i < header.length; i++) {
            String col = header[i].trim().toLowerCase(Locale.ROOT);
            if (dateIdx < 0 && col.contains("date")) {
                dateIdx = i;
            }
            if (employeeIdx < 0 && (col.contains("employee") || col.equals("name"))) {
                employeeIdx = i;
            }
            if (presentIdx < 0 && col.contains("present")) {
                presentIdx = i;
            }
        }
        // Reasonable fallbacks if header names are unexpected
        if (dateIdx < 0) dateIdx = 0;
        if (employeeIdx < 0) employeeIdx = 1;
        if (presentIdx < 0) presentIdx = 2;

        int maxIdx = Math.max(dateIdx, Math.max(employeeIdx, presentIdx));
        Map<String, List<EvacRecord>> out = new LinkedHashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(";", -1);
            if (parts.length <= maxIdx) {
                log.debug("Skip CSV row {}: not enough columns: '{}'", i, line);
                continue;
            }

            String dateStr = parts[dateIdx].trim();
            String rawName = parts[employeeIdx].trim();
            String presentStr = parts[presentIdx].trim();

            if (!StringUtils.hasText(rawName)) {
                log.debug("Skip CSV row {}: empty employee name: '{}'", i, line);
                continue;
            }
            if (!StringUtils.hasText(dateStr)) {
                log.debug("Skip CSV row {}: empty date: '{}'", i, line);
                continue;
            }

            Long ts = parseHumanDateToMillis(dateStr);
            if (ts == null) {
                log.warn("Skip CSV row {}: cannot parse date '{}' in line: '{}'", i, dateStr, line);
                continue;
            }

            boolean presence = "true".equalsIgnoreCase(presentStr.trim());
            String normalizedName = normalizeName(rawName);

            out.computeIfAbsent(normalizedName, k -> new ArrayList<>())
                    .add(new EvacRecord(dateStr, ts, presence));
        }
        return out;
    }

    /**
     * From Map<employee, records>, determine which employees are "present" by:
     *  - sorting by timestamp
     *  - taking the chronologically last record
     */
    private Set<String> resolvePresentNames(Map<String, List<EvacRecord>> recordsByEmployee) {
        if (recordsByEmployee == null || recordsByEmployee.isEmpty()) {
            return Set.of();
        }

        Set<String> present = new LinkedHashSet<>();
        for (Map.Entry<String, List<EvacRecord>> e : recordsByEmployee.entrySet()) {
            List<EvacRecord> records = e.getValue();
            if (records == null || records.isEmpty()) continue;

            records.sort(Comparator.comparingLong(r -> r.timestamp == null ? Long.MIN_VALUE : r.timestamp));
            EvacRecord last = records.get(records.size() - 1);
            if (last != null && last.presense) {
                present.add(e.getKey());
            }
        }
        return present;
    }

    /**
     * Presence CSV provides a human-readable date.
     * We parse it into epoch millis to be able to sort chronologically.
     */
    private Long parseHumanDateToMillis(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim();

        // Common formats we have seen / may receive.
        // If time is not provided, we treat it as start of day in system timezone.
        List<DateTimeFormatter> dateOnly = List.of(
                DateTimeFormatter.ofPattern("dd-MM-uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ISO_LOCAL_DATE
        );

        ZoneId zone = ZoneId.systemDefault();

        // If string contains time, try to parse LocalDateTime.
        if (s.contains(":")) {
            List<DateTimeFormatter> dateTime = List.of(
                    DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss"),
                    DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm"),
                    DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss"),
                    DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
            );
            for (DateTimeFormatter f : dateTime) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(s, f);
                    return ldt.atZone(zone).toInstant().toEpochMilli();
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        for (DateTimeFormatter f : dateOnly) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                return d.atStartOfDay(zone).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String normalizeName(String s) {
        return (s == null) ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * One CSV row represented as a strongly typed record.
     *
     * parsedTime - original human readable date string from CSV (as-is)
     * timestamp  - epoch millis parsed from parsedTime
     * presense   - CSV "Present" boolean (kept with original typo requested)
     */
    static class EvacRecord {
        public final String parsedTime;
        public final Long timestamp;
        public final boolean presense;

        EvacRecord(String parsedTime, Long timestamp, boolean presense) {
            this.parsedTime = parsedTime;
            this.timestamp = timestamp;
            this.presense = presense;
        }
    }
}

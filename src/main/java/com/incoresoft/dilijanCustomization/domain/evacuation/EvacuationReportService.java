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
        for (int i = 0; i < header.length; i++) {
            String col = header[i].trim().toLowerCase(Locale.ROOT);
            if (employeeIdx < 0 && (col.contains("employee") || col.equals("name"))) {
                employeeIdx = i;
            }
            if (presentIdx < 0 && col.contains("present")) {
                presentIdx = i;
            }
        }
        // Reasonable fallbacks if header names are unexpected
        if (employeeIdx < 0) {
            employeeIdx = 1;
        }
        if (presentIdx < 0) {
            presentIdx = 2;
        }
        // Map: normalizedName -> last "present" flag
        Map<String, Boolean> lastStatusByName = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(";");
            int maxIdx = Math.max(employeeIdx, presentIdx);
            if (parts.length <= maxIdx) {
                continue;
            }
            String rawName = parts[employeeIdx].trim();
            if (rawName.isEmpty()) {
                continue;
            }
            String normalizedName = normalizeName(rawName);
            String presentStr = parts[presentIdx].trim().toLowerCase(Locale.ROOT);
            boolean present = presentStr.equals("true");
            lastStatusByName.put(normalizedName, present);
        }
        if (lastStatusByName.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> entry : lastStatusByName.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private String normalizeName(String s) {
        return (s == null) ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}

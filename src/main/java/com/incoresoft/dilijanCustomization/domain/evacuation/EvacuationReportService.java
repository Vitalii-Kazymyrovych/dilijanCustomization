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

        // Delegate XLSX generation (temp file here — adjust pathing if needed)
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

    /** Parse CSV and return a deduplicated set of lowercased "present" names (Employee column). */
    private Set<String> parseCsvPresentNames(String csv) {
        if (!StringUtils.hasText(csv)) return Set.of();
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);

        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) return Set.of();

        String[] header = lines[0].split(";");
        int employeeIdx = -1, presentIdx = -1;
        for (int i = 0; i < header.length; i++) {
            String col = header[i].trim().toLowerCase(Locale.ROOT);
            if (employeeIdx < 0 && (col.contains("employee") || col.equals("name"))) employeeIdx = i;
            if (presentIdx < 0 && col.contains("present")) presentIdx = i;
        }
        if (employeeIdx < 0) employeeIdx = 1;
        if (presentIdx < 0) presentIdx = 2;

        Set<String> result = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(";");
            if (parts.length <= Math.max(employeeIdx, presentIdx)) continue;

            String presentStr = parts[presentIdx].trim().toLowerCase(Locale.ROOT);
            boolean present = presentStr.equals("true") || presentStr.equals("1") || presentStr.equals("yes") || presentStr.equals("да");
            if (!present) continue;

            String name = parts[employeeIdx].trim();
            if (!name.isEmpty()) result.add(normalizeName(name));
        }
        return result;
    }

    private String normalizeName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}

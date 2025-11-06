package com.incoresoft.dilijanCustomization.domain.attendance.service;

import com.incoresoft.dilijanCustomization.config.CafeteriaProps;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.service.ReportService;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CafeteriaReportService {

    private final CafeteriaProps cafe;
    private final FaceApiRepository repo;
    private final ReportService reportService;

    @Scheduled(cron = "${vezha.cafe.schedule-cron:0 0 22 * * *}", zone = "${vezha.cafe.timezone:Asia/Yerevan}")
    public void generateDaily() {
        try {
            buildSingleDayReport(LocalDate.now(ZoneId.of(cafe.getTimezone())));
        } catch (Exception ex) {
            log.error("Cafeteria report generation failed: {}", ex.getMessage(), ex);
        }
    }

    public File buildSingleDayReport(LocalDate date) throws Exception {
        return buildSingleDayReport(date, null, null);
    }

    public File buildSingleDayReport(LocalDate date, String tzOverride, List<Long> onlyListIds) throws Exception {
        ZoneId zone = resolveZone(tzOverride);

        long brStart = toMillis(date.atTime(cafe.getBreakfastStart()), zone);
        long brEnd   = toMillis(date.atTime(cafe.getBreakfastEnd()),   zone);
        long luStart = toMillis(date.atTime(cafe.getLunchStart()),     zone);
        long luEnd   = toMillis(date.atTime(cafe.getLunchEnd()),       zone);
        long diStart = toMillis(date.atTime(cafe.getDinnerStart()),    zone);
        long diEnd   = toMillis(date.atTime(cafe.getDinnerEnd()),      zone);

        Map<Long, String> listIdToName = fetchListNames();
        Set<String> excludedNames = cafe.getExcludedListNames().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toSet());

        List<Long> targetListIds = listIdToName.entrySet().stream()
                .filter(e -> onlyListIds == null || onlyListIds.isEmpty() || onlyListIds.contains(e.getKey()))
                .filter(e -> !excludedNames.contains(safeLower(e.getValue())))
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        // Unique IDs per meal, per list
        Map<Long, Set<Long>> brUniques = queryUniqueListItemIds(brStart, brEnd, targetListIds);
        Map<Long, Set<Long>> luUniques = queryUniqueListItemIds(luStart, luEnd, targetListIds);
        Map<Long, Set<Long>> diUniques = queryUniqueListItemIds(diStart, diEnd, targetListIds);

        // Build pivot rows (alphabetical by list name)
        List<CafeteriaPivotRow> rows = new ArrayList<>();
        targetListIds.stream()
                .sorted(Comparator.comparing(id -> listIdToName.getOrDefault(id, "").toLowerCase(Locale.ROOT)))
                .forEach(id -> {
                    String name = listIdToName.getOrDefault(id, "list_" + id);
                    int b = sizeOf(brUniques.get(id));
                    int l = sizeOf(luUniques.get(id));
                    int d = sizeOf(diUniques.get(id));
                    rows.add(new CafeteriaPivotRow(name, b, l, d));
                });

        // Prepare output path
        File outDir = new File(cafe.getOutputDir());
        Files.createDirectories(outDir.toPath());
        File out = new File(outDir, date.toString() + ".xlsx");

        // Delegate Excel writing
        File result = reportService.exportCafeteriaPivot(date, "Cafeteria", rows, out);
        log.info("Cafeteria report generated (tz={}): {}", zone, result.getAbsolutePath());
        return result;
    }

    /** DEDUP: per list, unique list_item.id within the given time window */
    private Map<Long, Set<Long>> queryUniqueListItemIds(long startMillis, long endMillis, List<Long> listIds) {
        Map<Long, Set<Long>> uniquesByList = new HashMap<>();
        for (Long listId : listIds) {
            List<DetectionDto> dets = repo.getAllDetectionsInWindow(
                    listId,
                    cafe.getAnalyticsIds(),
                    startMillis,
                    endMillis,
                    500
            );
            Set<Long> uniqueIds = uniquesByList.computeIfAbsent(listId, k -> new HashSet<>());
            for (DetectionDto d : dets) {
                Long listItemId = (d.getListItem() != null) ? d.getListItem().getId() : null;
                if (listItemId != null) uniqueIds.add(listItemId);
            }
        }
        // Ensure empty sets for lists without detections (visible zeros)
        for (Long id : listIds) uniquesByList.computeIfAbsent(id, k -> new HashSet<>());
        return uniquesByList;
    }

    private Map<Long, String> fetchListNames() {
        FaceListsResponse lists = repo.getFaceLists(200);
        Map<Long, String> map = new HashMap<>();
        if (lists != null && lists.getData() != null) {
            for (FaceListDto l : lists.getData()) {
                if (l.getId() != null && StringUtils.hasText(l.getName())) {
                    map.put(l.getId(), l.getName().trim());
                }
            }
        }
        return map;
    }

    private ZoneId resolveZone(String tzOverride) {
        String cfg = cafe.getTimezone();
        if (!StringUtils.hasText(tzOverride)) return ZoneId.of(cfg);
        try { return ZoneId.of(tzOverride.trim()); }
        catch (Exception ex) {
            log.warn("Invalid tz '{}', falling back to props tz '{}': {}", tzOverride, cfg, ex.getMessage());
            return ZoneId.of(cfg);
        }
    }

    private static long toMillis(LocalDateTime ldt, ZoneId zone) {
        return ldt.atZone(zone).toInstant().toEpochMilli();
    }

    private static int sizeOf(Set<Long> s) { return (s == null) ? 0 : s.size(); }
    private static String safeLower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }
}

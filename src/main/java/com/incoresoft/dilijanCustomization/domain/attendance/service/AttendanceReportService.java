package com.incoresoft.dilijanCustomization.domain.attendance.service;

import com.incoresoft.dilijanCustomization.config.CafeteriaProps;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.service.ReportService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceReportService {

    private static final int DEFAULT_FACE_LIST_LIMIT = 200;
    private static final int DETECTION_PAGE_LIMIT = 500;
    private static final String DEFAULT_SHEET_NAME = "Cafeteria";

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

        MealWindows mealWindows = buildMealWindows(date, zone);

        Map<Long, String> listIdToName = fetchListNames();
        List<Long> targetListIds = resolveTargetListIds(onlyListIds, listIdToName);

        Map<Long, Set<Long>> breakfastCounts =
                queryUniqueListItemIds(mealWindows.breakfastStart(), mealWindows.breakfastEnd(), targetListIds);
        Map<Long, Set<Long>> lunchCounts =
                queryUniqueListItemIds(mealWindows.lunchStart(), mealWindows.lunchEnd(), targetListIds);
        Map<Long, Set<Long>> dinnerCounts =
                queryUniqueListItemIds(mealWindows.dinnerStart(), mealWindows.dinnerEnd(), targetListIds);

        List<CafeteriaPivotRow> rows = buildPivotRows(listIdToName, targetListIds, breakfastCounts, lunchCounts, dinnerCounts);
        File outputFile = prepareOutputFile(date);
        File result = reportService.exportCafeteriaPivot(date, DEFAULT_SHEET_NAME, rows, outputFile);
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
                    DETECTION_PAGE_LIMIT
            );
            Set<Long> uniqueIds = uniquesByList.computeIfAbsent(listId, k -> new HashSet<>());
            collectUniqueListItemIds(dets, uniqueIds);
        }
        // Ensure empty sets for lists without detections (visible zeros)
        for (Long id : listIds) uniquesByList.computeIfAbsent(id, k -> new HashSet<>());
        return uniquesByList;
    }

    private void collectUniqueListItemIds(List<DetectionDto> detections, Set<Long> uniqueIds) {
        detections.stream()
                .map(d -> d.getListItem() != null ? d.getListItem().getId() : null)
                .filter(Objects::nonNull)
                .forEach(uniqueIds::add);
    }

    private Map<Long, String> fetchListNames() {
        FaceListsResponse lists = repo.getFaceLists(DEFAULT_FACE_LIST_LIMIT);
        if (lists == null || lists.getData() == null) {
            return Map.of();
        }
        return lists.getData().stream()
                .filter(l -> l.getId() != null && StringUtils.hasText(l.getName()))
                .collect(Collectors.toMap(
                        FaceListDto::getId,
                        l -> l.getName().trim(),
                        (existing, replacement) -> replacement,
                        HashMap::new));
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

    private File prepareOutputFile(LocalDate date) throws Exception {
        File outDir = new File(cafe.getOutputDir());
        Files.createDirectories(outDir.toPath());
        return new File(outDir, date + ".xlsx");
    }

    private List<CafeteriaPivotRow> buildPivotRows(Map<Long, String> listIdToName,
                                                  List<Long> targetListIds,
                                                  Map<Long, Set<Long>> breakfastCounts,
                                                  Map<Long, Set<Long>> lunchCounts,
                                                  Map<Long, Set<Long>> dinnerCounts) {
        List<CafeteriaPivotRow> rows = new ArrayList<>();
        targetListIds.stream()
                .sorted(Comparator.comparing(id -> listIdToName.getOrDefault(id, "").toLowerCase(Locale.ROOT)))
                .forEach(id -> {
                    String name = listIdToName.getOrDefault(id, "list_" + id);
                    int breakfast = sizeOf(breakfastCounts.get(id));
                    int lunch = sizeOf(lunchCounts.get(id));
                    int dinner = sizeOf(dinnerCounts.get(id));
                    rows.add(new CafeteriaPivotRow(name, breakfast, lunch, dinner));
                });
        return rows;
    }

    private List<Long> resolveTargetListIds(List<Long> onlyListIds, Map<Long, String> listIdToName) {
        Set<String> excludedNames = cafe.getExcludedListNames().stream()
                .map(AttendanceReportService::safeLower)
                .collect(Collectors.toSet());

        return listIdToName.entrySet().stream()
                .filter(e -> onlyListIds == null || onlyListIds.isEmpty() || onlyListIds.contains(e.getKey()))
                .filter(e -> !excludedNames.contains(safeLower(e.getValue())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private MealWindows buildMealWindows(LocalDate date, ZoneId zone) {
        long breakfastStart = toMillis(date.atTime(cafe.getBreakfastStart()), zone);
        long breakfastEnd = toMillis(date.atTime(cafe.getBreakfastEnd()), zone);
        long lunchStart = toMillis(date.atTime(cafe.getLunchStart()), zone);
        long lunchEnd = toMillis(date.atTime(cafe.getLunchEnd()), zone);
        long dinnerStart = toMillis(date.atTime(cafe.getDinnerStart()), zone);
        long dinnerEnd = toMillis(date.atTime(cafe.getDinnerEnd()), zone);
        return new MealWindows(breakfastStart, breakfastEnd, lunchStart, lunchEnd, dinnerStart, dinnerEnd);
    }

    private static int sizeOf(Set<Long> s) { return (s == null) ? 0 : s.size(); }

    private static String safeLower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }

    private record MealWindows(long breakfastStart, long breakfastEnd,
                               long lunchStart, long lunchEnd,
                               long dinnerStart, long dinnerEnd) {
    }
}

package com.incoresoft.dilijanCustomization.domain.attendance.service;

import com.incoresoft.dilijanCustomization.config.CafeteriaProps;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CafeteriaReportService {

    private final CafeteriaProps cafe;
    private final FaceApiRepository repo;

    // Nightly generation using properties (keep as you already configured)
    @Scheduled(cron = "${vezha.cafe.schedule-cron:0 0 22 * * *}", zone = "${vezha.cafe.timezone:Asia/Yerevan}")
    public void generateDaily() {
        try {
            buildSingleDayReport(LocalDate.now(ZoneId.of(cafe.getTimezone())));
        } catch (Exception ex) {
            log.error("Cafeteria report generation failed: {}", ex.getMessage(), ex);
        }
    }

    // Default entry (scheduler): uses props timezone
    public File buildSingleDayReport(LocalDate date) throws Exception {
        return buildSingleDayReport(date, null, null);
    }

    /**
     * Test-friendly variant with timezone override and optional filtering by list IDs.
     * - tzOverride example: "Europe/Kyiv"
     * - onlyListIds example: [2,5,7]; if null or empty => all lists (except excluded names)
     */
    public File buildSingleDayReport(LocalDate date, String tzOverride, List<Long> onlyListIds) throws Exception {
        ZoneId zone = resolveZone(tzOverride);

        long brStart = toMillis(date.atTime(cafe.getBreakfastStart()), zone);
        long brEnd   = toMillis(date.atTime(cafe.getBreakfastEnd()),   zone);
        long luStart = toMillis(date.atTime(cafe.getLunchStart()),     zone);
        long luEnd   = toMillis(date.atTime(cafe.getLunchEnd()),       zone);
        long diStart = toMillis(date.atTime(cafe.getDinnerStart()),    zone);
        long diEnd   = toMillis(date.atTime(cafe.getDinnerEnd()),      zone);

        // 1) All lists (id -> name) minus excluded by NAME; optionally restrict to onlyListIds
        Map<Long, String> listIdToName = fetchListNames();
        Set<String> excluded = cafe.getExcludedListNames().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toSet());

        List<Long> targetListIds = listIdToName.keySet().stream()
                .filter(id -> onlyListIds == null || onlyListIds.isEmpty() || onlyListIds.contains(id))
                .filter(id -> !excluded.contains(listIdToName.get(id).toLowerCase(Locale.ROOT)))
                .sorted()
                .collect(Collectors.toList());

        // 2) Aggregate for each window using detections (listed only, unique by list_item.id)
        Map<String,Integer> breakfast = aggregateListed(brStart, brEnd, targetListIds, listIdToName);
        Map<String,Integer> lunch     = aggregateListed(luStart, luEnd, targetListIds, listIdToName);
        Map<String,Integer> dinner    = aggregateListed(diStart, diEnd, targetListIds, listIdToName);

        // 3) Build XLSX
        File outDir = new File(cafe.getOutputDir());
        Files.createDirectories(outDir.toPath());
        File out = new File(outDir, date.toString() + ".xlsx");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Sheet1");
            int r = 0;

            Row header = sh.createRow(r++);
            header.createCell(0).setCellValue("Category");
            header.createCell(1).setCellValue("Amount of people");

            r = writeBlock(sh, r, "Breakfast", breakfast);
            r = writeBlock(sh, r, "Lunch",     lunch);
            r = writeBlock(sh, r, "Dinner",    dinner);

            sh.autoSizeColumn(0);
            sh.autoSizeColumn(1);

            try (FileOutputStream fos = new FileOutputStream(out)) {
                wb.write(fos);
            }
        }
        log.info("Cafeteria report generated (tz={}): {}", zone, out.getAbsolutePath());
        return out;
    }

    /** Listed-only aggregation: per list -> unique count of list_item.id */
    private Map<String,Integer> aggregateListed(
            long startMillis,
            long endMillis,
            List<Long> listIds,
            Map<Long, String> listIdToName
    ) {
        Map<String, Set<Long>> uniquesByListName = new HashMap<>();

        for (Long listId : listIds) {
            String listName = listIdToName.getOrDefault(listId, "list_" + listId);

            List<DetectionDto> dets = repo.getAllDetectionsInWindow(
                    listId,
                    cafe.getAnalyticsIds(),
                    startMillis,
                    endMillis,
                    500
            );

            for (DetectionDto d : dets) {
                Long listItemId = (d.getListItem() != null)
                        ? d.getListItem().getId() : null;
                if (listItemId != null) {
                    uniquesByListName.computeIfAbsent(listName, k -> new HashSet<>()).add(listItemId);
                }
            }
        }

        // convert Set sizes to counts and return alphabetical by name
        Map<String,Integer> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Set<Long>> e : uniquesByListName.entrySet()) {
            result.put(e.getKey(), e.getValue().size());
        }
        return result;
    }

    private int writeBlock(Sheet sh, int r, String title, Map<String,Integer> counts) {
        Row head = sh.createRow(r++);
        head.createCell(0).setCellValue(title);

        // sorted by list name (already TreeMap, but safe to re-sort)
        List<Map.Entry<String,Integer>> rows = new ArrayList<>(counts.entrySet());
        rows.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));

        for (Map.Entry<String,Integer> e : rows) {
            Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
        }
        return r;
    }

    private Map<Long, String> fetchListNames() {
        FaceListsResponse lists = repo.getFaceLists(200);
        Map<Long, String> map = new HashMap<>();
        if (lists != null && lists.getData() != null) {
            for (FaceListDto l : lists.getData()) {
                if (l.getId() != null && StringUtils.hasText(l.getName())) {
                    map.put(l.getId(), l.getName());
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
}

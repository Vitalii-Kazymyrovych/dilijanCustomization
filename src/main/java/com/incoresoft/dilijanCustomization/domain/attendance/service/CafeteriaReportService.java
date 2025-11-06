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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cafeteria attendance report:
 * One sheet, per-list unique people counts for Breakfast / Lunch / Dinner.
 * Deduplication key: list_item.id (unique within each time window independently).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CafeteriaReportService {

    private final CafeteriaProps cafe;
    private final FaceApiRepository repo;

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

    /**
     * @param tzOverride e.g. "Europe/Kyiv" for testing; null -> props timezone
     * @param onlyListIds restrict to these list IDs; null/empty -> all (minus excluded by name)
     */
    public File buildSingleDayReport(LocalDate date, String tzOverride, List<Long> onlyListIds) throws Exception {
        ZoneId zone = resolveZone(tzOverride);

        long brStart = toMillis(date.atTime(cafe.getBreakfastStart()), zone);
        long brEnd   = toMillis(date.atTime(cafe.getBreakfastEnd()),   zone);
        long luStart = toMillis(date.atTime(cafe.getLunchStart()),     zone);
        long luEnd   = toMillis(date.atTime(cafe.getLunchEnd()),       zone);
        long diStart = toMillis(date.atTime(cafe.getDinnerStart()),    zone);
        long diEnd   = toMillis(date.atTime(cafe.getDinnerEnd()),      zone);

        // id -> name (filtered by onlyListIds and excluded names)
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

        // Query unique list_item IDs per meal window (per-list)
        Map<Long, Set<Long>> brUniques = queryUniqueListItemIds(brStart, brEnd, targetListIds);
        Map<Long, Set<Long>> luUniques = queryUniqueListItemIds(luStart, luEnd, targetListIds);
        Map<Long, Set<Long>> diUniques = queryUniqueListItemIds(diStart, diEnd, targetListIds);

        // Build pivot: list name -> [b,l,d,total]
        List<RowData> rows = new ArrayList<>();
        for (Long listId : targetListIds) {
            String name = listIdToName.getOrDefault(listId, "list_" + listId);
            int b = sizeOf(brUniques.get(listId));
            int l = sizeOf(luUniques.get(listId));
            int d = sizeOf(diUniques.get(listId));
            rows.add(new RowData(name, b, l, d));
        }
        // Sort by name (case-insensitive)
        rows.sort(Comparator.comparing(r -> r.category.toLowerCase(Locale.ROOT)));

        // Create XLSX (single sheet)
        File outDir = new File(cafe.getOutputDir());
        Files.createDirectories(outDir.toPath());
        File out = new File(outDir, date.toString() + ".xlsx");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Cafeteria");
            int r = 0;

            // Header
            Row header = sh.createRow(r++);
            header.createCell(0).setCellValue("Category");
            header.createCell(1).setCellValue("Breakfast");
            header.createCell(2).setCellValue("Lunch");
            header.createCell(3).setCellValue("Dinner");
            header.createCell(4).setCellValue("Total");

            // Body
            for (RowData rd : rows) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(rd.category);
                row.createCell(1).setCellValue(rd.breakfast);
                row.createCell(2).setCellValue(rd.lunch);
                row.createCell(3).setCellValue(rd.dinner);
                row.createCell(4).setCellValue(rd.total());
            }

            // Grand total row
            Row totalRow = sh.createRow(r++);
            totalRow.createCell(0).setCellValue("Grand Total");
            totalRow.createCell(1).setCellValue(rows.stream().mapToInt(rd -> rd.breakfast).sum());
            totalRow.createCell(2).setCellValue(rows.stream().mapToInt(rd -> rd.lunch).sum());
            totalRow.createCell(3).setCellValue(rows.stream().mapToInt(rd -> rd.dinner).sum());
            totalRow.createCell(4).setCellValue(rows.stream().mapToInt(RowData::total).sum());

            // Autosize
            for (int c = 0; c <= 4; c++) sh.autoSizeColumn(c);

            try (FileOutputStream fos = new FileOutputStream(out)) {
                wb.write(fos);
            }
        }

        log.info("Cafeteria report generated (tz={}): {}", zone, out.getAbsolutePath());
        return out;
    }

    /** Query detections and return per-list unique list_item IDs for the window. */
    private Map<Long, Set<Long>> queryUniqueListItemIds(long startMillis, long endMillis, List<Long> listIds) {
        Map<Long, Set<Long>> uniquesByList = new HashMap<>();
        for (Long listId : listIds) {
            // Pull detections for this list within the window.
            List<DetectionDto> dets = repo.getAllDetectionsInWindow(
                    listId,
                    cafe.getAnalyticsIds(),
                    startMillis,
                    endMillis,
                    500
            );

            // Deduplicate by list_item.id
            Set<Long> uniqueIds = uniquesByList.computeIfAbsent(listId, k -> new HashSet<>());
            for (DetectionDto d : dets) {
                Long listItemId = (d.getListItem() != null) ? d.getListItem().getId() : null;
                if (listItemId != null) uniqueIds.add(listItemId);
            }
        }
        // Ensure empty sets for lists with no detections (so they appear with zeros)
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
        try {
            return ZoneId.of(tzOverride.trim());
        } catch (Exception ex) {
            log.warn("Invalid tz '{}', falling back to props tz '{}': {}", tzOverride, cfg, ex.getMessage());
            return ZoneId.of(cfg);
        }
    }

    private static long toMillis(LocalDateTime ldt, ZoneId zone) {
        return ldt.atZone(zone).toInstant().toEpochMilli();
    }

    private static int sizeOf(Set<Long> s) { return (s == null) ? 0 : s.size(); }
    private static String safeLower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }

    private static class RowData {
        final String category;
        final int breakfast;
        final int lunch;
        final int dinner;
        RowData(String category, int breakfast, int lunch, int dinner) {
            this.category = category;
            this.breakfast = breakfast;
            this.lunch = lunch;
            this.dinner = dinner;
        }
        int total() { return breakfast + lunch + dinner; }
    }
}

package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.domain.attendance.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

/**
 * Builds the evacuation report as requested:
 * 1) For each listId:
 *    - fetch FaceListDto
 *    - fetch presence CSV, keep Present=true, deduplicate by Employee (name)
 *    - fetch all ListItemDto for that list
 *    - filter items to those present by name
 *    - add to map <FaceListDto, List<ListItemDto>>
 * 2) Build XLSX with a sheet per list: [☐, Photo, Name, Comment]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvacuationReportService {
    // Tune these to taste
    private static final float ROW_HEIGHT_PT = 150f;          // row height in points (e.g., 120 pt ≈ 160 px)
    private static final int COL_WIDTH_CHECKBOX = 15 * 256;    // Excel column width units (chars * 256)
    private static final int COL_WIDTH_PHOTO   = 30 * 256;    // make photo column wide
    private static final int COL_WIDTH_NAME    = 50 * 256;
    private static final int COL_WIDTH_COMMENT = 50 * 256;
    private static final String[] EVAC_OPTIONS = {"On site", "Evacuated"};

    private final FaceApiRepository repo;

    public File buildEvacuationReport(List<Long> listIds) throws Exception {
        if (listIds == null || listIds.isEmpty()) {
            throw new IllegalArgumentException("listIds cannot be empty");
        }

        // Keep a predictable order
        List<Long> sortedIds = new ArrayList<>(listIds);
        Collections.sort(sortedIds);

        // The resulting map <FaceListDto, List<ListItemDto>>
        Map<FaceListDto, List<ListItemDto>> data = new LinkedHashMap<>();

        long now = Instant.now().toEpochMilli();

        for (Long listId : sortedIds) {
            // 1) FaceList meta (name etc.)
            FaceListDto faceList = fetchFaceListMeta(listId).get();

            // 2) Evacuation CSV -> set of present names (deduped)
            String csv = repo.downloadPresenceCsv(listId, now); // per your latest change, start_date = end - 30d
            Set<String> presentNames = parseCsvPresentNames(csv);
            if (presentNames.isEmpty()) {
                data.put(faceList, List.of());
                continue;
            }

            // 3) All list items for this list
            List<ListItemDto> allItems = fetchAllListItems(listId);

            // 4) Filter by presence (by name)
            List<ListItemDto> presentItems = allItems.stream()
                    .filter(it -> StringUtils.hasText(it.getName()))
                    .filter(it -> presentNames.contains(normalizeName(it.getName())))
                    // Sort by name for readability
                    .sorted(Comparator.comparing(li -> li.getName().toLowerCase(Locale.ROOT)))
                    .toList();

            data.put(faceList, presentItems);
        }

        // 5) Build XLSX
        return buildWorkbook(data);
    }

    private Optional<FaceListDto> fetchFaceListMeta(Long listId) {
        return repo.getFaceLists(100)
                .getData()
                .stream()
                .filter(d -> d.getId() == listId)
                .findFirst();
    }

    private List<ListItemDto> fetchAllListItems(Long listId) {
        // If your lists may be large, implement paging; for now limit=1000 is fine.
        ListItemsResponse resp = repo.getListItems(listId, "", "", 0, 1000, "asc", "name");
        return (resp != null && resp.getData() != null) ? resp.getData() : List.of();
    }

    /** Parse CSV and return a de-duplicated set of lowercased "present" names (Employee column). */
    private Set<String> parseCsvPresentNames(String csv) {
        if (!StringUtils.hasText(csv)) return Set.of();
        // Remove UTF-8 BOM if present
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);

        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) return Set.of();

        // Detect columns by header names
        String[] header = lines[0].split(";");
        int employeeIdx = -1;
        int presentIdx = -1;
        for (int i = 0; i < header.length; i++) {
            String col = header[i].trim().toLowerCase(Locale.ROOT);
            if (employeeIdx < 0 && (col.contains("employee") || col.equals("name"))) employeeIdx = i;
            if (presentIdx < 0 && col.contains("present")) presentIdx = i;
        }
        // Fallbacks if needed (CSV sample was: Date;Employee;Present)
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
            if (!name.isEmpty()) {
                result.add(normalizeName(name));
            }
        }
        return result;
    }

    private String normalizeName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private File buildWorkbook(Map<FaceListDto, List<ListItemDto>> data) throws Exception {
        File out = File.createTempFile("evacuation-", ".xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            for (Map.Entry<FaceListDto, List<ListItemDto>> e : data.entrySet()) {
                FaceListDto list = e.getKey();
                List<ListItemDto> items = e.getValue();

                String sheetName = sanitizeSheetName(
                        StringUtils.hasText(list.getName()) ? list.getName() : "List_" + list.getId()
                );
                Sheet sh = wb.createSheet(sheetName);

                // Header
                Row header = sh.createRow(0);
                header.createCell(0).setCellValue(" ");
                header.createCell(1).setCellValue("Photo");
                header.createCell(2).setCellValue("Name");
                header.createCell(3).setCellValue("Comment");
                sh.setDefaultRowHeightInPoints(ROW_HEIGHT_PT);
                sh.setColumnWidth(0, COL_WIDTH_CHECKBOX);
                sh.setColumnWidth(1, COL_WIDTH_PHOTO);
                sh.setColumnWidth(2, COL_WIDTH_NAME);
                sh.setColumnWidth(3, COL_WIDTH_COMMENT);
                header.setHeightInPoints(24f);

                Drawing<?> drawing = sh.createDrawingPatriarch();
                int r = 1;

                for (ListItemDto item : items) {
                    Row row = sh.createRow(r);
                    // First column will have a dropdown; set an empty default value + centering style
                    Cell c0 = row.createCell(0);
                    c0.setCellValue("On site"); // default
                    c0.setCellStyle(checkboxColumnStyle(wb));

                    // Photo
                    try {
                        String firstImagePath = firstImagePath(item);
                        if (StringUtils.hasText(firstImagePath)) {
                            byte[] img = repo.downloadStorageObject(firstImagePath);
                            if (img != null && img.length > 0) {
                                int picIdx = wb.addPicture(img, Workbook.PICTURE_TYPE_JPEG);
                                CreationHelper helper = wb.getCreationHelper();
                                ClientAnchor anchor = helper.createClientAnchor();
                                anchor.setRow1(r);
                                anchor.setRow2(r + 1);
                                anchor.setCol1(1);
                                anchor.setCol2(2);
                                Picture picture = drawing.createPicture(anchor, picIdx);
                                picture.resize(1.0, 1.0);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Photo embedding failed for list {} item {}: {}", list.getId(), item.getId(), ex.getMessage());
                    }

                    // Name & Comment
                    row.createCell(2).setCellValue(nullSafe(item.getName()));
                    row.createCell(3).setCellValue(nullSafe(item.getComment()));

                    r++;
                }

                // Create a dropdown (data validation) for column A on all data rows (row 1..r-1)
                DataValidationHelper dvHelper = sh.getDataValidationHelper();
                DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(EVAC_OPTIONS);

                // rows: from first data row (1) to last written row (r - 1), column 0 (A)
                CellRangeAddressList addressList = new CellRangeAddressList(1, r - 1, 0, 0);
                DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);

                // Hints for Excel behavior
                validation.setSuppressDropDownArrow(false);     // show the arrow
                validation.setEmptyCellAllowed(true);
                validation.setShowErrorBox(true);

                sh.addValidationData(validation);
            }

            try (FileOutputStream fos = new FileOutputStream(out)) {
                wb.write(fos);
            }
        }
        return out;
    }

    // NOTE: adjust this to your actual ListItemDto structure if needed.
    private String firstImagePath(ListItemDto item) {
        try {
            if (item.getImages() != null && !item.getImages().isEmpty()) {
                var img = item.getImages().get(0);
                // If your Image DTO uses another getter, change here:
                return img.getPath();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String sanitizeSheetName(String raw) {
        String cleaned = raw.replaceAll("[:\\\\/*?\\[\\]]", "_");
        return cleaned.length() <= 31 ? cleaned : cleaned.substring(0, 31);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private CellStyle checkboxColumnStyle(Workbook wb) {
        CellStyle cs = wb.createCellStyle();
        cs.setAlignment(HorizontalAlignment.CENTER);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);
        return cs;
    }
}

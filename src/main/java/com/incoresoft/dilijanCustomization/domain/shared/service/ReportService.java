package com.incoresoft.dilijanCustomization.domain.shared.service;

import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationReportRow;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
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
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {
    private static final float ROW_HEIGHT_PT = 150f;
    private static final int COL_WIDTH_CHECKBOX = 15 * 256;
    private static final int COL_WIDTH_TIME    = 25 * 256;
    private static final int COL_WIDTH_PHOTO   = 30 * 256;
    private static final int COL_WIDTH_NAME    = 50 * 256;
    private static final int COL_WIDTH_COMMENT = 50 * 256;
    /** Width of the ID column in characters (scaled by 256 for POI). */
    private static final int COL_WIDTH_ID      = 20 * 256;
    private static final String CHECKBOX_CHECKED = "TRUE";
    private static final String CHECKBOX_UNCHECKED = "FALSE";
    private static final String[] EVAC_CHECKBOX_OPTIONS = {CHECKBOX_CHECKED, CHECKBOX_UNCHECKED};
    private static final List<String> COLUMNS = List.of("Category", "Breakfast", "Lunch", "Dinner", "Total");

    private final FaceApiRepository repo;

    /**
     * Create a single-sheet XLSX with columns:
     * Category | Breakfast | Lunch | Dinner | Total
     */
    public File exportCafeteriaPivot(LocalDate date, String sheetName, List<CafeteriaPivotRow> rows, File outFile) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet(sheetName);

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Header
            Row header = sh.createRow(0);
            header.setHeightInPoints(18f);
            for (int i = 0; i < COLUMNS.size(); i++) {
                createCell(header, i, COLUMNS.get(i), headerStyle);
            }

            // Body
            int rowIdx = 1;
            for (CafeteriaPivotRow rd : rows) {
                Row row = sh.createRow(rowIdx++);
                createCell(row, 0, rd.category(), null);
                createNumericCell(row, 1, rd.breakfast(), null);
                createNumericCell(row, 2, rd.lunch(), null);
                createNumericCell(row, 3, rd.dinner(), null);
                createNumericCell(row, 4, rd.total(), null);
            }

            // Grand total
            Row totalRow = sh.createRow(rowIdx);
            createCell(totalRow, 0, "Grand Total", headerStyle);
            if (!rows.isEmpty()) {
                int firstDataRow = 2; // header is row 1
                int lastDataRow = 1 + rows.size();
                createFormula(totalRow, 1, String.format("SUM(B%d:B%d)", firstDataRow, lastDataRow), headerStyle);
                createFormula(totalRow, 2, String.format("SUM(C%d:C%d)", firstDataRow, lastDataRow), headerStyle);
                createFormula(totalRow, 3, String.format("SUM(D%d:D%d)", firstDataRow, lastDataRow), headerStyle);
                createFormula(totalRow, 4, String.format("SUM(E%d:E%d)", firstDataRow, lastDataRow), headerStyle);
            } else {
                createNumericCell(totalRow, 1, 0, headerStyle);
                createNumericCell(totalRow, 2, 0, headerStyle);
                createNumericCell(totalRow, 3, 0, headerStyle);
                createNumericCell(totalRow, 4, 0, headerStyle);
            }

            // Autosize
            for (int c = 0; c <= 4; c++) sh.autoSizeColumn(c);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }
            log.info("Attendance report written to excel: {}", outFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("[CREATE ATTENDANCE REPORT]", e);
            throw new RuntimeException(e);
        }
        return outFile;
    }

    /**
     * Build an XLSX with a sheet per list: Status | Photo | ID | Name | Comment.
     * Downloads and embeds the first image for each ListItemDto (if present).
     */
    public File exportEvacuationWorkbook(Map<FaceListDto, List<EvacuationReportRow>> data, File outFile) {
        try (Workbook wb = new XSSFWorkbook()) {
            for (Map.Entry<FaceListDto, List<EvacuationReportRow>> e : data.entrySet()) {
                FaceListDto list = e.getKey();
                List<EvacuationReportRow> items = e.getValue();

                String sheetName = sanitizeSheetName(
                        StringUtils.hasText(list.getName()) ? list.getName() : "List_" + list.getId()
                );
                Sheet sh = wb.createSheet(sheetName);

                // Header: Status, Entrance time, Photo, ID, Name, Comment
                Row header = sh.createRow(0);
                header.createCell(0).setCellValue("Status");
                header.createCell(1).setCellValue("Entrance time");
                header.createCell(2).setCellValue("Photo");
                header.createCell(3).setCellValue("ID");
                header.createCell(4).setCellValue("Name");
                header.createCell(5).setCellValue("Comment");

                sh.setDefaultRowHeightInPoints(ROW_HEIGHT_PT);
                sh.setColumnWidth(0, COL_WIDTH_CHECKBOX);
                sh.setColumnWidth(1, COL_WIDTH_TIME);
                sh.setColumnWidth(2, COL_WIDTH_PHOTO);
                sh.setColumnWidth(3, COL_WIDTH_ID);
                sh.setColumnWidth(4, COL_WIDTH_NAME);
                sh.setColumnWidth(5, COL_WIDTH_COMMENT);
                header.setHeightInPoints(24f);

                Drawing<?> drawing = sh.createDrawingPatriarch();

                int r = 1;
                for (EvacuationReportRow rowData : items) {
                    ListItemDto item = rowData.item();
                    Row row = sh.createRow(r);

                    // Status checkbox (default checked)
                    Cell statusCell = row.createCell(0, CellType.BOOLEAN);
                    statusCell.setCellValue(true);
                    statusCell.setCellStyle(checkboxColumnStyle(wb));

                    // Entrance time
                    Cell entranceCell = row.createCell(1);
                    entranceCell.setCellValue(formatEntranceTime(rowData.entranceTime()));

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
                                anchor.setCol1(2);
                                anchor.setCol2(3);
                                Picture picture = drawing.createPicture(anchor, picIdx);
                                picture.resize(1.0, 1.0);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Photo embedding failed for list {} item {}: {}", list.getId(), item.getId(), ex.getMessage());
                    }

                    // ID
                    Cell idCell = row.createCell(3);
                    if (item.getId() != null) {
                        idCell.setCellValue(item.getId());
                    }

                    // Name
                    row.createCell(4).setCellValue(nullSafe(item.getName()));

                    // Comment
                    row.createCell(5).setCellValue(nullSafe(item.getComment()));

                    r++;
                }

                // Checkbox-style validation on Status column for data rows
                DataValidationHelper dvHelper = sh.getDataValidationHelper();
                DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(EVAC_CHECKBOX_OPTIONS);
                CellRangeAddressList addressList =
                        new CellRangeAddressList(1, Math.max(1, sh.getLastRowNum()), 0, 0);
                DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
                validation.setSuppressDropDownArrow(true);
                validation.setEmptyCellAllowed(true);
                validation.setShowErrorBox(true);
                sh.addValidationData(validation);
            }

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }
        } catch (IOException e) {
            log.error("[CREATE EVACUATION REPORT]", e);
            throw new RuntimeException(e);
        }
        return outFile;
    }

    // Helpers for cell creation, sheet naming and styling
    private static void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void createNumericCell(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void createFormula(Row row, int col, String formula, CellStyle style) {
        Cell cell = row.createCell(col, CellType.FORMULA);
        cell.setCellFormula(formula);
        if (style != null) cell.setCellStyle(style);
    }

    private static String sanitizeSheetName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[:\\\\/*?\\[\\]]", "_");
        return cleaned.length() <= 31 ? cleaned : cleaned.substring(0, 31);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static CellStyle checkboxColumnStyle(Workbook wb) {
        CellStyle cs = wb.createCellStyle();
        cs.setAlignment(HorizontalAlignment.CENTER);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);
        Font font = wb.createFont();
        font.setFontName("Segoe UI Symbol");
        font.setFontHeightInPoints((short) 14);
        cs.setFont(font);
        return cs;
    }

    private static String formatEntranceTime(Long entranceTime) {
        if (entranceTime == null) {
            return "";
        }
        return Instant.ofEpochMilli(entranceTime)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String firstImagePath(ListItemDto item) {
        try {
            if (item.getImages() != null && !item.getImages().isEmpty()) {
                var img = item.getImages().get(0);
                return img.getPath();
            }
        } catch (Exception ignored) {}
        return null;
    }
}

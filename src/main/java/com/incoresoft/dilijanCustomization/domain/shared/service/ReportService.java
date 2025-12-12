package com.incoresoft.dilijanCustomization.domain.shared.service;

import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {
    private static final float ROW_HEIGHT_PT = 150f;
    private static final int COL_WIDTH_CHECKBOX = 15 * 256;
    private static final int COL_WIDTH_PHOTO   = 30 * 256;
    private static final int COL_WIDTH_NAME    = 50 * 256;
    private static final int COL_WIDTH_COMMENT = 50 * 256;
    private static final String[] EVAC_OPTIONS = {"On site", "Evacuated"};
    private static final List<String> COLUMNS = List.of("Category", "Breakfast", "Lunch", "Dinner", "Total");

    private final FaceApiRepository repo;

    /**
     * Create a single-sheet XLSX with columns:
     * Category | Breakfast | Lunch | Dinner | Total
     */
    public File exportCafeteriaPivot(LocalDate date, String sheetName, List<CafeteriaPivotRow> rows, File outFile) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet(sheetName);

            // Simple styles
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Header
            int raw_index = 0;
            Row header = sh.createRow(raw_index++);
            header.setHeightInPoints(18f);
            int column_index = 0;
            while (column_index < COLUMNS.size()) {
                createCell(header, column_index, COLUMNS.get(column_index), headerStyle);
                column_index++;
            }

            // Body
            for (CafeteriaPivotRow rd : rows) {
                Row row = sh.createRow(raw_index++);
                createCell(row, 0, rd.category(), null);
                createNumericCell(row, 1, rd.breakfast(), null);
                createNumericCell(row, 2, rd.lunch(), null);
                createNumericCell(row, 3, rd.dinner(), null);
                createNumericCell(row, 4, rd.total(), null);
            }

            // Grand total
            Row totalRow = sh.createRow(raw_index++);
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
            } catch (IOException e) {
                log.error("[CREATE ATTENDANCE REPORT]", e);
                throw new RuntimeException(e);
            }
            log.info("Attendance report written to excel: {}", outFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("[CREATE ATTENDANCE REPORT]", e);
            throw new RuntimeException(e);
        }
        return outFile;
    }

    /**
     * Build an XLSX with a sheet per list: [drop-down, Photo, Name, Comment].
     * Downloads and embeds the first image for each ListItemDto (if present).
     */
    public File exportEvacuationWorkbook(Map<FaceListDto, List<ListItemDto>> data, File outFile) {
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

                    // A: drop-down default value + centering
                    Cell c0 = row.createCell(0);
                    c0.setCellValue("On site");
                    c0.setCellStyle(checkboxColumnStyle(wb));

                    // B: Photo
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

                    // C: Name, D: Comment
                    row.createCell(2).setCellValue(nullSafe(item.getName()));
                    row.createCell(3).setCellValue(nullSafe(item.getComment()));

                    r++;
                }

                // Drop-down on A column (data rows only)
                DataValidationHelper dvHelper = sh.getDataValidationHelper();
                DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(EVAC_OPTIONS);
                CellRangeAddressList addressList = new CellRangeAddressList(1, Math.max(1, sh.getLastRowNum()), 0, 0);
                DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
                validation.setSuppressDropDownArrow(false);
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

    // ===== Helpers =====

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
        return cs;
    }

    // NOTE: adjust if your ListItemDto image model differs
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

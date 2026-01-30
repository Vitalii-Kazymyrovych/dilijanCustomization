package com.incoresoft.dilijanCustomization.domain.shared.service;

import com.incoresoft.dilijanCustomization.domain.attendance.dto.CafeteriaPivotRow;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationReportRow;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListImage;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @Test
    void exportsCafeteriaPivotWithTotals() throws Exception {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService service = new ReportService(repo);
        File out = File.createTempFile("cafeteria-", ".xlsx");

        CafeteriaPivotRow row = new CafeteriaPivotRow("Alpha", 1, 2, 3);
        File result = service.exportCafeteriaPivot(LocalDate.of(2024, 12, 1), "Cafe", List.of(row), out);

        try (FileInputStream fis = new FileInputStream(result); XSSFWorkbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheet("Cafe");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Category");
            Row data = sheet.getRow(1);
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("Alpha");
            assertThat(data.getCell(4).getNumericCellValue()).isEqualTo(6);

            Row totals = sheet.getRow(2);
            assertThat(totals.getCell(0).getStringCellValue()).isEqualTo("Grand Total");
            assertThat(totals.getCell(4).getCellFormula()).contains("SUM(E2:E2)");
        }
    }

    @Test
    void exportsEvacuationWorkbookWithSanitizedSheetName() throws Exception {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        when(repo.downloadStorageObject(anyString())).thenReturn(new byte[0]);
        ReportService service = new ReportService(repo);

        FaceListDto list = new FaceListDto();
        list.setId(10L);
        list.setName("List:Unsafe*Name?");

        ListImage image = new ListImage();
        image.setPath("img/path");

        ListItemDto item = new ListItemDto();
        item.setId(5L);
        item.setName("John Doe");
        item.setComment("comment");
        item.setImages(List.of(image));

        File out = File.createTempFile("evac-", ".xlsx");
        long entranceTime = 1_701_000_000_000L;
        EvacuationReportRow row = new EvacuationReportRow(item, entranceTime);
        File result = service.exportEvacuationWorkbook(Map.of(list, List.of(row)), out);

        try (FileInputStream fis = new FileInputStream(result); XSSFWorkbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheet("List_Unsafe_Name_");
            assertThat(sheet).isNotNull();
            Row data = sheet.getRow(1);
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("☐");
            String expectedTime = Instant.ofEpochMilli(entranceTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo(expectedTime);
            assertThat(data.getCell(3).getNumericCellValue()).isEqualTo(5);
            assertThat(data.getCell(4).getStringCellValue()).isEqualTo("John Doe");
            assertThat(data.getCell(5).getStringCellValue()).isEqualTo("comment");
        }
    }
}

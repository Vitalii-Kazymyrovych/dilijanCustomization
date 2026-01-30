package com.incoresoft.dilijanCustomization.telegram;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramBotTest {

    @Test
    void extractUpdatesReadsIdFromCorrectColumn() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Status");
            header.createCell(1).setCellValue("Entrance time");
            header.createCell(2).setCellValue("Photo");
            header.createCell(3).setCellValue("ID");

            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("☐"); // status false
            row1.createCell(3).setCellValue(10);          // numeric ID

            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("☑");   // status true
            row2.createCell(3).setCellValue("11");        // string ID

            Map<String, Long> nameToId = Map.of("List_1", 99L);
            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId);

            assertThat(updates).containsExactly(
                    new TelegramBot.EvacuationUpdate(99L, 10L, false),
                    new TelegramBot.EvacuationUpdate(99L, 11L, true)
            );
        }
    }

    @Test
    void extractUpdatesSkipsUnknownSheetsAndRowsWithoutIds() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            sheet.createRow(0);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("☐");
            // no ID column set

            // Sheet not present in nameToId
            wb.createSheet("Other");

            Map<String, Long> nameToId = Map.of("List_1", 50L);
            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId);

            assertThat(updates).isEmpty();
        }
    }
}

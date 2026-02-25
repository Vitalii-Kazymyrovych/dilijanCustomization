package com.incoresoft.dilijanCustomization.telegram;

import com.incoresoft.dilijanCustomization.domain.attendance.service.AttendanceReportService;
import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationReportService;
import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationStatusService;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            row1.createCell(0).setCellValue(false); // status false
            row1.createCell(3).setCellValue(10);          // numeric ID

            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue(true);   // status true
            row2.createCell(3).setCellValue("11");        // string ID

            Map<String, Long> nameToId = Map.of("List_1", 99L);
            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId);

            assertThat(updates).containsExactly(
                    new TelegramBot.EvacuationUpdate(99L, 10L, false)
            );
        }
    }

    @Test
    void extractUpdatesSkipsUnknownSheetsAndRowsWithoutIds() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            sheet.createRow(0);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(false);
            // no ID column set

            // Sheet not present in nameToId
            wb.createSheet("Other");

            Map<String, Long> nameToId = Map.of("List_1", 50L);
            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId);

            assertThat(updates).isEmpty();
        }
    }

    @Test
    void extractUpdatesSkipsResolvedNameRowWhenStatusIsTrue() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Status");
            header.createCell(4).setCellValue("Name");

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(true);
            row.createCell(4).setCellValue("John Smith");

            Map<String, Long> nameToId = Map.of("List_1", 99L);
            Map<Long, Map<String, Long>> listItemMappings = Map.of(99L, Map.of("John Smith", 123L));

            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId, listItemMappings);

            assertThat(updates).isEmpty();
        }
    }

    @Test
    void extractUpdatesDoesNotResolveIdForNonExactNameMatch() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            sheet.createRow(0);

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(true);
            row.createCell(4).setCellValue("John Smith");

            Map<String, Long> nameToId = Map.of("List_1", 99L);
            Map<Long, Map<String, Long>> listItemMappings = Map.of(99L, Map.of("john smith", 123L));

            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId, listItemMappings);

            assertThat(updates).isEmpty();
        }
    }

    @Test
    void extractUpdatesResolvesIdByExactFullNameWhenIdIsMissingAndStatusIsFalse() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("List_1");
            sheet.createRow(0);

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(false);
            row.createCell(4).setCellValue("John Smith");

            Map<String, Long> nameToId = Map.of("List_1", 99L);
            Map<Long, Map<String, Long>> listItemMappings = Map.of(99L, Map.of("John Smith", 123L));

            List<TelegramBot.EvacuationUpdate> updates =
                    TelegramBot.extractUpdatesFromWorkbook(wb, nameToId, listItemMappings);

            assertThat(updates).containsExactly(
                    new TelegramBot.EvacuationUpdate(99L, 123L, false)
            );
        }
    }

    @Test
    void buildListItemNameMappingsStopsWhenPaginationDoesNotAdvance() throws Exception {
        FaceApiRepository repository = mock(FaceApiRepository.class);
        ListItemsResponse firstPage = new ListItemsResponse();
        List<ListItemDto> pageItems = new java.util.ArrayList<>();
        for (long id = 1; id <= 1000; id++) {
            ListItemDto item = new ListItemDto();
            item.setId(id);
            item.setName("Person " + id);
            pageItems.add(item);
        }
        firstPage.setData(pageItems);

        when(repository.getListItems(eq(99L), eq(""), eq(""), any(), eq(1000), eq("asc"), eq("name")))
                .thenReturn(firstPage);

        TelegramBot bot = new TelegramBot(repository, mock(EvacuationReportService.class),
                mock(AttendanceReportService.class), mock(EvacuationStatusService.class));

        Method method = TelegramBot.class.getDeclaredMethod("buildListItemNameMappings", Iterable.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Long, Map<String, Long>> mappings = (Map<Long, Map<String, Long>>) method.invoke(bot, List.of(99L));

        assertThat(mappings).containsKey(99L);
        assertThat(mappings.get(99L)).containsEntry("Person 1", 1L);
        verify(repository, times(2)).getListItems(eq(99L), eq(""), eq(""), any(), eq(1000), eq("asc"), eq("name"));
    }

}

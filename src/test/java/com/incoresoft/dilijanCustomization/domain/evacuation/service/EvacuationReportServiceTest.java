package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.service.ReportService;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvacuationReportServiceTest {

    @Test
    void buildsReportWithActiveStatusesOnly() throws Exception {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService reportService = mock(ReportService.class);
        EvacuationStatusService statusService = mock(EvacuationStatusService.class);
        doNothing().when(statusService).refreshStatuses();

        FaceListDto list1 = new FaceListDto();
        list1.setId(1L);
        list1.setName("List 1");
        FaceListDto list2 = new FaceListDto();
        list2.setId(2L);
        list2.setName("List 2");

        var listsResponse = new com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse();
        listsResponse.setData(List.of(list1, list2));
        when(repo.getFaceLists(100)).thenReturn(listsResponse);

        ListItemDto activeItem = new ListItemDto();
        activeItem.setId(10L);
        activeItem.setName("Active");

        ListItemsResponse itemsResponse = new ListItemsResponse();
        itemsResponse.setData(List.of(activeItem));
        when(repo.getListItems(eq(1L), anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(itemsResponse);
        when(repo.getListItems(eq(2L), anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(itemsResponse);

        when(statusService.getActiveListItemIds(1L)).thenReturn(Set.of(10L));
        when(statusService.getActiveListItemIds(2L)).thenReturn(Set.of());

        File exported = File.createTempFile("evac-report-", ".xlsx");
        ArgumentCaptor<Map<FaceListDto, List<ListItemDto>>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        when(reportService.exportEvacuationWorkbook(dataCaptor.capture(), any(File.class))).thenReturn(exported);

        EvacuationReportService service = new EvacuationReportService(repo, reportService, statusService);
        File result = service.buildEvacuationReport(List.of(2L, 1L));

        assertThat(result).isEqualTo(exported);
        Map<FaceListDto, List<ListItemDto>> data = dataCaptor.getValue();
        assertThat(data).hasSize(2);
        assertThat(data.get(list1)).extracting(ListItemDto::getId).containsExactly(10L);
        assertThat(data.get(list2)).isEmpty();
    }

    @Test
    void parsesPresenceCsvAndResolvesPresentNames() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService reportService = mock(ReportService.class);
        EvacuationStatusService statusService = mock(EvacuationStatusService.class);
        EvacuationReportService service = new EvacuationReportService(repo, reportService, statusService);

        String csv = "\uFEFFDate;Employee;Present\n01-09-2023;Alice;true\n02-09-2023;Alice;false\n01-09-2023;Bob;true\n";

        Map<String, List<?>> records = ReflectionTestUtils.invokeMethod(service, "parsePresenceCsv", csv);
        assertThat(records).containsKeys("alice", "bob");

        @SuppressWarnings("unchecked")
        Set<String> present = ReflectionTestUtils.invokeMethod(service, "resolvePresentNames", records);
        assertThat(present).containsExactly("bob");
    }

    @Test
    void parsesHumanReadableDates() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        ReportService reportService = mock(ReportService.class);
        EvacuationStatusService statusService = mock(EvacuationStatusService.class);
        EvacuationReportService service = new EvacuationReportService(repo, reportService, statusService);

        String raw = "01-09-2023 10:15";
        Long parsed = ReflectionTestUtils.invokeMethod(service, "parseHumanDateToMillis", raw);
        long expected = LocalDateTime.of(2023, 9, 1, 10, 15)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(parsed).isEqualTo(expected);

        Long fallback = ReflectionTestUtils.invokeMethod(service, "parseHumanDateToMillis", "2023-09-01");
        long fallbackExpected = LocalDate.of(2023, 9, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(fallback).isEqualTo(fallbackExpected);

        Long invalid = ReflectionTestUtils.invokeMethod(service, "parseHumanDateToMillis", "bad-date");
        assertThat(invalid).isNull();
    }
}

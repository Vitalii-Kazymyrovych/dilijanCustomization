package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.dto.TimeAttendance;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EvacuationStatusServiceTest {

    @Test
    void getActiveListItemIdsReturnsEmptyOnError() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        when(statusRepo.findByListIdAndStatusTrue(99L)).thenThrow(new RuntimeException("db down"));

        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);
        Set<Long> result = service.getActiveListItemIds(99L);

        assertThat(result).isEmpty();
    }

    @Test
    void updateStatusInsertsWhenMissing() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        when(statusRepo.existsById(new EvacuationStatusPK(1L, 2L))).thenReturn(false);

        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);
        service.updateStatus(1L, 2L, true);

        ArgumentCaptor<EvacuationStatus> captor = ArgumentCaptor.forClass(EvacuationStatus.class);
        verify(statusRepo).save(captor.capture());
        EvacuationStatus saved = captor.getValue();
        assertThat(saved.getListId()).isEqualTo(1L);
        assertThat(saved.getListItemId()).isEqualTo(2L);
        assertThat(saved.getStatus()).isTrue();
        assertThat(saved.getEntranceTime()).isNotNull();
        assertThat(saved.getExitTime()).isNull();
        assertThat(saved.getManuallyUpdated()).isTrue();
    }

    @Test
    void fetchListItemsPaginatesThroughAllPages() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);

        ListItemsResponse firstPage = new ListItemsResponse();
        firstPage.setTotal(1200);
        firstPage.setData(buildListItems(1000, 1));

        ListItemsResponse secondPage = new ListItemsResponse();
        secondPage.setTotal(1200);
        secondPage.setData(buildListItems(200, 1001));

        when(repo.getListItems(eq(1L), anyString(), anyString(), eq(0), eq(1000), anyString(), anyString()))
                .thenReturn(firstPage);
        when(repo.getListItems(eq(1L), anyString(), anyString(), eq(1000), eq(1000), anyString(), anyString()))
                .thenReturn(secondPage);

        @SuppressWarnings("unchecked")
        List<ListItemDto> items = ReflectionTestUtils.invokeMethod(service, "fetchListItems", 1L);

        assertThat(items).hasSize(1200);
        assertThat(items.getFirst().getId()).isEqualTo(1L);
        assertThat(items.getLast().getId()).isEqualTo(1200L);
    }

    @Test
    void manualStatusSkipsRefreshWhenNoNewDetection() {
        FaceApiRepository repo = mock(FaceApiRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);

        FaceListDto list = new FaceListDto();
        list.setId(1L);
        list.setTimeAttendance(new TimeAttendance(true, List.of(1L), List.of(2L)));

        ListItemDto item = new ListItemDto();
        item.setId(5L);
        ListItemsResponse listItemsResponse = new ListItemsResponse();
        listItemsResponse.setData(List.of(item));
        listItemsResponse.setTotal(1);
        when(repo.getListItems(eq(1L), anyString(), anyString(), eq(0), eq(1000), anyString(), anyString()))
                .thenReturn(listItemsResponse);

        DetectionDto detection = new DetectionDto();
        detection.setTimestamp(123L);
        DetectionDto.AnalyticsRef analytics = new DetectionDto.AnalyticsRef();
        analytics.setId(1L);
        detection.setAnalytics(analytics);
        ListItemDto detectedItem = new ListItemDto();
        detectedItem.setId(5L);
        detection.setListItem(detectedItem);
        when(repo.getAllDetectionsInWindow(eq(1L), anyList(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(detection));

        EvacuationStatus existing = new EvacuationStatus();
        existing.setListId(1L);
        existing.setListItemId(5L);
        existing.setStatus(false);
        existing.setEntranceTime(123L);
        existing.setManuallyUpdated(true);
        when(statusRepo.findByListId(1L)).thenReturn(List.of(existing));

        ReflectionTestUtils.invokeMethod(service, "updateListStatuses", list, 0L, 200L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvacuationStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(statusRepo).saveAll(captor.capture());
        EvacuationStatus saved = captor.getValue().getFirst();
        assertThat(saved.getStatus()).isFalse();
        assertThat(saved.getManuallyUpdated()).isTrue();
        assertThat(saved.getEntranceTime()).isEqualTo(123L);
    }

    private static List<ListItemDto> buildListItems(int count, long startingId) {
        List<ListItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ListItemDto dto = new ListItemDto();
            dto.setId(startingId + i);
            dto.setName("Person " + dto.getId());
            items.add(dto);
        }
        return items;
    }
}

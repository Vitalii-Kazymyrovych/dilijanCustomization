package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.TimeAttendance;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.VezhaDbRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvacuationStatusServiceTest {

    @Test
    void getActiveListItemIdsReturnsEmptyOnError() {
        VezhaDbRepository repo = mock(VezhaDbRepository.class);
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
        VezhaDbRepository repo = mock(VezhaDbRepository.class);
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
        assertThat(saved.getEntranceTime()).isNull();
        assertThat(saved.getExitTime()).isNull();
        assertThat(saved.getManuallyUpdated()).isTrue();
    }


    @Test
    void manualUpdateKeepsExistingDetectionTimes() {
        VezhaDbRepository repo = mock(VezhaDbRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);

        EvacuationStatus existing = new EvacuationStatus();
        existing.setListId(1L);
        existing.setListItemId(2L);
        existing.setStatus(false);
        existing.setEntranceTime(100L);
        existing.setExitTime(200L);
        when(statusRepo.findById(new EvacuationStatusPK(1L, 2L))).thenReturn(java.util.Optional.of(existing));
        when(statusRepo.existsById(new EvacuationStatusPK(1L, 2L))).thenReturn(true);

        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);
        service.updateStatus(1L, 2L, true);

        verify(statusRepo).updateStatus(1L, 2L, true, 100L, 200L, true);
    }

    @Test
    void fetchListItemsReadsFromDbRepository() {
        VezhaDbRepository repo = mock(VezhaDbRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);

        ListItemDto first = new ListItemDto();
        first.setId(1L);
        ListItemDto second = new ListItemDto();
        second.setId(2L);
        when(repo.findListItems(1L)).thenReturn(List.of(first, second));

        @SuppressWarnings("unchecked")
        List<ListItemDto> items = ReflectionTestUtils.invokeMethod(service, "fetchListItems", 1L);

        assertThat(items).extracting(ListItemDto::getId).containsExactly(1L, 2L);
    }

    @Test
    void manualStatusSkipsRefreshWhenNoNewDetection() {
        VezhaDbRepository repo = mock(VezhaDbRepository.class);
        EvacuationProps props = new EvacuationProps();
        PostgresProps postgresProps = new PostgresProps();
        EvacuationStatusRepository statusRepo = mock(EvacuationStatusRepository.class);
        EvacuationStatusService service = new EvacuationStatusService(repo, props, postgresProps, statusRepo);

        FaceListDto list = new FaceListDto();
        list.setId(1L);
        list.setTimeAttendance(new TimeAttendance(true, List.of(1L), List.of(2L)));

        ListItemDto item = new ListItemDto();
        item.setId(5L);
        when(repo.findListItems(1L)).thenReturn(List.of(item));

        DetectionDto detection = new DetectionDto();
        detection.setTimestamp(123L);
        DetectionDto.AnalyticsRef analytics = new DetectionDto.AnalyticsRef();
        analytics.setId(1L);
        detection.setAnalytics(analytics);
        ListItemDto detectedItem = new ListItemDto();
        detectedItem.setId(5L);
        detection.setListItem(detectedItem);
        when(repo.findLatestDetectionsByListItem(eq(1L), anyList(), anyLong(), anyLong()))
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
}

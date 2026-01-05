package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

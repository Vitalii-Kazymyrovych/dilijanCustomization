package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    }
}

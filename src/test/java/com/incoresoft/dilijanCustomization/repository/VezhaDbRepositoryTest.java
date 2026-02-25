package com.incoresoft.dilijanCustomization.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incoresoft.dilijanCustomization.config.VezhaDbProps;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VezhaDbRepositoryTest {

    @Test
    void usesEventTimestampColumnWhenAvailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VezhaDbProps props = new VezhaDbProps();
        props.setEnabled(true);
        VezhaDbRepository repository = new VezhaDbRepository(jdbcTemplate, props, new ObjectMapper());

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("videoanalytics"))).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(new DetectionDto()));

        repository.findLatestDetectionsByListItem(1L, List.of(10L), null, null);

        verify(jdbcTemplate).queryForObject(anyString(), eq(Boolean.class), eq("videoanalytics"));
        verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.contains("fd.timestamp AS event_timestamp"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void fallsBackToCreatedAtWhenTimestampColumnMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VezhaDbProps props = new VezhaDbProps();
        props.setEnabled(true);
        VezhaDbRepository repository = new VezhaDbRepository(jdbcTemplate, props, new ObjectMapper());

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("videoanalytics"))).thenReturn(false);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        List<DetectionDto> result = repository.findLatestDetectionsByListItem(1L, List.of(10L), null, null);

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.contains("fd.created_at"), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Boolean.class), eq("videoanalytics"));
    }
}

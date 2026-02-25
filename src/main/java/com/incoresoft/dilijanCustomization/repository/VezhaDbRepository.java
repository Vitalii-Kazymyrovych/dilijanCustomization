package com.incoresoft.dilijanCustomization.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incoresoft.dilijanCustomization.config.VezhaDbProps;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListImage;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.TimeAttendance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class VezhaDbRepository {
    private final JdbcTemplate jdbcTemplate;
    private final VezhaDbProps vezhaDbProps;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Boolean> hasDetectionTimestampColumn = new AtomicReference<>();

    public VezhaDbRepository(@Qualifier("vezhaJdbcTemplate") JdbcTemplate jdbcTemplate,
                             VezhaDbProps vezhaDbProps,
                             ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.vezhaDbProps = vezhaDbProps;
        this.objectMapper = objectMapper;
    }

    public List<FaceListDto> findListsWithAttendanceEnabled() {
        if (!vezhaDbProps.isEnabled()) {
            return List.of();
        }
        String sql = "SELECT id, name, comment, status, time_attendance FROM " + schema() + ".face_lists ORDER BY id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            FaceListDto dto = new FaceListDto();
            dto.setId(rs.getLong("id"));
            dto.setName(rs.getString("name"));
            dto.setComment(rs.getString("comment"));
            dto.setStatus((Integer) rs.getObject("status"));
            dto.setTimeAttendance(parseTimeAttendance(rs.getString("time_attendance")));
            return dto;
        }).stream().filter(it -> it.getTimeAttendance() != null && Boolean.TRUE.equals(it.getTimeAttendance().getEnabled())).toList();
    }

    public FaceListDto findFaceList(Long listId) {
        if (!vezhaDbProps.isEnabled()) {
            return null;
        }
        String sql = "SELECT id, name, comment, status, time_attendance FROM " + schema() + ".face_lists WHERE id = ?";
        List<FaceListDto> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            FaceListDto dto = new FaceListDto();
            dto.setId(rs.getLong("id"));
            dto.setName(rs.getString("name"));
            dto.setComment(rs.getString("comment"));
            dto.setStatus((Integer) rs.getObject("status"));
            dto.setTimeAttendance(parseTimeAttendance(rs.getString("time_attendance")));
            return dto;
        }, listId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ListItemDto> findListItems(Long listId) {
        if (!vezhaDbProps.isEnabled()) {
            return List.of();
        }
        String sql = "SELECT i.id AS item_id, i.list_id, i.name, i.comment, img.path " +
                "FROM " + schema() + ".face_list_items i " +
                "LEFT JOIN " + schema() + ".face_list_items_images img ON img.list_item_id = i.id " +
                "WHERE i.list_id = ? ORDER BY i.name ASC, i.id ASC, img.id ASC";
        Map<Long, ListItemDto> items = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Long itemId = rs.getLong("item_id");
            ListItemDto item = items.computeIfAbsent(itemId, id -> {
                ListItemDto created = new ListItemDto();
                created.setId(id);
                created.setListId(rsLong(rs, "list_id"));
                created.setName(rsString(rs, "name"));
                created.setComment(rsString(rs, "comment"));
                created.setImages(new ArrayList<>());
                return created;
            });
            String path = rsString(rs, "path");
            if (path != null && !path.isBlank()) {
                ListImage image = new ListImage();
                image.setPath(path);
                item.getImages().add(image);
            }
        }, listId);
        return new ArrayList<>(items.values());
    }

    public List<DetectionDto> findLatestDetectionsByListItem(Long listId, List<Long> analyticsIds, Long startMillis, Long endMillis) {
        if (!vezhaDbProps.isEnabled() || analyticsIds == null || analyticsIds.isEmpty()) {
            return List.of();
        }
        String placeholders = analyticsIds.stream().map(x -> "?").collect(Collectors.joining(","));
        boolean useEventTimestamp = hasFaceDetectionsTimestampColumn();
        String sql = useEventTimestamp
                ? "SELECT DISTINCT ON (fd.list_item_id) fd.list_item_id, fd.analytics_id, fd.timestamp AS event_timestamp " +
                "FROM " + schema() + ".face_detections fd " +
                "WHERE fd.list_id = ? AND fd.list_item_id IS NOT NULL " +
                "AND fd.analytics_id IN (" + placeholders + ") " +
                "AND (? IS NULL OR fd.timestamp >= ?) " +
                "AND (? IS NULL OR fd.timestamp <= ?) " +
                "ORDER BY fd.list_item_id, fd.timestamp DESC, fd.id DESC"
                : "SELECT DISTINCT ON (fd.list_item_id) fd.list_item_id, fd.analytics_id, fd.created_at " +
                "FROM " + schema() + ".face_detections fd " +
                "WHERE fd.list_id = ? AND fd.list_item_id IS NOT NULL " +
                "AND fd.analytics_id IN (" + placeholders + ") " +
                "AND (? IS NULL OR fd.created_at >= to_timestamp(? / 1000.0)) " +
                "AND (? IS NULL OR fd.created_at <= to_timestamp(? / 1000.0)) " +
                "ORDER BY fd.list_item_id, fd.created_at DESC, fd.id DESC";
        List<Object> params = new ArrayList<>();
        params.add(listId);
        params.addAll(analyticsIds);
        params.add(startMillis);
        params.add(startMillis);
        params.add(endMillis);
        params.add(endMillis);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            DetectionDto d = new DetectionDto();
            ListItemDto item = new ListItemDto();
            item.setId(rsLong(rs, "list_item_id"));
            d.setListItem(item);

            DetectionDto.AnalyticsRef analytics = new DetectionDto.AnalyticsRef();
            analytics.setId(rsLong(rs, "analytics_id"));
            d.setAnalytics(analytics);

            if (useEventTimestamp) {
                d.setTimestamp(rsLong(rs, "event_timestamp"));
            } else {
                Timestamp created = rs.getTimestamp("created_at");
                if (created != null) {
                    d.setTimestamp(created.toInstant().atZone(ZoneOffset.UTC).toInstant().toEpochMilli());
                }
            }
            return d;
        }, params.toArray());
    }

    private boolean hasFaceDetectionsTimestampColumn() {
        Boolean cached = hasDetectionTimestampColumn.get();
        if (cached != null) {
            return cached;
        }
        String sql = "SELECT EXISTS (" +
                "SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = 'face_detections' AND column_name = 'timestamp')";
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, schema()));
        hasDetectionTimestampColumn.compareAndSet(null, exists);
        return hasDetectionTimestampColumn.get();
    }

    private TimeAttendance parseTimeAttendance(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TimeAttendance.class);
        } catch (Exception ex) {
            log.warn("[VEZHA-DB] Failed to parse time_attendance JSON: {}", ex.getMessage());
            return null;
        }
    }

    private String schema() {
        return (vezhaDbProps.getSchema() == null || vezhaDbProps.getSchema().isBlank()) ? "videoanalytics" : vezhaDbProps.getSchema();
    }

    private static Long rsLong(java.sql.ResultSet rs, String c) {
        try {
            long v = rs.getLong(c);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String rsString(java.sql.ResultSet rs, String c) {
        try {
            return rs.getString(c);
        } catch (Exception e) {
            return null;
        }
    }
}

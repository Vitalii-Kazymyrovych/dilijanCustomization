package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Вычисляет и сохраняет статусы эвакуации в таблицу PostgreSQL.
 */
@ConditionalOnProperty(prefix = "evacuation", name = "enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class EvacuationStatusService {
    private static final int DEFAULT_FACE_LIST_LIMIT = 100;
    private static final int DEFAULT_DETECTION_PAGE_LIMIT = 500;
    private static final int LIST_ITEM_PAGE_LIMIT = 1000;

    private final FaceApiRepository repo;
    private final EvacuationProps evacuationProps;
    private final PostgresProps postgresProps;
    /** JPA репозиторий для сохранения и обновления статусов. */
    private final EvacuationStatusRepository evacuationStatusRepository;

    @PostConstruct
    public void init() {
        // При использовании Spring Data JPA таблица создаётся автоматически,
        // поэтому просто рассчитываем статусы при старте.
        if (!evacuationProps.isAutostart()) {
            log.info("[EVAC] Autostart disabled; skipping init");
            return;
        }
        try {
            initializeDatabaseAndTable();
        } catch (Exception e) {
            log.warn("Exception was thrown while db initialization: {}", e.getMessage(), e);
        }
        refreshStatuses();
    }

    /** Периодически обновляет статусы. Период задаётся в конфигурации. */
    @Scheduled(fixedDelayString = "${evacuation.refreshMinutes:5}", timeUnit = TimeUnit.MINUTES)
    public synchronized void refreshStatuses() {
        List<FaceListDto> evacuationLists = fetchListsWithAttendanceEnabled();
        if (evacuationLists.isEmpty()) {
            log.info("[EVAC] No lists with attendance enabled; skipping refresh");
            return;
        }
        long now = System.currentTimeMillis();
        Long start = resolveStartMillis(now);
        log.info("[EVAC] Refresh started");
        for (FaceListDto list : evacuationLists) {
            try {
                updateListStatuses(list, start, now);
            } catch (Exception ex) {
                log.error("Failed to refresh list {}: {}", list.getId(), ex.getMessage(), ex);
            }
        }
        log.info("[EVAC] Refresh finished");
    }

    /**
     * Возвращает ID пунктов списка со статусом true.
     * Используется JPA репозиторий вместо psql-вызова.
     */
    public Set<Long> getActiveListItemIds(Long listId) {
        try {
            return evacuationStatusRepository.findByListIdAndStatusTrue(listId)
                    .stream()
                    .map(EvacuationStatus::getListItemId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.error("Query failed: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    /**
     * Retrieve full active status rows (including entrance timestamps) for a list.
     * @param listId identifier of the face list
     * @return map keyed by list_item_id with the corresponding status row
     */
    public Map<Long, EvacuationStatus> getActiveStatuses(Long listId) {
        try {
            return evacuationStatusRepository.findByListIdAndStatusTrue(listId)
                    .stream()
                    .collect(Collectors.toMap(EvacuationStatus::getListItemId, it -> it, (a, b) -> a, LinkedHashMap::new));
        } catch (Exception e) {
            log.error("Query failed: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    // --- внутренние методы ---

    private void updateListStatuses(FaceListDto faceList, Long startMillis, Long endMillis) throws Exception {
        TimeAttendanceConfig attendanceConfig = TimeAttendanceConfig.from(faceList);
        if (!attendanceConfig.enabled()) {
            log.debug("[EVAC] Skip list {}: attendance disabled or missing", faceList.getId());
            return;
        }

        List<DetectionDto> detections = repo.getAllDetectionsInWindow(
                faceList.getId(),
                attendanceConfig.allStreams(),
                startMillis,
                endMillis,
                DEFAULT_DETECTION_PAGE_LIMIT
        );

        Map<Long, DetectionDto> latestByPerson = findLatestDetections(detections);
        List<ListItemDto> listItems = fetchListItems(faceList.getId());
        if (listItems.isEmpty()) {
            return;
        }

        List<EvacuationStatus> statuses = buildStatuses(faceList, attendanceConfig, latestByPerson, listItems);
        evacuationStatusRepository.saveAll(statuses);
    }

    /** Обновление статуса одного пользователя в списке. */
    @Transactional
    public void updateStatus(Long listId, Long listItemId, boolean status) {
        updateStatus(listId, listItemId, status, status ? System.currentTimeMillis() : null);
    }

    /**
     * Update status and entrance time for a single list item. If the record does not yet exist,
     * it will be inserted.
     */
    public void updateStatus(Long listId, Long listItemId, boolean status, Long entranceTime) {
        try {
            evacuationStatusRepository.updateStatus(listId, listItemId, status, entranceTime);
            // если записи нет, сохранить новую
            if (!evacuationStatusRepository.existsById(new EvacuationStatusPK(listId, listItemId))) {
                EvacuationStatus es = new EvacuationStatus();
                es.setListId(listId);
                es.setListItemId(listItemId);
                es.setStatus(status);
                es.setEntranceTime(entranceTime);
                evacuationStatusRepository.save(es);
            }
        } catch (Exception ex) {
            log.error("Failed to update status for listId {} and listItemId {}: {}",
                    listId, listItemId, ex.getMessage(), ex);
        }
    }

    private void initializeDatabaseAndTable() throws Exception {
        String dbName = postgresProps.getDatabase();
        if (dbName == null || dbName.isBlank()) {
            log.warn("No postgres.database configured");
            return;
        }
        boolean exists = false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(postgresProps.getPsqlPath());
            cmd.add("-U");
            cmd.add(postgresProps.getSuperuser());
            cmd.add("-d");
            cmd.add(dbName);
            cmd.add("-c");
            cmd.add("SELECT 1");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PGPASSWORD", postgresProps.getSuperpass());
            Process p = pb.start();
            exists = p.waitFor() == 0;
        } catch (Exception ignored) {}
        if (!exists) {
            runPsql("postgres", "CREATE DATABASE \"" + dbName + "\"");
        }
        String createTable = String.join("",
                "CREATE TABLE IF NOT EXISTS evacuation (",
                "list_id INT, ",
                "list_item_id BIGINT, ",
                "enter_stream_ids INT[], ",
                "exit_stream_ids INT[], ",
                "status BOOLEAN, ",
                "entrance_time BIGINT, ",
                "PRIMARY KEY (list_id, list_item_id)",
                ")");
        runPsql(dbName, createTable);
        runPsql(dbName, "ALTER TABLE IF EXISTS evacuation ADD COLUMN IF NOT EXISTS entrance_time BIGINT");
    }

    private void runPsql(String db, String sql) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(postgresProps.getPsqlPath());
        cmd.add("-U");
        cmd.add(postgresProps.getSuperuser());
        if (db != null && !db.isBlank()) {
            cmd.add("-d");
            cmd.add(db);
        }
        cmd.add("-c");
        cmd.add(sql);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", postgresProps.getSuperpass());
        Process p = pb.start();
        p.getErrorStream().readAllBytes();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    private List<FaceListDto> fetchListsWithAttendanceEnabled() {
        try {
            FaceListsResponse response = repo.getFaceLists(DEFAULT_FACE_LIST_LIMIT);
            if (response == null || response.getData() == null) {
                return List.of();
            }
            return response.getData().stream()
                    .filter(list -> list.getTimeAttendance() != null)
                    .filter(list -> Boolean.TRUE.equals(list.getTimeAttendance().getEnabled()))
                    .toList();
        } catch (Exception e) {
            log.warn("[EVAC] VEZHA API unavailable, skipping refresh: {}", e.getMessage());
            return List.of();
        }
    }

    private Long resolveStartMillis(long now) {
        return evacuationProps.getLookbackDays() > 0
                ? now - Duration.ofDays(evacuationProps.getLookbackDays()).toMillis()
                : null;
    }

    private List<ListItemDto> fetchListItems(Long listId) {
        var response = repo.getListItems(listId, "", "", 0, LIST_ITEM_PAGE_LIMIT, "asc", "name");
        return response != null && response.getData() != null ? response.getData() : List.of();
    }

    private Map<Long, DetectionDto> findLatestDetections(List<DetectionDto> detections) {
        if (detections == null || detections.isEmpty()) {
            return Map.of();
        }
        Map<Long, DetectionDto> latest = new HashMap<>();
        for (DetectionDto detection : detections) {
            if (detection == null || detection.getListItem() == null || detection.getListItem().getId() == null) {
                continue;
            }
            Long listItemId = detection.getListItem().getId();
            DetectionDto previous = latest.get(listItemId);
            if (previous == null || isLater(detection, previous)) {
                latest.put(listItemId, detection);
            }
        }
        return latest;
    }

    private boolean isLater(DetectionDto candidate, DetectionDto existing) {
        return candidate.getTimestamp() != null
                && (existing.getTimestamp() == null || candidate.getTimestamp() > existing.getTimestamp());
    }

    private List<EvacuationStatus> buildStatuses(FaceListDto faceList,
                                                 TimeAttendanceConfig attendanceConfig,
                                                 Map<Long, DetectionDto> latestByPerson,
                                                 List<ListItemDto> listItems) {
        List<EvacuationStatus> statuses = new ArrayList<>();
        for (ListItemDto item : listItems) {
            DetectionDto detection = latestByPerson.get(item.getId());
            boolean status = detection != null && isEntranceDetection(detection, attendanceConfig.entrance());

            EvacuationStatus evacuationStatus = new EvacuationStatus();
            evacuationStatus.setListId(faceList.getId());
            evacuationStatus.setListItemId(item.getId());
            evacuationStatus.setEnterStreamIds(attendanceConfig.entranceArray());
            evacuationStatus.setExitStreamIds(attendanceConfig.exitArray());
            evacuationStatus.setStatus(status);
            evacuationStatus.setEntranceTime(status && detection != null ? detection.getTimestamp() : null);
            statuses.add(evacuationStatus);
        }
        return statuses;
    }

    private boolean isEntranceDetection(DetectionDto detection, List<Long> entranceStreams) {
        if (entranceStreams.isEmpty() || detection.getAnalytics() == null || detection.getAnalytics().getStreamId() == null) {
            return false;
        }
        return entranceStreams.contains(detection.getAnalytics().getStreamId());
    }

    private record TimeAttendanceConfig(boolean enabled, List<Long> entrance, List<Long> exit) {
        static TimeAttendanceConfig from(FaceListDto faceList) {
            if (faceList.getTimeAttendance() == null || !Boolean.TRUE.equals(faceList.getTimeAttendance().getEnabled())) {
                return new TimeAttendanceConfig(false, List.of(), List.of());
            }
            List<Long> entrance = defaultList(faceList.getTimeAttendance().getEntranceAnalyticsIds());
            List<Long> exit = defaultList(faceList.getTimeAttendance().getExitAnalyticsIds());
            return new TimeAttendanceConfig(true, entrance, exit);
        }

        Long[] entranceArray() {
            return entrance.isEmpty() ? null : entrance.toArray(Long[]::new);
        }

        Long[] exitArray() {
            return exit.isEmpty() ? null : exit.toArray(Long[]::new);
        }

        List<Long> allStreams() {
            List<Long> combined = new ArrayList<>(entrance);
            combined.addAll(exit);
            return combined;
        }

        private static List<Long> defaultList(List<Long> source) {
            return source == null ? List.of() : source;
        }
    }
}

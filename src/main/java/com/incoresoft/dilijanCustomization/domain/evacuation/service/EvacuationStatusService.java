package com.incoresoft.dilijanCustomization.domain.evacuation.service;

import com.incoresoft.dilijanCustomization.config.EvacuationProps;
import com.incoresoft.dilijanCustomization.config.PostgresProps;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.repository.EvacuationStatusRepository;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringEscapeUtils.escapeCsv;

/**
 * Вычисляет и сохраняет статусы эвакуации в таблицу PostgreSQL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvacuationStatusService {
    private final FaceApiRepository repo;
    private final EvacuationProps evacuationProps;
    private final PostgresProps postgresProps;
    /** JPA репозиторий для сохранения и обновления статусов. */
    private final EvacuationStatusRepository evacuationStatusRepository;

    @PostConstruct
    public void init() {
        // При использовании Spring Data JPA таблица создаётся автоматически,
        // поэтому просто рассчитываем статусы при старте.
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
        List<FaceListDto> evacuationLists = repo.getFaceLists(100).getData()
                .stream()
                .filter(l -> l.getTimeAttendance().getEnabled())
                .toList();
        if (evacuationLists.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long start = evacuationProps.getLookbackDays() > 0
                ? now - Duration.ofDays(evacuationProps.getLookbackDays()).toMillis()
                : null;
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

    // --- внутренние методы ---

    private void updateListStatuses(FaceListDto faceList, Long startMillis, Long endMillis) throws Exception {
        // получаем конфиг списка: входные и выходные камеры
        List<Long> entrance = faceList.getTimeAttendance().getEntranceAnalyticsIds();
        List<Long> exit = faceList.getTimeAttendance().getExitAnalyticsIds();
        List<Long> allStreams = new ArrayList<>();
        if (entrance != null) allStreams.addAll(entrance);
        if (exit != null) allStreams.addAll(exit);

        // Загружаем все детекции за период
        List<DetectionDto> dets = repo.getAllDetectionsInWindow(faceList.getId(), allStreams, startMillis, endMillis, 500);

        // Последняя детекция для каждого list_item_id
        Map<Long, DetectionDto> latest = new HashMap<>();
        for (DetectionDto d : dets) {
            if (d.getListItem() == null || d.getListItem().getId() == null) continue;
            Long liId = d.getListItem().getId();
            DetectionDto prev = latest.get(liId);
            if (prev == null || (prev.getTimestamp() != null && d.getTimestamp() != null && d.getTimestamp() > prev.getTimestamp())) {
                latest.put(liId, d);
            }
        }

        // Загружаем все элементы списка
        List<ListItemDto> items = repo.getListItems(faceList.getId(), "", "", 0, 1000, "asc", "name").getData();
        if (items == null || items.isEmpty()) return;

        // Составляем сущности для сохранения/обновления
        List<EvacuationStatus> toSave = new ArrayList<>();
        for (ListItemDto item : items) {
            boolean status = false;
            DetectionDto det = latest.get(item.getId());
            if (det != null && det.getAnalytics() != null && det.getAnalytics().getStreamId() != null && entrance != null) {
                Long sid = det.getAnalytics().getStreamId();
                status = entrance.contains(sid);
            }
            EvacuationStatus es = new EvacuationStatus();
            es.setListId(faceList.getId());
            es.setListItemId(item.getId());
            es.setEnterStreamIds(entrance != null ? entrance.toArray(Long[]::new) : null);
            es.setExitStreamIds(exit != null ? exit.toArray(Long[]::new) : null);
            es.setStatus(status);
            toSave.add(es);
        }

        // Сохраняем всё батчем — JPA выполнит upsert по составному ключу.
        evacuationStatusRepository.saveAll(toSave);
    }

    /** Обновление статуса одного пользователя в списке. */
    @Transactional
    public void updateStatus(Long listId, Long listItemId, boolean status) {
        try {
            evacuationStatusRepository.updateStatus(listId, listItemId, status);
            // если записи нет, сохранить новую
            if (!evacuationStatusRepository.existsById(new EvacuationStatusPK(listId, listItemId))) {
                EvacuationStatus es = new EvacuationStatus();
                es.setListId(listId);
                es.setListItemId(listItemId);
                es.setStatus(status);
                evacuationStatusRepository.save(es);
            }
        } catch (Exception ex) {
            log.error("Failed to update status for listId {} and listItemId {}: {}",
                    listId, listItemId, ex.getMessage(), ex);
        }
    }

    // --- старые вспомогательные psql-методы остаются неизменными (не используются JPA) ---
    private String toPgArrayLiteral(List<Long> list) {
        if (list == null || list.isEmpty()) return "'{}'::int[]";
        String joined = list.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "'{" + joined + "}'::int[]";
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
                "PRIMARY KEY (list_id, list_item_id)",
                ")");
        runPsql(dbName, createTable);
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

    private String queryPsql(String db, String sql) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(postgresProps.getPsqlPath());
        cmd.add("-U");
        cmd.add(postgresProps.getSuperuser());
        if (db != null && !db.isBlank()) {
            cmd.add("-d");
            cmd.add(db);
        }
        cmd.add("-t");
        cmd.add("-A");
        cmd.add("-c");
        cmd.add(sql);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", postgresProps.getSuperpass());
        Process p = pb.start();
        byte[] outBytes = p.getInputStream().readAllBytes();
        p.getErrorStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
        return new String(outBytes, StandardCharsets.UTF_8).trim();
    }
}

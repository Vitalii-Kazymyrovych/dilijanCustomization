package com.incoresoft.dilijanCustomization.domain.unknown.service;

import com.incoresoft.dilijanCustomization.config.UnknownListRegistry;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.FaceEventDto;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.FromDetectionRequest;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnknownPersonService {

    private static final int RECENT_DETECTION_LIMIT = 100;
    private static final int DETECTION_WINDOW_MILLIS = 2000;
    private static final int CLEAN_PAGE_LIMIT = 500;
    private static final String UNKNOWN_PREFIX = "unknown";
    private static final String UNKNOWN_COMMENT = "auto-unknown";

    private final FaceApiRepository repo;
    private final UnknownListRegistry unknownListRegistry;

    // ADD flow
    public Optional<ListItemDto> handleEventAddIfUnknown(FaceEventDto event) {
        if (event.isInList()) {
            log.info("[ADD] Skip: already in a list (list_id={})", event.getFace().getListItem().getList().getId());
            return Optional.empty();
        }

        List<DetectionDto> candidates = fetchCandidateDetections(event);
        DetectionDto matchingDetection = findMatchingDetection(event, candidates);

        if (!isUniqueDetection(matchingDetection)) {
            log.info("[ADD] Skip: similar face exists in other lists (face_image={})", matchingDetection.getFaceImage());
            return Optional.empty();
        }

        String name = nextUnknownName();
        log.info("[ADD] unique name={}", name);

        ListItemDto item = repo.createListItemFromDetection(
                new FromDetectionRequest(unknownListRegistry.get(), matchingDetection.getId(), name, UNKNOWN_COMMENT));
        log.info("[ADD] created list_item id={}", item.getId());

        return Optional.ofNullable(item);
    }

    // REMOVE flow
    public boolean handleEventRemoveIfUnknown(FaceEventDto event) {
        if (!isUnknownListEvent(event)) {
            log.info("[REMOVE] Skip: not in unknown list or list_item_id missing");
            return false;
        }
        Long listItemId = event.getFace().getListItem().getId();
        repo.deleteListItem(listItemId);
        log.info("[REMOVE] deleted list_item id={}", listItemId);
        return true;
    }

    // nightly clean
    @Scheduled(cron = "0 0 0 * * SUN")
    public void cleanUnknownList() {
        fetchListItems(CLEAN_PAGE_LIMIT).forEach(this::attemptDelete);
        log.info("[CLEAN] Unknown list cleaned");
    }

    private String nextUnknownName() {
        ListItemsResponse resp = repo.getListItems(unknownListRegistry.get(), UNKNOWN_PREFIX, "", 0, 1, "asc", "name");
        int total = resp.getTotal() == null ? 0 : resp.getTotal();
        return UNKNOWN_PREFIX + (total + 1);
    }

    private List<DetectionDto> fetchCandidateDetections(FaceEventDto event) {
        long windowStart = event.getTimestamp() - DETECTION_WINDOW_MILLIS;
        long windowEnd = event.getTimestamp() + DETECTION_WINDOW_MILLIS;
        List<DetectionDto> detections = Optional.ofNullable(
                        repo.getRecentDetections(RECENT_DETECTION_LIMIT, "asc", windowStart, windowEnd).getData())
                .orElse(List.of());
        detections.forEach(d -> log.info("[ADD] candidate id={} face_image={}", d.getId(), d.getFaceImage()));
        return detections;
    }

    private DetectionDto findMatchingDetection(FaceEventDto event, List<DetectionDto> detections) {
        return detections.stream()
                .filter(d -> Objects.equals(d.getFaceImage(), event.getFaceImage()))
                .peek(d -> log.info("[ADD] MATCH id={}", d.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No detection with matching face_image: " + event.getFaceImage()));
    }

    private boolean isUniqueDetection(DetectionDto detection) {
        return downloadBytes(detection)
                .map(bytes -> repo.isFaceUniqueInLists(bytes, null))
                .orElse(false);
    }

    private Optional<byte[]> downloadBytes(DetectionDto detection) {
        return Optional.ofNullable(detection)
                .map(DetectionDto::getFaceImage)
                .filter(img -> !img.isBlank())
                .map(this::downloadImageSafely);
    }

    private byte[] downloadImageSafely(String imagePath) {
        return executeSafe(() -> repo.downloadStorageObject(imagePath), new byte[0]);
    }

    private <T> T executeSafe(Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[ADD] Failed to download face image: {}", e.getMessage());
            return fallback;
        }
    }

    private boolean isUnknownListEvent(FaceEventDto event) {
        return event.isInList()
                && event.getFace() != null
                && event.getFace().getListItem() != null
                && Objects.equals(event.getFace().getListItem().getList().getId(), unknownListRegistry.get())
                && event.getFace().getListItem().getId() != null;
    }

    private List<ListItemDto> fetchListItems(int limit) {
        ListItemsResponse response = repo.getListItems(unknownListRegistry.get(), "", "", 0, limit, "asc", "name");
        return Optional.ofNullable(response.getData()).orElse(List.of());
    }

    private void attemptDelete(ListItemDto listItemDto) {
        if (listItemDto == null || listItemDto.getId() == null) {
            return;
        }
        try {
            repo.deleteListItem(listItemDto.getId());
        } catch (Exception ex) {
            log.warn("Delete failed id={}: {}", listItemDto.getId(), ex.getMessage());
        }
    }
}

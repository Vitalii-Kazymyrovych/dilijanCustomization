package com.incoresoft.dilijanCustomization.domain.unknown.service;

import com.incoresoft.dilijanCustomization.config.UnknownListRegistry;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.*;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnknownPersonService {

    private final FaceApiRepository repo;
    private final UnknownListRegistry unknownListRegistry;


    // ADD flow
    public Optional<ListItemDto> handleEventAddIfUnknown(FaceEventDto event) {
        if (event.isInList()) {
            log.info("[ADD] Skip: already in a list (list_id={})", event.getFace().getListItem().getList().getId());
            return Optional.empty();
        }

        // 1) retrieve detections
        DetectionsResponse resp = repo.getRecentDetections(100, "asc", event.getTimestamp() - 2000, event.getTimestamp() + 2000);
        List<DetectionDto> list = (resp.getData() == null) ? List.of() : resp.getData();
        list.forEach(d -> log.info("[ADD] candidate id={} face_image={}", d.getId(), d.getFaceImage()));

        // 2) find by face_image
        DetectionDto det = list.stream()
                .filter(d -> d.getFaceImage().equals(event.getFaceImage()))
                .peek(d -> log.info("[ADD] MATCH id={}", d.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No detection with matching face_image: " + event.getFaceImage()));

        // 3) unique name
        String name = nextUnknownName();
        log.info("[ADD] unique name={}", name);

        // 4) create list item
        ListItemDto item = repo.createListItemFromDetection(
                new FromDetectionRequest(unknownListRegistry.get(), det.getId(), name, "auto-unknown"));
        log.info("[ADD] created list_item id={}", item.getId());

        return Optional.ofNullable(item);
    }

    // REMOVE flow
    public boolean handleEventRemoveIfUnknown(FaceEventDto event) {
        boolean target = event.isInList()
                && Objects.equals(event.getFace().getListItem().getList().getId(), unknownListRegistry.get())
                && event.getFace().getListItem().getId() != null;
        if (!target) {
            log.info("[REMOVE] Skip: not in unknown list or list_item_id missing");
            return false;
        }
        repo.deleteListItem(event.getFace().getListItem().getId());
        log.info("[REMOVE] deleted list_item id={}", event.getFace().getListItem().getId());
        return true;
    }

    // nightly clean
    @Scheduled(cron = "0 0 0 * * SUN")
    public void cleanUnknownList() {
        ListItemsResponse resp = repo.getListItems(unknownListRegistry.get(), "", "", 0, 500, "asc", "name");
        for (ListItemDto li : (resp.getData() == null ? List.<ListItemDto>of() : resp.getData())) {
            try { repo.deleteListItem(li.getId()); }
            catch (Exception ex) { log.warn("Delete failed id={}: {}", li.getId(), ex.getMessage()); }
        }
        log.info("[CLEAN] Unknown list cleaned");
    }

    private String nextUnknownName() {
        ListItemsResponse resp = repo.getListItems(unknownListRegistry.get(), "unknown", "", 0, 1, "asc", "name");
        int total = resp.getTotal() == null ? 0 : resp.getTotal();
        return "unknown" + (total + 1);
    }
}

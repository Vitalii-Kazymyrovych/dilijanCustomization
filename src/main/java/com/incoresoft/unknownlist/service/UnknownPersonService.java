package com.incoresoft.unknownlist.service;

import com.incoresoft.unknownlist.config.FaceProps;
import com.incoresoft.unknownlist.dto.*;
import com.incoresoft.unknownlist.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnknownPersonService {

    private final FaceApiRepository repo;
    private final FaceProps faceProps;

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
                new FromDetectionRequest(faceProps.getUnknownListId(), det.getId(), name, "auto-unknown"));
        log.info("[ADD] created list_item id={}", item.getId());

        return Optional.ofNullable(item);
    }

    // REMOVE flow
    public boolean handleEventRemoveIfUnknown(FaceEventDto event) {
        boolean target = event.isInList()
                && Objects.equals(event.getFace().getListItem().getList().getId(), faceProps.getUnknownListId())
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
    public void cleanUnknownList() {
        ListItemsResponse resp = repo.getListItems(faceProps.getUnknownListId(), "", "", 0, 500, "asc", "name");
        for (ListItemDto li : (resp.getData() == null ? List.<ListItemDto>of() : resp.getData())) {
            try { repo.deleteListItem(li.getId()); }
            catch (Exception ex) { log.warn("Delete failed id={}: {}", li.getId(), ex.getMessage()); }
        }
        log.info("[CLEAN] Unknown list cleaned");
    }

    private String nextUnknownName() {
        ListItemsResponse resp = repo.getListItems(faceProps.getUnknownListId(), "unknown", "", 0, 1, "asc", "name");
        int total = resp.getTotal() == null ? 0 : resp.getTotal();
        return "unknown" + (total + 1);
    }
}

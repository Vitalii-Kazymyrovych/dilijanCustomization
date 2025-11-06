package com.incoresoft.dilijanCustomization.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.FaceEventDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.unknown.service.UnknownPersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/webhooks/vezha")
@RequiredArgsConstructor
public class VezhaWebhookController {

    private final UnknownPersonService service;
    private final ObjectMapper objectMapper;

    @PostMapping(path = "/face-event/add", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> addFromEntrance(
            @RequestBody String raw,
            @RequestParam(required = false) Map<String, String> params,
            @RequestHeader(required = false) MultiValueMap<String, String> headers
    ) {
        System.out.println("=== /face-event/add RAW ===\n" + raw + "\n=== END RAW ===");
        try {
            FaceEventDto event = objectMapper.readValue(raw, FaceEventDto.class);
            Optional<ListItemDto> res = service.handleEventAddIfUnknown(event);
            return res.<ResponseEntity<Object>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_MODIFIED).build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error","bad_request","message", e.getMessage()));
        }
    }

    @PostMapping(path = "/face-event/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> removeFromExit(
            @RequestBody String raw,
            @RequestParam(required = false) Map<String, String> params,
            @RequestHeader(required = false) MultiValueMap<String, String> headers
    ) {
        System.out.println("=== /face-event/remove RAW ===\n" + raw + "\n=== END RAW ===");
        try {
            FaceEventDto event = objectMapper.readValue(raw, FaceEventDto.class);
            boolean removed = service.handleEventRemoveIfUnknown(event);
            return removed ? ResponseEntity.noContent().build()
                    : ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}

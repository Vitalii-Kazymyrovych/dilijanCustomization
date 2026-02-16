package com.incoresoft.dilijanCustomization.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.FaceEventDto;
import com.incoresoft.dilijanCustomization.domain.unknown.service.UnknownPersonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VezhaWebhookControllerTest {

    @Mock
    private UnknownPersonService service;

    private VezhaWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new VezhaWebhookController(service, new ObjectMapper());
    }

    @Test
    void addFromEntranceReturnsOkWhenItemCreated() {
        ListItemDto created = new ListItemDto();
        created.setId(7L);
        when(service.handleEventAddIfUnknown(any(FaceEventDto.class))).thenReturn(Optional.of(created));

        var response = controller.addFromEntrance("{\"timestamp\":123,\"in_list\":false}", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void addFromEntranceReturnsNotModifiedWhenSkipped() {
        when(service.handleEventAddIfUnknown(any(FaceEventDto.class))).thenReturn(Optional.empty());

        var response = controller.addFromEntrance("{\"timestamp\":123,\"in_list\":false}", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void addFromEntranceReturnsBadRequestForInvalidPayload() {
        var response = controller.addFromEntrance("not-json", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
    }

    @Test
    void removeFromExitReturnsNoContentWhenRemoved() {
        when(service.handleEventRemoveIfUnknown(any(FaceEventDto.class))).thenReturn(true);

        var response = controller.removeFromExit("{\"timestamp\":123,\"in_list\":true}", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeFromExitReturnsNotModifiedWhenSkipped() {
        when(service.handleEventRemoveIfUnknown(any(FaceEventDto.class))).thenReturn(false);

        var response = controller.removeFromExit("{\"timestamp\":123,\"in_list\":true}", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void removeFromExitReturnsBadRequestForInvalidPayload() {
        var response = controller.removeFromExit("not-json", Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

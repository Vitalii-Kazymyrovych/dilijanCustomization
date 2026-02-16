package com.incoresoft.dilijanCustomization.domain.unknown.service;

import com.incoresoft.dilijanCustomization.config.UnknownListRegistry;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.FaceEventDto;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnknownPersonServiceTest {

    @Mock
    private FaceApiRepository repository;

    @Mock
    private UnknownListRegistry unknownListRegistry;

    @InjectMocks
    private UnknownPersonService service;

    @Test
    void handleEventAddIfUnknownCreatesItemForUniqueDetection() {
        FaceEventDto event = buildEvent(false, null, null, "face-image");
        DetectionDto detection = new DetectionDto();
        detection.setId(777L);
        detection.setFaceImage("face-image");

        DetectionsResponse detectionsResponse = new DetectionsResponse();
        detectionsResponse.setData(List.of(detection));

        ListItemsResponse countResponse = new ListItemsResponse();
        countResponse.setTotal(2);

        ListItemDto created = new ListItemDto();
        created.setId(999L);

        when(unknownListRegistry.get()).thenReturn(10L);
        when(repository.getRecentDetections(eq(100), eq("asc"), anyLong(), anyLong())).thenReturn(detectionsResponse);
        when(repository.downloadStorageObject("face-image")).thenReturn(new byte[]{1, 2, 3});
        when(repository.isFaceUniqueInLists(new byte[]{1, 2, 3}, null)).thenReturn(true);
        when(repository.getListItems(10L, "unknown", "", 0, 1, "asc", "name")).thenReturn(countResponse);
        when(repository.createListItemFromDetection(any())).thenReturn(created);

        Optional<ListItemDto> result = service.handleEventAddIfUnknown(event);

        assertThat(result).contains(created);
        ArgumentCaptor<com.incoresoft.dilijanCustomization.domain.unknown.dto.FromDetectionRequest> captor =
                ArgumentCaptor.forClass(com.incoresoft.dilijanCustomization.domain.unknown.dto.FromDetectionRequest.class);
        verify(repository).createListItemFromDetection(captor.capture());
        assertThat(captor.getValue().getListId()).isEqualTo(10L);
        assertThat(captor.getValue().getDetectionId()).isEqualTo(777L);
        assertThat(captor.getValue().getName()).isEqualTo("unknown3");
    }

    @Test
    void handleEventAddIfUnknownSkipsWhenEventAlreadyInList() {
        FaceEventDto event = buildEvent(true, 10L, 1L, "face-image");

        Optional<ListItemDto> result = service.handleEventAddIfUnknown(event);

        assertThat(result).isEmpty();
        verify(repository, never()).getRecentDetections(any(), any(), anyLong(), anyLong());
    }

    @Test
    void handleEventAddIfUnknownSkipsWhenEventAlreadyInListWithoutListPayload() {
        FaceEventDto event = new FaceEventDto();
        event.setInList(true);

        Optional<ListItemDto> result = service.handleEventAddIfUnknown(event);

        assertThat(result).isEmpty();
        verify(repository, never()).getRecentDetections(any(), any(), anyLong(), anyLong());
    }

    @Test
    void handleEventAddIfUnknownSkipsWhenImageDownloadFails() {
        FaceEventDto event = buildEvent(false, null, null, "face-image");
        DetectionDto detection = new DetectionDto();
        detection.setId(100L);
        detection.setFaceImage("face-image");

        DetectionsResponse detectionsResponse = new DetectionsResponse();
        detectionsResponse.setData(List.of(detection));

        when(repository.getRecentDetections(eq(100), eq("asc"), anyLong(), anyLong())).thenReturn(detectionsResponse);
        when(repository.downloadStorageObject("face-image")).thenThrow(new RuntimeException("boom"));

        Optional<ListItemDto> result = service.handleEventAddIfUnknown(event);

        assertThat(result).isEmpty();
        verify(repository, never()).createListItemFromDetection(any());
    }

    @Test
    void handleEventAddIfUnknownThrowsWhenNoMatchingDetectionFound() {
        FaceEventDto event = buildEvent(false, null, null, "missing");
        DetectionDto detection = new DetectionDto();
        detection.setId(1L);
        detection.setFaceImage("other");
        DetectionsResponse detectionsResponse = new DetectionsResponse();
        detectionsResponse.setData(List.of(detection));
        when(repository.getRecentDetections(eq(100), eq("asc"), anyLong(), anyLong())).thenReturn(detectionsResponse);

        assertThatThrownBy(() -> service.handleEventAddIfUnknown(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No detection with matching face_image");
    }

    @Test
    void handleEventAddIfUnknownThrowsWhenTimestampMissing() {
        FaceEventDto event = buildEvent(false, null, null, "face-image");
        event.setTimestamp(null);

        assertThatThrownBy(() -> service.handleEventAddIfUnknown(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timestamp is required");
    }

    @Test
    void handleEventRemoveIfUnknownDeletesOnlyForUnknownList() {
        FaceEventDto event = buildEvent(true, 15L, 555L, null);
        when(unknownListRegistry.get()).thenReturn(15L);

        boolean removed = service.handleEventRemoveIfUnknown(event);

        assertThat(removed).isTrue();
        verify(repository).deleteListItem(555L);
    }

    @Test
    void handleEventRemoveIfUnknownReturnsFalseForDifferentList() {
        when(unknownListRegistry.get()).thenReturn(15L);
        FaceEventDto event = buildEvent(true, 99L, 555L, null);

        boolean removed = service.handleEventRemoveIfUnknown(event);

        assertThat(removed).isFalse();
        verify(repository, never()).deleteListItem(anyLong());
    }

    @Test
    void handleEventRemoveIfUnknownReturnsFalseWhenListDataMissing() {
        FaceEventDto event = buildEvent(true, null, 555L, null);
        event.getFace().getListItem().setList(null);

        boolean removed = service.handleEventRemoveIfUnknown(event);

        assertThat(removed).isFalse();
        verify(repository, never()).deleteListItem(anyLong());
    }

    @Test
    void cleanUnknownListDeletesOnlyItemsWithIds() {
        when(unknownListRegistry.get()).thenReturn(11L);
        ListItemDto valid = new ListItemDto();
        valid.setId(1L);
        ListItemDto missingId = new ListItemDto();

        ListItemsResponse response = new ListItemsResponse();
        response.setData(java.util.Arrays.asList(valid, missingId, null));
        when(repository.getListItems(11L, "", "", 0, 500, "asc", "name")).thenReturn(response);

        service.cleanUnknownList();

        verify(repository).deleteListItem(1L);
        verify(repository, never()).deleteListItem(null);
    }

    private FaceEventDto buildEvent(boolean inList, Long listId, Long listItemId, String faceImage) {
        FaceEventDto event = new FaceEventDto();
        event.setInList(inList);
        event.setTimestamp(1_000L);
        event.setFaceImage(faceImage);

        FaceEventDto.FacePayload payload = new FaceEventDto.FacePayload();
        FaceEventDto.ListItemRef listItemRef = new FaceEventDto.ListItemRef();
        FaceEventDto.PersonListRef personListRef = new FaceEventDto.PersonListRef();
        personListRef.setId(listId);
        listItemRef.setList(personListRef);
        listItemRef.setId(listItemId);
        payload.setListItem(listItemRef);
        event.setFace(payload);
        return event;
    }
}

package com.incoresoft.dilijanCustomization.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incoresoft.dilijanCustomization.config.VezhaApiProps;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.attendance.dto.UniquePeopleResponse;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FaceApiRepository {
    private final RestTemplate vezha;
    private final VezhaApiProps props;
    private final ObjectMapper mapper = new ObjectMapper();

    // POST /face/detections (multipart: -F image=) + query params (limit, sort_order, start_date, end_date)
    public DetectionsResponse getRecentDetections(Integer limit, String sortOrder, Long startTs, Long endTs) {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl())        // http://localhost:2001/api
                .path("/face/detections")
                .queryParam("limit",      (limit == null ? 100 : limit))
                .queryParam("sort_order", (sortOrder == null ? "asc" : sortOrder))
                .queryParamIfPresent("start_date", java.util.Optional.ofNullable(startTs))
                .queryParamIfPresent("end_date",   java.util.Optional.ofNullable(endTs))
                .build()
                .toUriString();

        // POST multipart/form-data with an *empty* multipart body (no parts)
        MultiValueMap<String, Object> emptyBody = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<MultiValueMap<String, Object>> emptyReq = new HttpEntity<>(emptyBody, headers);

        try {
            ResponseEntity<DetectionsResponse> resp =
                    vezha.exchange(url, HttpMethod.POST, emptyReq, DetectionsResponse.class);
            return (resp.getBody() != null) ? resp.getBody() : emptyDetections();
        } catch (HttpClientErrorException.NotFound nf) {
            return emptyDetections();
        }
    }

    // POST /face/list_items/from_detection (application/json)
    public ListItemDto createListItemFromDetection(FromDetectionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<FromDetectionRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<ListItemDto> resp =
                vezha.exchange(props.getBaseUrl() + "/face/list_items/from_detection", HttpMethod.POST, entity, ListItemDto.class);
        return resp.getBody();
    }

    // GET /face/list_items
    public ListItemsResponse getListItems(Long listId, String name, String comment,
                                          Integer offset, Integer limit, String order, String sortBy) {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl())
                .path("/face/list_items")
                .queryParam("list_id", listId)
                .queryParam("name",    name == null ? "" : name)
                .queryParam("comment", comment == null ? "" : comment)
                .queryParam("offset",  offset == null ? 0 : offset)
                .queryParam("limit",   limit  == null ? 20 : limit)
                .queryParam("order",   order  == null ? "asc" : order)
                .queryParam("sort_by", sortBy == null ? "name" : sortBy)
                .build().toUriString();

        ResponseEntity<ListItemsResponse> resp =
                vezha.exchange(url, HttpMethod.GET, null, ListItemsResponse.class);
        return resp.getBody();
    }

    // DELETE /face/list_items/{id}
    public void deleteListItem(Long id) {
        vezha.delete(props.getBaseUrl() + "/face/list_items/{id}", id);
    }

    private static DetectionsResponse emptyDetections() {
        DetectionsResponse r = new DetectionsResponse();
        r.setData(Collections.emptyList());
        r.setTotal(0);
        r.setPages(0);
        r.setStatus("ok");
        return r;
    }

    /** GET /face/lists?limit=100 */
    public FaceListsResponse getFaceLists(int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl())
                .path("/face/lists")
                .queryParam("limit", limit)
                .build().toUriString();

        ResponseEntity<FaceListsResponse> resp =
                vezha.exchange(url, HttpMethod.GET, null, FaceListsResponse.class);
        return resp.getBody();
    }

    public DetectionsResponse getDetectionsFiltered(
            Long listId,
            List<Long> analyticsIds,
            Long startMillis,
            Long endMillis,
            Integer limit,
            Integer offset,
            String sortOrder
    ) {
        // Build URL: /face/detections?limit=...&offset=...&sort_order=...&start_date=...&end_date=...&list_id=...&analytics_ids=[2,3]
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl())
                .path("/face/detections")
                .queryParam("limit",  limit == null ? 500 : limit)
                .queryParam("sort_order", sortOrder == null ? "asc" : sortOrder);

        if (offset != null)    b.queryParam("offset", offset);
        if (startMillis != null) b.queryParam("start_date", startMillis);
        if (endMillis != null)   b.queryParam("end_date",   endMillis);
        if (listId != null)      b.queryParam("list_id",    listId);

        if (analyticsIds != null && !analyticsIds.isEmpty()) {
            // same style as swagger: "[2,3]"
            String encoded = "[" + analyticsIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "]";
            b.queryParam("analytics_ids", encoded);
        }

        String url = b.build().toUriString();

        // POST multipart/form-data with empty body (works with VEZHA)
        MultiValueMap<String, Object> emptyBody = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(emptyBody, headers);

        try {
            ResponseEntity<DetectionsResponse> resp =
                    vezha.exchange(url, HttpMethod.POST, req, DetectionsResponse.class);
            return resp.getBody();
        } catch (HttpClientErrorException.NotFound nf) {
            return emptyDetections();
        }
    }

    // --- NEW: fetch all pages in a window for ONE list (or for unlisted when listId==null) ---
    public List<DetectionDto> getAllDetectionsInWindow(
            Long listId,
            List<Long> analyticsIds,
            Long startMillis,
            Long endMillis,
            int pageLimit
    ) {
        List<DetectionDto> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            DetectionsResponse page = getDetectionsFiltered(listId, analyticsIds, startMillis, endMillis, pageLimit, offset, "asc");
            List<DetectionDto> data = (page == null || page.getData() == null) ? java.util.Collections.emptyList() : page.getData();
            all.addAll(data);
            if (data.size() < pageLimit) break; // last page
            offset += pageLimit;
        }
        return all;
    }
}

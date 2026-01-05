package com.incoresoft.dilijanCustomization.repository;

import com.incoresoft.dilijanCustomization.config.VezhaApiProps;
import com.incoresoft.dilijanCustomization.domain.shared.dto.*;
import com.incoresoft.dilijanCustomization.domain.unknown.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FaceApiRepository {
    private final RestTemplate vezhaApi;
    private final VezhaApiProps vezhaApiProps;
    private final RestTemplateBuilder restTemplateBuilder;

    // POST /face/detections (multipart: -F image=) + query params (limit, sort_order, start_date, end_date)
    public DetectionsResponse getRecentDetections(Integer limit, String sortOrder, Long startTs, Long endTs) {
        String url = UriComponentsBuilder
                .fromPath("/face/detections")
                .queryParam("limit",      (limit == null ? 100 : limit))
                .queryParam("sort_order", (sortOrder == null ? "asc" : sortOrder))
                .queryParamIfPresent("start_date", java.util.Optional.ofNullable(startTs))
                .queryParamIfPresent("end_date",   java.util.Optional.ofNullable(endTs))
                .build()
                .toUriString();

        HttpEntity<MultiValueMap<String, Object>> emptyReq = getRequestWithEmptyMultipart();

        try {
            ResponseEntity<DetectionsResponse> resp =
                    vezhaApi.exchange(
                            vezhaApiProps.getBaseUrl() + url,
                            HttpMethod.POST,
                            emptyReq,
                            DetectionsResponse.class);
            return (resp.getBody() != null) ? resp.getBody() : emptyDetections();
        } catch (Exception e) {
            log.error("[GET DETECTIONS]", e);
            throw new RuntimeException(e);
        }
    }

    // POST /face/list_items/from_detection (application/json)
    public ListItemDto createListItemFromDetection(FromDetectionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<FromDetectionRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<ListItemDto> resp;
        try {
            resp =
                    vezhaApi.exchange(
                            vezhaApiProps.getBaseUrl() +
                                    "/face/list_items/from_detection",
                            HttpMethod.POST,
                            entity,
                            ListItemDto.class);
        } catch (Exception e) {
            log.error("[CREATE LIST ITEM]", e);
            throw new RuntimeException(e);
        }
        return resp.getBody();
    }

    // GET /face/list_items
    public ListItemsResponse getListItems(Long listId, String name, String comment,
                                          Integer offset, Integer limit,
                                          String order, String sortBy) {
        String url = UriComponentsBuilder
                .fromPath("/face/list_items")
                .queryParam("list_id", listId)
                .queryParam("name",    name == null ? "" : name)
                .queryParam("comment", comment == null ? "" : comment)
                .queryParam("offset",  offset == null ? 0 : offset)
                .queryParam("limit",   limit  == null ? 20 : limit)
                .queryParam("order",   order  == null ? "asc" : order)
                .queryParam("sort_by", sortBy == null ? "name" : sortBy)
                .build().toUriString();
        ResponseEntity<ListItemsResponse> resp;
        try {
            resp =
                    vezhaApi.exchange(
                            vezhaApiProps.getBaseUrl() + url,
                            HttpMethod.GET,
                            null,
                            ListItemsResponse.class);
        } catch (Exception e) {
            log.error("[GET LIST ITEMS]", e);
            throw new RuntimeException(e);
        }
        return resp.getBody();
    }

    // DELETE /face/list_items/{id}
    public void deleteListItem(Long id) {
        try {
            vezhaApi.delete(vezhaApiProps.getBaseUrl() + "/face/list_items/{id}", id);
        } catch (Exception e) {
            log.error("[DELETE LIST ITEM]", e);
            throw new RuntimeException(e);
        }
    }

    /** GET /face/lists?limit=100 */
    public FaceListsResponse getFaceLists(int limit) {
        String url = UriComponentsBuilder
                .fromPath("/face/lists")
                .queryParam("limit", limit)
                .build().toUriString();

        ResponseEntity<FaceListsResponse> resp;
        try {
            resp = vezhaApi.exchange(
                    vezhaApiProps.getBaseUrl() + url,
                    HttpMethod.GET,
                    null,
                    FaceListsResponse.class);
        } catch (RestClientException e) {
            log.error("[GET LISTS]", e);
            throw new RuntimeException(e);
        }
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
        UriComponentsBuilder b = UriComponentsBuilder
                .fromPath("/face/detections")
                .queryParam("limit",  limit == null ? 500 : limit)
                .queryParam("sort_order", sortOrder == null ? "asc" : sortOrder);

        if (offset != null)      b.queryParam("offset", offset);
        if (startMillis != null) b.queryParam("start_date", startMillis);
        if (endMillis != null)   b.queryParam("end_date",   endMillis);
        if (listId != null)      b.queryParam("list_id",    listId);

        if (analyticsIds != null && !analyticsIds.isEmpty()) {
            String encoded = "[" + analyticsIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "]";
            b.queryParam("analytics_ids", encoded);
        }

        String url = b.build().toUriString();

        HttpEntity<MultiValueMap<String, Object>> req = getRequestWithEmptyMultipart();

        try {
            ResponseEntity<DetectionsResponse> resp =
                    vezhaApi.exchange(
                            vezhaApiProps.getBaseUrl() + url,
                            HttpMethod.POST,
                            req,
                            DetectionsResponse.class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("[GET DETECTIONS]", e);
            throw new RuntimeException();
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
            DetectionsResponse page = getDetectionsFiltered(
                    listId,
                    analyticsIds,
                    startMillis,
                    endMillis,
                    pageLimit,
                    offset,
                    "asc");
            List<DetectionDto> data = (page == null || page.getData() == null)
                    ? java.util.Collections.emptyList()
                    : page.getData();
            all.addAll(data);

            Integer total = page == null ? null : page.getTotal();
            Integer pages = page == null ? null : page.getPages();
            int currentPage = (pageLimit > 0) ? (offset / pageLimit) + 1 : 1;
            boolean lastByTotal = total != null && offset + data.size() >= total;
            boolean lastByPages = pages != null && currentPage >= pages;

            if (data.isEmpty() || data.size() < pageLimit || lastByTotal || lastByPages) break; // last page
            offset += pageLimit;
        }
        return all;
    }

    /**
     * Presence CSV for the exact moment:
     * GET /api/face/reports/presence?list_id=...&start_date=ts&end_date=ts
     * Accept: application/octet-stream
     */
    public String downloadPresenceCsv(Long listId, long exactMillis) {
        long startMillis = exactMillis - Duration.ofDays(30).toMillis();
        String url = UriComponentsBuilder
                .fromPath("/face/reports/presence")
                .queryParam("list_id", listId)
                .queryParam("start_date", startMillis)
                .queryParam("end_date", exactMillis)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
        HttpEntity<Void> req = new HttpEntity<>(headers);

        ResponseEntity<byte[]> resp;
        try {
            resp = vezhaApi.exchange(
                    vezhaApiProps.getBaseUrl() + url,
                    HttpMethod.GET,
                    req,
                    byte[].class);
        } catch (Exception e) {
            log.error("[DOWNLOAD PRESENCE]", e);
            throw new RuntimeException(e);
        }
        return (resp.getBody() != null) ? new String(resp.getBody(), StandardCharsets.UTF_8) : "";
    }

    public byte[] downloadStorageObject(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return new byte[0];

        String url;
        String base;
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            base = "";
            url = imagePath;
        } else {
            base = vezhaApiProps.getBaseUrl().replaceFirst("/api/?$", "");
            url = UriComponentsBuilder
                    .fromPath("/storage/")
                    .path(imagePath.startsWith("/") ? imagePath.substring(1) : imagePath)
                    .build()
                    .toUriString();
        }

        RestTemplate rest = restTemplateBuilder.build();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(
                MediaType.parseMediaType("image/avif"),
                MediaType.parseMediaType("image/webp"),
                MediaType.parseMediaType("image/apng"),
                MediaType.parseMediaType("image/svg+xml"),
                MediaType.IMAGE_JPEG,
                MediaType.IMAGE_PNG,
                MediaType.ALL
        ));
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + vezhaApiProps.getToken());

        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> resp = rest.exchange(
                    base + url,
                    HttpMethod.GET,
                    req,
                    byte[].class);
            return Optional.ofNullable(resp.getBody()).orElse(new byte[0]);
        } catch (Exception e) {
            log.error("[DOWNLOAD]", e);
            throw new RuntimeException(e);
        }
    }

    public Optional<FaceListDto> findListByName(String name) {
        return getFaceLists(100).getData()
                .stream()
                .filter(l -> l.getName() != null && l.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    public long createFaceList(String name, String comment) {
        var payload = CreateListRequest.builder()
                .name(name)
                .comment(comment == null ? "" : comment)
                .color("#FFFFFF")
                .minConfidence(80)
                .status(1)
                .sendInternalNotifications(true)
                .showPopupForInternalNotifications(false)
                .analyticsIds(null)
                .timeAttendance(new TimeAttendance(false, List.of(), List.of()))
                .accessRestrictions(AccessRestrictions.defaultOpen())
                .eventsHolder(new EventsHolder(false, List.of()))
                .build();

        var entity = new HttpEntity<>(payload, headers());
        ResponseEntity<FaceListDto> response;
        try {
            response = vezhaApi.exchange(
                    vezhaApiProps.getBaseUrl() + "/face/lists",
                    HttpMethod.POST,
                    entity,
                    FaceListDto.class);
        } catch (Exception e) {
            log.error("[CREATE LIST]", e);
            throw new RuntimeException(e);
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Failed to create face list: HTTP " + response.getStatusCode());
        }
        return response.getBody().getId();
    }

    // Получить конфиг списка (входные/выходные камеры)
    public ListConfigDto getListConfig(Long listId) {
        String url = "/face/lists/" + listId;
        ResponseEntity<ListConfigDto> resp = vezhaApi.exchange(
                vezhaApiProps.getBaseUrl() + url,
                HttpMethod.GET,
                null,
                ListConfigDto.class);
        return resp.getBody();
    }


    private static DetectionsResponse emptyDetections() {
        DetectionsResponse r = new DetectionsResponse();
        r.setData(Collections.emptyList());
        r.setTotal(0);
        r.setPages(0);
        r.setStatus("ok");
        return r;
    }

    private HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.setBearerAuth(vezhaApiProps.getToken());
        return h;
    }

    private static HttpEntity<MultiValueMap<String, Object>> getRequestWithEmptyMultipart() {
        MultiValueMap<String, Object> emptyBody = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(emptyBody, headers);
    }
}

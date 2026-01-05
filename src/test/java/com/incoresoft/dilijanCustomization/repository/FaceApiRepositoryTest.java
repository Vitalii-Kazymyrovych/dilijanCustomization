package com.incoresoft.dilijanCustomization.repository;

import com.incoresoft.dilijanCustomization.config.VezhaApiProps;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.DetectionsResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FaceApiRepositoryTest {

    @Test
    void aggregatesDetectionsAcrossPages() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        RestTemplateBuilder builder = Mockito.mock(RestTemplateBuilder.class);
        VezhaApiProps props = new VezhaApiProps();
        props.setBaseUrl("http://example");
        props.setToken("token");

        FaceApiRepository repo = Mockito.spy(new FaceApiRepository(restTemplate, props, builder));

        DetectionDto d1 = new DetectionDto();
        d1.setId(1L);
        d1.setTimestamp(10L);
        DetectionDto d2 = new DetectionDto();
        d2.setId(2L);
        d2.setTimestamp(20L);

        DetectionsResponse page1 = new DetectionsResponse();
        page1.setData(List.of(d1));
        page1.setTotal(2);
        page1.setPages(2);
        DetectionsResponse page2 = new DetectionsResponse();
        page2.setData(List.of(d2));
        page2.setTotal(2);
        page2.setPages(2);

        doReturn(page1, page2).when(repo)
                .getDetectionsFiltered(any(), anyList(), any(), any(), anyInt(), anyInt(), anyString());

        List<DetectionDto> all = repo.getAllDetectionsInWindow(5L, List.of(1L), 0L, 100L, 1);
        assertThat(all).containsExactly(d1, d2);
    }

    @Test
    void downloadsStorageObjectThroughRestTemplate() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        RestTemplateBuilder builder = Mockito.mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        VezhaApiProps props = new VezhaApiProps();
        props.setBaseUrl("http://example/api");
        props.setToken("token");

        FaceApiRepository repo = new FaceApiRepository(restTemplate, props, builder);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[]{1, 2, 3}));

        byte[] result = repo.downloadStorageObject("image.jpg");
        assertThat(result).containsExactly(1, 2, 3);

        assertThat(repo.downloadStorageObject("  ")).isEmpty();
    }

    @Test
    void buildsDetectionUrlWithoutDuplicatingSlashes() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestTemplateBuilder builder = new RestTemplateBuilder();
        VezhaApiProps props = new VezhaApiProps();
        props.setBaseUrl("http://example/api/");
        props.setToken("token");

        FaceApiRepository repo = new FaceApiRepository(restTemplate, props, builder);

        server.expect(requestTo("http://example/api/face/detections?limit=1&sort_order=asc&offset=0"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andRespond(withSuccess("{\"data\":[{\"id\":1,\"timestamp\":5}],\"total\":1,\"pages\":1,\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        List<DetectionDto> all = repo.getAllDetectionsInWindow(null, null, null, null, 1);
        assertThat(all).hasSize(1);

        server.verify();
    }
}

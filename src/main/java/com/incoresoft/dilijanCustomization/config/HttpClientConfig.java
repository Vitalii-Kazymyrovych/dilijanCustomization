package com.incoresoft.dilijanCustomization.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
@EnableConfigurationProperties({VezhaApiProps.class, CafeteriaProps.class})
public class HttpClientConfig {
    @Bean
    public RestTemplate vezhaRestTemplate(VezhaApiProps props, RestTemplateBuilder builder) {
        ClientHttpRequestInterceptor auth = (req, body, exec) -> {
            req.getHeaders().add("Authorization", "Bearer " + props.getToken());
            req.getHeaders().add("Accept", "application/json");
            return exec.execute(req, body);
        };
        // TODO: understand why rootUri doesn't work
        return builder
                .rootUri(props.getBaseUrl()) // http://localhost:2001/api
                .additionalInterceptors(List.of(auth))
                .build();
    }
}

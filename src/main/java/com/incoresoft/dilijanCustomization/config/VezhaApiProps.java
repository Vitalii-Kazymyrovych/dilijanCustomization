package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "vezha.api")
public class VezhaApiProps {
  private String baseUrl;
  private String token;
}

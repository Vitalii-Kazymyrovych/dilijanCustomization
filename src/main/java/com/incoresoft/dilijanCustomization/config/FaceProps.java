// config/FaceProps.java
package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "vezha.face")
public class FaceProps {
  private long unknownListId;
}

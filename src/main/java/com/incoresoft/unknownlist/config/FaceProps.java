// config/FaceProps.java
package com.incoresoft.unknownlist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@ConfigurationProperties(prefix = "vezha.face")
public class FaceProps {
  private long unknownListId;
}

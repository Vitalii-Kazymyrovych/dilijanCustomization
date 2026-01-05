package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


@Data
@ConfigurationProperties(prefix = "unknown")
public class UnknownProps {
    private boolean autostart = true;
}

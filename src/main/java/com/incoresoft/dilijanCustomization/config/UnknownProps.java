package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "unknown")
public class UnknownProps {
    private boolean autostart = false;
    /**
     * Camera frame height in pixels used to convert normalized detection box coordinates.
     */
    private int cameraResolutionHeight = 1080;
    /**
     * Minimum face crop height in pixels required to auto-add unknown persons.
     */
    private int desiredImageHeight = 120;
}

package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "vezha.cafe")
public class CafeteriaProps {
    /** Analytics to use, e.g. [2] */
    private List<Long> analyticsIds = new ArrayList<>();
    /** Local timezone for windows & schedule */
    private String timezone;
    /** Cron for daily generation (local time) */
    private String scheduleCron;
    /** Absolute output folder for XLSX */
    private String outputDir;
    /** Lists to exclude by name (case-insensitive), e.g. outsourced/contractors */
    private Set<String> excludedListNames;
    // Meal windows (local time)
    private LocalTime breakfastStart = LocalTime.of(6, 30);
    private LocalTime breakfastEnd   = LocalTime.of(10, 30);
    private LocalTime lunchStart     = LocalTime.of(11, 30);
    private LocalTime lunchEnd       = LocalTime.of(14, 30);
    private LocalTime dinnerStart    = LocalTime.of(18, 0);
    private LocalTime dinnerEnd      = LocalTime.of(20, 30);
}

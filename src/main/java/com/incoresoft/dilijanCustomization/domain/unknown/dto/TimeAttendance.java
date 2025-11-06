package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeAttendance {
    private Boolean enabled;
    @JsonProperty("entrance_analytics_ids")
    private List<Long> entranceAnalyticsIds;
    @JsonProperty("exit_analytics_ids")
    private List<Long> exitAnalyticsIds;
}
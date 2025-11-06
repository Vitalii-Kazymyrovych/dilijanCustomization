package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateListRequest {
    // core
    private String name;
    private String comment;
    private String color;              // "#FFFFFF"
    @JsonProperty("min_confidence")
    private Integer minConfidence;     // 80
    private Integer status;            // 1 = active

    // notifications
    @JsonProperty("send_internal_notifications")
    private Boolean sendInternalNotifications;
    @JsonProperty("show_popup_for_internal_notifications")
    private Boolean showPopupForInternalNotifications;

    // analytics linkage (optional)
    @JsonProperty("analytics_ids")
    private List<Long> analyticsIds;

    // time attendance section (can be disabled for unknown list)
    @JsonProperty("time_attendance")
    private TimeAttendance timeAttendance;

    // permissions (mirror UI defaults = open)
    @JsonProperty("access_restrictions")
    private AccessRestrictions accessRestrictions;

    // events holder (UI sends it; we keep minimal)
    @JsonProperty("events_holder")
    private EventsHolder eventsHolder;
}
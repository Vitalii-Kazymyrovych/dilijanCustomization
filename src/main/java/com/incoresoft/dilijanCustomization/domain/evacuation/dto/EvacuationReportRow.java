package com.incoresoft.dilijanCustomization.domain.evacuation.dto;

import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;

/**
 * Row data for the evacuation report: the list item and the timestamp of the entrance
 * detection that marked the person as on site.
 */
public record EvacuationReportRow(ListItemDto item, Long entranceTime) {
}

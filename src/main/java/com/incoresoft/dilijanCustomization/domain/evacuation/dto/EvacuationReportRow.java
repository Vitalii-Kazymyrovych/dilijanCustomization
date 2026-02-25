package com.incoresoft.dilijanCustomization.domain.evacuation.dto;

import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;

/**
 * Row data for the evacuation report: the list item and the timestamp of the entrance
 * detection that marked the person as on site, plus whether the status was manually
 * updated in the evacuation table.
 */
public record EvacuationReportRow(ListItemDto item, Long entranceTime, boolean manuallyUpdated) {
}

package com.incoresoft.dilijanCustomization.domain.evacuation.dto;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Composite primary key for the EvacuationStatus entity.
 * Consists of listId and listItemId.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationStatusPK implements Serializable {
    private Long listId;
    private Long listItemId;
}

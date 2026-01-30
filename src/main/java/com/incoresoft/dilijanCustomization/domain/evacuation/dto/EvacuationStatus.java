package com.incoresoft.dilijanCustomization.domain.evacuation.dto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing an evacuation status record. This maps to the
 * `evacuation` table and stores a status flag per list item along with
 * the entrance/exit stream identifiers used to compute that status.
 */
@Entity
@Table(name = "evacuation")
@IdClass(EvacuationStatusPK.class)
@Data
public class EvacuationStatus {
    /** The list identifier (face list) that this record belongs to. */
    @Id
    @Column(name = "list_id")
    private Long listId;
    /** Identifier of the person (list item) within the list. */
    @Id
    @Column(name = "list_item_id")
    private Long listItemId;
    /**
     * Streams considered entrances. Stored as a PostgreSQL integer array.
     */
    @Column(name = "enter_stream_ids", columnDefinition = "integer[]")
    private Long[] enterStreamIds;
    /**
     * Streams considered exits. Stored as a PostgreSQL integer array.
     */
    @Column(name = "exit_stream_ids", columnDefinition = "integer[]")
    private Long[] exitStreamIds;
    /** Flag indicating whether the person is on site (true) or has evacuated (false). */
    @Column(name = "status")
    private Boolean status;
    /** Timestamp (epoch millis) of the last entrance detection that marked the user on site. */
    @Column(name = "entrance_time")
    private Long entranceTime;
    /** Timestamp (epoch millis) of the last exit detection that marked the user as evacuated. */
    @Column(name = "exit_time")
    private Long exitTime;
    /** Flag indicating whether the status was manually adjusted via uploaded workbook. */
    @Column(name = "manually_updated")
    private Boolean manuallyUpdated;
}

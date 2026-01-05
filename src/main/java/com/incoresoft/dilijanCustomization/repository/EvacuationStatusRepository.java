package com.incoresoft.dilijanCustomization.repository;

import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatus;
import com.incoresoft.dilijanCustomization.domain.evacuation.dto.EvacuationStatusPK;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for performing CRUD operations on evacuation status records.
 */
@Repository
public interface EvacuationStatusRepository extends JpaRepository<EvacuationStatus, EvacuationStatusPK> {

    /**
     * Retrieve all active records for a given list. Only rows where status=true are returned.
     *
     * @param listId identifier of the face list
     * @return list of active evacuation records
     */
    List<EvacuationStatus> findByListIdAndStatusTrue(Long listId);

    /**
     * Update the status field for the given list and listItem combination.
     * This uses a JPQL update to modify only the status flag.
     */
    @Modifying
    @Query("update EvacuationStatus e set e.status = :status, e.entranceTime = :entranceTime where e.listId = :listId and e.listItemId = :listItemId")
    void updateStatus(@Param("listId") Long listId,
                      @Param("listItemId") Long listItemId,
                      @Param("status") Boolean status,
                      @Param("entranceTime") Long entranceTime);
}

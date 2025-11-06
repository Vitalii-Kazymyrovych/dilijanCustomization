package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessRestrictions {
    @JsonProperty("creator_id")
    private Long creatorId;
    @JsonProperty("default_permissions")
    private Map<String, Boolean> defaultPermissions;
    @JsonProperty("role_permissions")
    private Map<String, Object> rolePermissions;
    @JsonProperty("user_permissions")
    private Map<String, Object> userPermissions;

    public static AccessRestrictions defaultOpen() {
        return new AccessRestrictions(
                1L, // if you know current user id; or null if VEZHA ignores it
                Map.of(
                        "ViewFaceLists", true,
                        "AddFaceListElement", true,
                        "EditFaceListElement", true,
                        "DeleteFaceListElement", true
                ),
                Map.of(), Map.of()
        );
    }
}

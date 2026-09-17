package com.qqlin.medflow.organization.dto;

import com.qqlin.medflow.organization.domain.ClinicRoom;
import com.qqlin.medflow.organization.domain.ResourceStatus;

public record ClinicRoomResponse(
        long id,
        long departmentId,
        String name,
        ResourceStatus status
) {

    public static ClinicRoomResponse from(ClinicRoom clinicRoom) {
        return new ClinicRoomResponse(
                clinicRoom.id(),
                clinicRoom.departmentId(),
                clinicRoom.name(),
                clinicRoom.status()
        );
    }
}

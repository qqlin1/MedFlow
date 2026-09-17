package com.qqlin.medflow.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateClinicRoomRequest(

        @NotNull(message = "科室 ID 不能为空")
        @Positive(message = "科室 ID 必须大于0")
        Long departmentId,

        @NotBlank(message = "诊室名称不能为空")
        @Size(max = 64, message = "诊室名称长度不能超过64个字符")
        String name
) {
}

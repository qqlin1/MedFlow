package com.qqlin.medflow.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateDoctorRequest(

        @NotBlank(message = "医生账号不能为空")
        @Size(max = 64, message = "医生账号长度不能超过64个字符")
        String username,

        @NotNull(message = "科室 ID 不能为空")
        @Positive(message = "科室 ID 必须大于0")
        Long departmentId,

        @NotBlank(message = "医生姓名不能为空")
        @Size(max = 64, message = "医生姓名长度不能超过64个字符")
        String name
) {
}

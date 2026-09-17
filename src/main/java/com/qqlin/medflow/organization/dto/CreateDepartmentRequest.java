package com.qqlin.medflow.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDepartmentRequest(

        @NotBlank(message = "科室名称不能为空")
        @Size(max = 64, message = "科室名称长度不能超过64个字符")
        String name
) {
}

package com.qqlin.medflow.patient.dto;

import com.qqlin.medflow.patient.domain.PatientGender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreatePatientRequest(

        @NotBlank(message = "就诊人姓名不能为空")
        @Size(max = 64, message = "就诊人姓名不能超过 64 个字符")
        String name,

        @NotNull(message = "就诊人性别不能为空")
        PatientGender gender,

        @NotNull(message = "出生日期不能为空")
        @Past(message = "出生日期必须早于今天")
        LocalDate birthDate,

        @NotBlank(message = "手机号不能为空")
        @Pattern(
                regexp = "^1\\d{10}$",
                message = "手机号格式不正确"
        )
        String phone,

        @NotBlank(message = "与账号所有者的关系不能为空")
        @Size(max = 32, message = "关系不能超过 32 个字符")
        String relationship
) {
}

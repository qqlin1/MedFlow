package com.qqlin.medflow.shared.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "请求参数校验失败"
    ),

    MALFORMED_REQUEST_BODY(
            HttpStatus.BAD_REQUEST,
            "MALFORMED_REQUEST_BODY",
            "请求体格式不正确"
    ),
    DOCTOR_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DOCTOR_NOT_FOUND",
            "医生不存在"
    ),

    CLINIC_ROOM_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "CLINIC_ROOM_NOT_FOUND",
            "诊室不存在"
    ),

    INVALID_SCHEDULE_TIME(
            HttpStatus.BAD_REQUEST,
            "INVALID_SCHEDULE_TIME",
            "排班时间不合法"
    ),

    DOCTOR_DISABLED(
            HttpStatus.CONFLICT,
            "DOCTOR_DISABLED",
            "医生当前不可排班"
    ),

    CLINIC_ROOM_DISABLED(
            HttpStatus.CONFLICT,
            "CLINIC_ROOM_DISABLED",
            "诊室当前不可用"
    ),

    RESOURCE_DEPARTMENT_MISMATCH(
            HttpStatus.CONFLICT,
            "RESOURCE_DEPARTMENT_MISMATCH",
            "医生和诊室不属于同一科室"
    ),

    SCHEDULE_CONFLICT(
            HttpStatus.CONFLICT,
            "SCHEDULE_CONFLICT",
            "医生或诊室的排班时间发生冲突"
    ),

    SHIFT_TEMPLATE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SHIFT_TEMPLATE_NOT_FOUND",
            "班次模板不存在"
    ),

    SHIFT_TEMPLATE_DISABLED(
            HttpStatus.CONFLICT,
            "SHIFT_TEMPLATE_DISABLED",
            "班次模板当前不可用"
    ),

    SHIFT_TEMPLATE_INVALID(
            HttpStatus.CONFLICT,
            "SHIFT_TEMPLATE_INVALID",
            "班次模板配置不合法"
    ),

    DEPARTMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DEPARTMENT_NOT_FOUND",
            "科室不存在"
    ),

    DEPARTMENT_DISABLED(
            HttpStatus.CONFLICT,
            "DEPARTMENT_DISABLED",
            "科室当前不可用"
    ),

    DEPARTMENT_NAME_DUPLICATE(
            HttpStatus.CONFLICT,
            "DEPARTMENT_NAME_DUPLICATE",
            "科室名称已存在"
    ),

    CLINIC_ROOM_NAME_DUPLICATE(
            HttpStatus.CONFLICT,
            "CLINIC_ROOM_NAME_DUPLICATE",
            "该科室下的诊室名称已存在"
    ),

    DOCTOR_ACCOUNT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DOCTOR_ACCOUNT_NOT_FOUND",
            "医生账号不存在"
    ),

    DOCTOR_ACCOUNT_ROLE_INVALID(
            HttpStatus.CONFLICT,
            "DOCTOR_ACCOUNT_ROLE_INVALID",
            "该账号不是医生角色"
    ),

    DOCTOR_ACCOUNT_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "DOCTOR_ACCOUNT_UNAVAILABLE",
            "医生账号当前不可用"
    ),

    DOCTOR_ALREADY_BOUND(
            HttpStatus.CONFLICT,
            "DOCTOR_ALREADY_BOUND",
            "该医生账号已绑定医生档案"
    ),

    SCHEDULE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SCHEDULE_NOT_FOUND",
            "排班不存在"
    ),

    SCHEDULE_NOT_DRAFT(
            HttpStatus.CONFLICT,
            "SCHEDULE_NOT_DRAFT",
            "当前排班状态不允许发布"
    ),

    SCHEDULE_PAST_CANNOT_PUBLISH(
            HttpStatus.CONFLICT,
            "SCHEDULE_PAST_CANNOT_PUBLISH",
            "过期排班不能发布"
    ),

    SCHEDULE_SLOT_CONFIGURATION_INVALID(
            HttpStatus.CONFLICT,
            "SCHEDULE_SLOT_CONFIGURATION_INVALID",
            "排班号源配置不合法"
    ),

    PATIENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "PATIENT_NOT_FOUND",
            "就诊人不存在"
    ),

    SLOT_NOT_BOOKABLE(
            HttpStatus.CONFLICT,
            "SLOT_NOT_BOOKABLE",
            "该号源当前不可预约"
    ),

    SLOT_BOOKING_EXPIRED(
            HttpStatus.CONFLICT,
            "SLOT_BOOKING_EXPIRED",
            "该号源已超过预约截止时间"
    ),

    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "幂等键不能用于不同的请求内容"
    ),

    IDEMPOTENCY_REQUEST_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            "相同幂等请求正在处理中，请稍后重试"
    ),

    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "INVALID_CREDENTIALS",
            "用户名或密码错误"
    ),

    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "UNAUTHORIZED",
            "请先登录"
    ),

    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "没有权限执行此操作"
    ),

    NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            "请求的资源不存在"
    ),

    SLOT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SLOT_NOT_FOUND",
            "号源不存在"
    ),

    SLOT_SOLD_OUT(
            HttpStatus.CONFLICT,
            "SLOT_SOLD_OUT",
            "该号源已约满"
    ),

    DUPLICATE_APPOINTMENT(
            HttpStatus.CONFLICT,
            "DUPLICATE_APPOINTMENT",
            "该就诊人已经预约过此号源"
    ),

    APPOINTMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "APPOINTMENT_NOT_FOUND",
            "预约不存在"
    ),

    APPOINTMENT_NOT_CONFIRMABLE(
            HttpStatus.CONFLICT,
            "APPOINTMENT_NOT_CONFIRMABLE",
            "当前预约状态不允许确认"
    ),

    APPOINTMENT_CONFIRMATION_EXPIRED(
            HttpStatus.CONFLICT,
            "APPOINTMENT_CONFIRMATION_EXPIRED",
            "预约确认时间已过期"
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "服务器内部错误"
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(
            HttpStatus httpStatus,
            String code,
            String defaultMessage
    ) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

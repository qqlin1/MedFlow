package com.qqlin.medflow.devtools.controller;

import com.qqlin.medflow.devtools.dto.WebCheckRequest;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dev/web")
@Profile("local")
public class WebCheckController {

    @GetMapping("/success")
    public Result<String> success() {
        return Result.success("Web链路正常");
    }

    @GetMapping("/sold-out")
    public Result<Void> soldOut() {
        throw new BusinessException(
                ErrorCode.SLOT_SOLD_OUT
        );
    }

    @GetMapping("/unexpected")
    public Result<Void> unexpected() {
        throw new IllegalStateException(
                "用于验证500处理的模拟异常"
        );
    }

    @PostMapping("/validate")
    public Result<WebCheckRequest> validate(
            @Valid @RequestBody WebCheckRequest request
    ) {
        return Result.success(request);
    }
}
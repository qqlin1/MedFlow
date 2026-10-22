package com.qqlin.medflow.identity.auth;

import com.qqlin.medflow.shared.web.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public UI flag; account creation and credentials stay outside this response. */
@RestController
public class DemoConfigController {
    private final boolean enabled;
    private final boolean workflowEnabled;

    public DemoConfigController(@Value("${medflow.demo.accounts.enabled:false}") boolean enabled,
            @Value("${medflow.demo.workflow.enabled:false}") boolean workflowEnabled) {
        this.enabled = enabled;
        this.workflowEnabled = enabled && workflowEnabled;
    }

    public record DemoConfig(boolean enabled, boolean workflowEnabled) {}

    @GetMapping("/api/v1/auth/demo-config")
    public Result<DemoConfig> demoConfig() {
        return Result.success(new DemoConfig(enabled,workflowEnabled));
    }
}

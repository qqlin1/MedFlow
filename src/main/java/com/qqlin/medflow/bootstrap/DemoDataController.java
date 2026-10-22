package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.shared.web.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local opt-in demo only. SecurityConfig restricts the route to ADMIN. */
@RestController
@ConditionalOnProperty(name={"medflow.demo.accounts.enabled","medflow.demo.workflow.enabled"},havingValue="true")
public class DemoDataController {
    private final DemoWorkflowService workflow;
    public DemoDataController(DemoWorkflowService workflow) { this.workflow=workflow; }
    @PostMapping("/api/v1/admin/demo-data/refresh")
    public Result<?> refresh() { return Result.success(workflow.initialize()); }
}

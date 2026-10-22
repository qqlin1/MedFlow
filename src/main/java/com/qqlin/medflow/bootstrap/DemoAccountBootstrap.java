package com.qqlin.medflow.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

@Component
@ConditionalOnProperty(name = "medflow.demo.accounts.enabled", havingValue = "true")
public class DemoAccountBootstrap implements ApplicationRunner {
    private final DemoAccountService accounts;
    private final ObjectProvider<DemoWorkflowService> workflows;

    public DemoAccountBootstrap(DemoAccountService accounts,ObjectProvider<DemoWorkflowService> workflows) {
        this.accounts = accounts;this.workflows = workflows;
    }

    @Override
    public void run(ApplicationArguments args) {
        accounts.initialize();
        workflows.ifAvailable(DemoWorkflowService::initialize);
    }
}

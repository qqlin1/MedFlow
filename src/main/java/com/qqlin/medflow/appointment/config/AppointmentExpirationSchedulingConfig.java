package com.qqlin.medflow.appointment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "medflow.appointment.expiration", name = "enabled", matchIfMissing = true)
public class AppointmentExpirationSchedulingConfig {
}

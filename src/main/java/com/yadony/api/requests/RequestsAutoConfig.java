package com.yadony.api.requests;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RequestsConfig.class, NegotiationProperties.class})
public class RequestsAutoConfig {
}

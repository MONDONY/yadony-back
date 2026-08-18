package com.yadony.api.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MatchingNegotiationConfig.class})
public class MatchingAutoConfig {
}

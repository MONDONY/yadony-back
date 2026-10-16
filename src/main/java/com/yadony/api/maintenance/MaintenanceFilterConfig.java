package com.yadony.api.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.i18n.MessagesResolver;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le filtre est un bean (injecté dans la chaîne de sécurité par {@code SecurityConfig}),
 * mais son enregistrement automatique comme filtre servlet est désactivé : il doit
 * tourner APRÈS l'authentification, jamais avant (voir {@link MaintenanceFilter}).
 */
@Configuration
public class MaintenanceFilterConfig {

    @Bean
    public MaintenanceFilter maintenanceFilter(MaintenanceCache cache, ObjectMapper objectMapper,
                                               MessagesResolver messagesResolver) {
        return new MaintenanceFilter(cache, objectMapper, messagesResolver);
    }

    @Bean
    public FilterRegistrationBean<MaintenanceFilter> maintenanceFilterServletRegistration(MaintenanceFilter filter) {
        FilterRegistrationBean<MaintenanceFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}

package com.yadony.api.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Délais des clients HTTP sortants qui n'en avaient aucun (SMS, email). Un appel
 * sortant sans délai peut garder un thread Tomcat, et la connexion JDBC de sa
 * transaction, aussi longtemps que le fournisseur se tait (YADONY-BACK-STAGING-W).
 */
public final class OutboundHttpTimeouts {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private OutboundHttpTimeouts() {
    }

    public static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}

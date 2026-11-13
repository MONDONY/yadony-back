package com.yadony.api.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * Activation explicite de la messagerie avant réservation, par requête.
 *
 * <p>Les conversations {@link ConversationKind#INQUIRY} n'ont pas de {@code bidId} : une app déjà
 * publiée qui lit {@code bidId} comme obligatoire casserait toute sa messagerie en en recevant une.
 * Seules les apps qui savent les afficher l'annoncent par l'en-tête {@value #HEADER}, une liste
 * séparée par des virgules contenant {@value #INQUIRY}. Sans lui, ces fils n'existent pas.
 */
@Component
public class InquiryCapability {

    public static final String HEADER = "X-Dony-Capabilities";
    public static final String INQUIRY = "inquiry";

    /** Vrai si la requête courante annonce la capacité ; faux hors requête HTTP. */
    public boolean enabled() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        String header = request.getHeader(HEADER);
        return header != null && Arrays.stream(header.split(","))
                .map(String::trim)
                .anyMatch(INQUIRY::equalsIgnoreCase);
    }
}

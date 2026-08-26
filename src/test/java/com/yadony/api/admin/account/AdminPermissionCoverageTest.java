package com.yadony.api.admin.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Critere d'acceptation n°2 de la feature RBAC : <b>toute permission declaree est consommee
 * par au moins un endpoint</b>. Plus aucune permission morte.
 *
 * <p>Ce test ne lit pas les fichiers source — il interroge les endpoints REELLEMENT publies
 * par le contexte Spring, puis leurs expressions {@code @PreAuthorize}. Une permission
 * ajoutee a l'enum sans etre cablee fera donc rougir la suite, en se nommant.
 *
 * <p>⚠️ Les deux niveaux d'annotation sont collectes, methode ET classe : une
 * {@code @PreAuthorize} de methode <b>remplace</b> celle de la classe
 * ({@code UniqueSecurityAnnotationScanner}), donc l'expression effective d'un endpoint peut
 * venir de l'une ou de l'autre. Ne regarder qu'un seul niveau donnerait de faux morts.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminPermissionCoverageTest — aucune permission morte")
class AdminPermissionCoverageTest {

    // Qualifie explicitement : l'actuator publie son propre RequestMappingHandlerMapping.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** Toutes les expressions @PreAuthorize atteignables, methode et classe confondues. */
    private Set<String> allPreAuthorizeExpressions() {
        return handlerMapping.getHandlerMethods().values().stream()
                .flatMap(this::expressionsOf)
                .collect(Collectors.toSet());
    }

    private Stream<String> expressionsOf(HandlerMethod handlerMethod) {
        return Stream.of(
                        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class),
                        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class))
                .filter(Objects::nonNull)
                .map(PreAuthorize::value);
    }

    @Test
    @DisplayName("Chaque valeur d'AdminPermission est referencee par au moins un endpoint")
    void everyPermissionIsConsumedByAtLeastOneEndpoint() {
        Set<String> expressions = allPreAuthorizeExpressions();

        List<String> dead = Arrays.stream(AdminPermission.values())
                .map(Enum::name)
                // Les quotes encadrantes evitent qu'une permission soit consideree couverte
                // parce qu'une AUTRE, plus longue, la contient comme prefixe
                // (USER_VIEW / USER_VIEW_SOMETHING).
                .filter(name -> expressions.stream().noneMatch(e -> e.contains("'" + name + "'")))
                .toList();

        assertThat(dead)
                .as("Permissions declarees mais consommees par aucun endpoint : %s. "
                        + "Les cabler, ou documenter une exemption justifiee — jamais les "
                        + "supprimer en silence.", dead)
                .isEmpty();
    }

    @Test
    @DisplayName("Aucune expression @PreAuthorize ne cite une permission qui n'existe pas")
    void noEndpointReferencesAnUnknownPermission() {
        Set<String> known = Arrays.stream(AdminPermission.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        // hasAuthority('X') : on extrait X et on verifie qu'il correspond a une vraie valeur.
        // Un renommage d'enum sans mise a jour de l'annotation fermerait la route a tout le
        // monde, en silence — c'est exactement ce que ce test rend bruyant.
        List<String> unknown = allPreAuthorizeExpressions().stream()
                .flatMap(expression -> Arrays.stream(expression.split("hasAuthority\\('")).skip(1))
                .map(fragment -> fragment.substring(0, fragment.indexOf('\'')))
                .distinct()
                .filter(authority -> !known.contains(authority))
                .toList();

        assertThat(unknown)
                .as("Expressions @PreAuthorize citant une permission inconnue : %s", unknown)
                .isEmpty();
    }
}

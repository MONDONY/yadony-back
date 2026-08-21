package com.yadony.api.config;

import com.yadony.api.auth.FirebaseTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final FirebaseTokenFilter firebaseTokenFilter;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private List<String> allowedOrigins;

    public SecurityConfig(FirebaseTokenFilter firebaseTokenFilter, ObjectMapper objectMapper) {
        this.firebaseTokenFilter = firebaseTokenFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Internal-Secret", "X-Device-Id", "X-Bootstrap-Secret"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Exception au permitAll sur /auth/** ci-dessous, déclarée AVANT lui car
                // Spring Security applique la première règle qui matche. Cet endpoint
                // MUTE le compte (il y rattache une adresse) : l'authentification doit
                // être portée par la chaîne de sécurité, pas par une garde écrite dans
                // le controller, qu'un refactor pourrait retirer sans aucun signal.
                .requestMatchers("/auth/email-otp/attach").authenticated()
                .requestMatchers("/auth/sms-otp/attach").authenticated()
                .requestMatchers(
                    "/auth/**",
                    "/actuator/health",
                    "/actuator/info",
                    // /actuator/prometheus is scraped by the internal monitoring stack
                    // (Prometheus/Alloy) over the private Docker network `yadony_internal`.
                    // It is NOT publicly exposed: the API port 8080 is never published to
                    // the host, and Nginx returns 404 for /api/v1/actuator/* (except health).
                    // permitAll here only enables the internal scrape; network-level
                    // isolation (no host port + Nginx edge block) prevents public access.
                    "/actuator/prometheus",
                    "/config/**",
                    "/kyc/webhook",
                    "/payments/webhook",
                    "/payments/stripe/webhook",
                    "/ratings/recipient",
                    "/ratings/user/**",
                    // /tracking/search n'est PAS public : il expose le statut du colis et
                    // les instructions de retrait (adresse physique du voyageur). Il exige
                    // une authentification et le service vérifie que l'appelant est
                    // l'expéditeur ou le voyageur du colis. Le destinataire, lui, passe par
                    // /tracking/public/** qui s'authentifie par le token de suivi.
                    "/tracking/public/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/dev/**",
                    // Internal messaging notify: kept in permitAll because the caller (Firebase Functions)
                    // does not carry a Firebase user token. Security is enforced at the controller level
                    // via constant-time comparison of X-Internal-Secret header (option b from the fix spec).
                    "/internal/messaging/notify",
                    // Stripe redirige ici après onboarding — pas de token Firebase (browser Stripe)
                    "/payments/onboarding/return",
                    "/payments/onboarding/refresh",
                    // Public traveler profile: list active/full announcements without auth
                    "/travelers/*/announcements",
                    // MM webhooks: no Firebase token (provider-to-server call). Security is
                    // enforced via HMAC signature verification in MobileMoneyPaymentService.
                    "/webhooks/mobile-money/**",
                    // Public shareable traveler profile (minimal, no-auth)
                    "/public/**",
                    // Alias court de /public/annonce/{id}, pensé pour l'URL visible par le
                    // public (yadony.com/annonce/{id} plutôt que .../api/v1/public/annonce/{id}).
                    // Même donnée, même contrôleur, aucune capacité nouvelle : c'est un
                    // deuxième chemin vers la page déjà couverte par /public/** ci-dessus.
                    "/annonce/**",
                    // Admin bootstrap: initial configuration without auth
                    "/admin/bootstrap",
                    // Sentry webhook: no Firebase token (server-to-server call). Security is
                    // enforced via HMAC signature verification in AdminSentryWebhookController.
                    "/admin/sentry-webhook"
                ).permitAll()
                // ── Invités (session Firebase anonyme) ──────────────────────────────
                // Modèle FERMÉ PAR DÉFAUT : on énumère ce qu'un invité peut faire, et
                // tout le reste lui est refusé par la dernière règle. Un contrôleur
                // ajouté demain sans y penser est donc inaccessible aux invités, au
                // lieu de leur être ouvert : l'oubli devient sûr.
                //
                // NE JAMAIS remplacer ceci par des @PreAuthorize sur les contrôleurs
                // d'engagement : ce serait revenir à un modèle ouvert par défaut.
                //
                // Ces chemins sont volontairement laissés en `authenticated()` et non en
                // `hasAnyRole("GUEST", "SENDER", ...)`. Ils étaient déjà joignables par
                // tout authentifié ; les réécrire en liste de rôles fermerait la
                // recherche aux comptes à autorités vides, c'est-à-dire au tunnel
                // d'inscription. La liste blanche ouvre aux invités, elle ne doit rien
                // fermer à personne. Le tri fin par rôle reste porté par les
                // @PreAuthorize de chaque méthode, inchangés hormis l'ajout de 'GUEST'.
                .requestMatchers(HttpMethod.GET,
                    "/announcements",
                    "/announcements/*",
                    "/package-requests",
                    "/package-requests/*",
                    "/favorites/ids",
                    "/favorites/trips",
                    "/favorites/package-requests",
                    "/me/corridor-alerts"
                ).authenticated()
                // L'ajout d'un favori est un PUT (toggle-on), pas un POST.
                .requestMatchers(HttpMethod.PUT, "/favorites/*/*", "/me/corridor-alerts/*")
                    .authenticated()
                .requestMatchers(HttpMethod.POST, "/me/corridor-alerts")
                    .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/favorites/*/*", "/me/corridor-alerts/*")
                    .authenticated()
                // Tout le reste : authentifié ET non-invité. `authenticated()` seul ne
                // suffirait pas, un token anonyme étant parfaitement authentifié.
                // `hasRole('GUEST')` teste l'autorité `ROLE_GUEST` : le préfixe est ajouté
                // par Spring Security, il ne doit pas être écrit ici.
                .anyRequest().access(new WebExpressionAuthorizationManager(
                    "isAuthenticated() and !hasRole('GUEST')"))
            )
            .addFilterBefore(firebaseTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                // 401 — missing or invalid token
                .authenticationEntryPoint((request, response, authException) -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.UNAUTHORIZED, "Authentication required");
                    problem.setType(URI.create("https://yadony.app/errors/unauthorized"));
                    problem.setTitle("Unauthorized");

                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), problem);
                })
                // 403 — authenticated but not enough permissions
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.FORBIDDEN, "Access denied");
                    problem.setType(URI.create("https://yadony.app/errors/forbidden"));
                    problem.setTitle("Forbidden");

                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), problem);
                })
            );

        return http.build();
    }
}

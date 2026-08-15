package com.yadony.api.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.service-account-path:}")
    private String serviceAccountPath;

    @Bean
    public Firestore firestore() {
        // La seule question qui vaille est « Firebase est-il initialisé ? », pas
        // « par quel mécanisme ». Tester aussi serviceAccountPath annulait le bean
        // en staging et en prod, où l'initialisation passe par
        // GOOGLE_APPLICATION_CREDENTIALS et où cette propriété est donc vide :
        // le backend perdait Firestore alors que Firebase tournait, et
        // FirestoreService retombait sur ses branches « disabled » sans rien casser
        // de visible. Conséquences silencieuses : documents de conversation jamais
        // créés (donc Cloud Function incapable de router les non-lus), aperçus de
        // dernier message figés, messages système absents.
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("Firestore bean unavailable — Firebase not initialized (test/ci mode)");
            return null;
        }
        return FirestoreClient.getFirestore();
    }

    @Bean
    public FirebaseAuth firebaseAuth() {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("FirebaseAuth bean unavailable — Firebase not initialized (test/ci mode)");
            return null;
        }
        return FirebaseAuth.getInstance();
    }

    @PostConstruct
    public void initializeFirebase() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }

        String googleAppCreds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        boolean usingEnvVar = googleAppCreds != null && !googleAppCreds.isBlank();

        if (!usingEnvVar && serviceAccountPath.isBlank()) {
            log.warn("Firebase service account path is empty and GOOGLE_APPLICATION_CREDENTIALS is not set — Firebase disabled (test/ci mode)");
            return;
        }

        try {
            GoogleCredentials credentials;
            if (usingEnvVar) {
                // Production path: credentials file referenced by env var, outside the JAR
                credentials = GoogleCredentials.getApplicationDefault();
                log.info("Firebase initialized via GOOGLE_APPLICATION_CREDENTIALS");
            } else {
                // Dev-only path: credentials bundled as classpath resource.
                // NEVER use this in production — the private key must not be inside the JAR.
                log.warn("[SECURITY] Firebase loading credentials from classpath resource '{}'. " +
                        "Set GOOGLE_APPLICATION_CREDENTIALS env var before deploying to production.",
                        serviceAccountPath);
                String path = serviceAccountPath.replaceFirst("^classpath:", "");
                InputStream stream = new ClassPathResource(path).getInputStream();
                credentials = GoogleCredentials.fromStream(stream);
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
        } catch (IOException e) {
            log.error("Failed to initialize Firebase: {}", e.getMessage());
            throw new IllegalStateException("Firebase initialization failed", e);
        }
    }

    private String getProjectId(String path) {
        try {
            InputStream stream = new ClassPathResource(path).getInputStream();
            byte[] bytes = stream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            int idx = content.indexOf("\"project_id\"");
            if (idx == -1) return "unknown";
            int start = content.indexOf("\"", idx + 13) + 1;
            int end = content.indexOf("\"", start);
            return content.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }
}

package com.yadony.api.admin;

import com.google.firebase.ErrorCode;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Parameter;
import com.google.firebase.remoteconfig.ParameterGroup;
import com.google.firebase.remoteconfig.ParameterValue;
import com.google.firebase.remoteconfig.ParameterValueType;
import com.google.firebase.remoteconfig.RemoteConfigErrorCode;
import com.google.firebase.remoteconfig.Template;
import com.yadony.api.admin.dto.AppUpdateResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.RemoteConfigGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lit et change {@code recommended_build} dans Firebase Remote Config : les applications deja
 * installees lisent CETTE cle (pas le back) pour afficher le bandeau « Nouvelle version
 * disponible ». {@code min_supported_build} (verrou de version minimale) n'est jamais ecrit.
 *
 * <p>L'ecriture lit le template, ne touche que ce parametre et republie avec l'ETag lu : un
 * changement concurrent fait echouer la publication (409) au lieu d'etre ecrase.
 */
@Service
public class AppUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateService.class);

    static final String RECOMMENDED_BUILD_KEY = "recommended_build";
    static final String MIN_SUPPORTED_BUILD_KEY = "min_supported_build";
    static final int MAX_BUILD = 100_000;

    /** Identifiant stable pour audit_log, qui exige un UUID alors que la cle n'a pas de ligne. */
    static final UUID AUDIT_ENTITY_ID =
            UUID.nameUUIDFromBytes("remote_config:recommended_build".getBytes(StandardCharsets.UTF_8));

    private final RemoteConfigGateway gateway;
    private final AuditService auditService;

    public AppUpdateService(RemoteConfigGateway gateway, AuditService auditService) {
        this.gateway = gateway;
        this.auditService = auditService;
    }

    public AppUpdateResponse read() {
        return toResponse(fetch());
    }

    public AppUpdateResponse updateRecommendedBuild(Integer newBuild, UUID adminId) {
        if (newBuild == null || newBuild < 0 || newBuild > MAX_BUILD) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "app-update-invalid",
                    "Unprocessable Entity",
                    "Le build recommande doit etre un entier compris entre 0 et " + MAX_BUILD);
        }
        Template template = fetch();
        int oldBuild = intValue(findParameter(template, RECOMMENDED_BUILD_KEY));

        applyRecommendedBuild(template, newBuild);

        Template published;
        try {
            published = gateway.publish(template);
        } catch (FirebaseRemoteConfigException e) {
            throw translate(e);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", RECOMMENDED_BUILD_KEY);
        payload.put("oldValue", oldBuild);
        payload.put("newValue", newBuild);
        auditService.log("remote_config", AUDIT_ENTITY_ID, "APP_RECOMMENDED_BUILD_CHANGED", adminId, payload);

        return toResponse(published != null ? published : template);
    }

    private Template fetch() {
        try {
            return gateway.fetch();
        } catch (FirebaseRemoteConfigException e) {
            throw translate(e);
        }
    }

    /** Ne modifie que {@code recommended_build} ; tout le reste du template reste tel quel. */
    private static void applyRecommendedBuild(Template template, int newBuild) {
        Parameter parameter = findParameter(template, RECOMMENDED_BUILD_KEY);
        if (parameter == null) {
            parameter = new Parameter();
            Map<String, Parameter> parameters = new HashMap<>(template.getParameters());
            parameters.put(RECOMMENDED_BUILD_KEY, parameter);
            template.setParameters(parameters);
        }
        parameter.setValueType(ParameterValueType.NUMBER)
                .setDefaultValue(ParameterValue.of(String.valueOf(newBuild)));
    }

    /** Cherche le parametre en haut du template, puis dans les groupes (la console peut l'y ranger). */
    private static Parameter findParameter(Template template, String key) {
        Parameter top = template.getParameters().get(key);
        if (top != null) {
            return top;
        }
        for (ParameterGroup group : template.getParameterGroups().values()) {
            Parameter inGroup = group.getParameters().get(key);
            if (inGroup != null) {
                return inGroup;
            }
        }
        return null;
    }

    private static AppUpdateResponse toResponse(Template template) {
        return new AppUpdateResponse(
                intValue(findParameter(template, RECOMMENDED_BUILD_KEY)),
                intValue(findParameter(template, MIN_SUPPORTED_BUILD_KEY)));
    }

    /** 0 si le parametre manque, n'a pas de valeur explicite ou n'est pas un entier. */
    private static int intValue(Parameter parameter) {
        if (parameter == null || !(parameter.getDefaultValue() instanceof ParameterValue.Explicit explicit)) {
            return 0;
        }
        try {
            return Integer.parseInt(explicit.getValue().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static YadonyBusinessException translate(FirebaseRemoteConfigException e) {
        if (isEtagConflict(e)) {
            return new YadonyBusinessException(HttpStatus.CONFLICT, "app-update-conflict", "Conflict",
                    "La configuration a changé entre-temps, réessaie.");
        }
        log.warn("Firebase Remote Config indisponible : code={} remoteCode={}",
                e.getErrorCode(), e.getRemoteConfigErrorCode());
        return new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "remote-config-unavailable",
                "Service Unavailable",
                "Firebase Remote Config est injoignable ou le compte de service n'a pas le droit de publier.");
    }

    private static boolean isEtagConflict(FirebaseRemoteConfigException e) {
        if (e.getRemoteConfigErrorCode() == RemoteConfigErrorCode.VERSION_MISMATCH) {
            return true;
        }
        ErrorCode code = e.getErrorCode();
        if (code == ErrorCode.ABORTED || code == ErrorCode.CONFLICT) {
            return true;
        }
        return code == ErrorCode.FAILED_PRECONDITION
                && e.getHttpResponse() != null && e.getHttpResponse().getStatusCode() == 412;
    }
}

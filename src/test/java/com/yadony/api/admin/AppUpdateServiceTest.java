package com.yadony.api.admin;

import com.google.firebase.ErrorCode;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Parameter;
import com.google.firebase.remoteconfig.ParameterValue;
import com.google.firebase.remoteconfig.RemoteConfigErrorCode;
import com.google.firebase.remoteconfig.Template;
import com.yadony.api.admin.dto.AppUpdateResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.RemoteConfigGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("AppUpdateService — recommended_build dans Remote Config")
class AppUpdateServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();

    private static final String TEMPLATE_JSON = """
            {
              "conditions": [{"name": "ios_only", "expression": "device.os == 'ios'"}],
              "parameters": {
                "min_supported_build": {"defaultValue": {"value": "42"}, "valueType": "NUMBER"},
                "recommended_build": {"defaultValue": {"value": "50"}, "valueType": "NUMBER"},
                "welcome_text": {"defaultValue": {"value": "Salut"}, "valueType": "STRING",
                                 "conditionalValues": {"ios_only": {"value": "Salut iOS"}}}
              },
              "parameterGroups": {
                "promo": {"description": "g", "parameters": {"promo_on": {"defaultValue": {"value": "true"}}}}
              }
            }
            """;

    /** Faux client : sert un template en memoire, garde ce qui est publie, peut echouer. */
    static class FakeGateway implements RemoteConfigGateway {
        Template current;
        Template published;
        FirebaseRemoteConfigException fetchError;
        FirebaseRemoteConfigException publishError;

        FakeGateway(Template current) {
            this.current = current;
        }

        @Override
        public Template fetch() throws FirebaseRemoteConfigException {
            if (fetchError != null) {
                throw fetchError;
            }
            return current;
        }

        @Override
        public Template publish(Template template) throws FirebaseRemoteConfigException {
            if (publishError != null) {
                throw publishError;
            }
            published = template;
            return template;
        }
    }

    private FakeGateway gateway;
    private AuditService audit;
    private AppUpdateService service;

    private static Template template(String json) throws Exception {
        return Template.fromJSON(json);
    }

    private static FirebaseRemoteConfigException error(ErrorCode code, RemoteConfigErrorCode remote) {
        return new FirebaseRemoteConfigException(code, "boom", null, null, remote);
    }

    @BeforeEach
    void setUp() throws Exception {
        gateway = new FakeGateway(template(TEMPLATE_JSON));
        audit = mock(AuditService.class);
        service = new AppUpdateService(gateway, audit);
    }

    @Test
    @DisplayName("read — lit recommended_build et min_supported_build")
    void read() {
        assertThat(service.read()).isEqualTo(new AppUpdateResponse(50, 42));
    }

    @Test
    @DisplayName("read — 0 si les cles manquent")
    void readMissing() throws Exception {
        gateway.current = template("{\"parameters\": {}}");
        assertThat(service.read()).isEqualTo(new AppUpdateResponse(0, 0));
    }

    @Test
    @DisplayName("read — 0 si la valeur n'est pas un entier ou est la valeur par defaut de l'app")
    void readNonNumeric() throws Exception {
        gateway.current = template("""
                {"parameters": {
                  "recommended_build": {"defaultValue": {"value": "abc"}},
                  "min_supported_build": {"defaultValue": {"useInAppDefault": true}}
                }}""");
        assertThat(service.read()).isEqualTo(new AppUpdateResponse(0, 0));
    }

    @Test
    @DisplayName("read — retrouve un parametre range dans un groupe")
    void readInGroup() throws Exception {
        gateway.current = template("""
                {"parameterGroups": {"g": {"parameters": {
                  "recommended_build": {"defaultValue": {"value": "77"}}}}}}""");
        assertThat(service.read().recommendedBuild()).isEqualTo(77);
    }

    @Test
    @DisplayName("update — change uniquement recommended_build et conserve le reste")
    void updatePreservesOthers() {
        AppUpdateResponse response = service.updateRecommendedBuild(60, ADMIN);

        assertThat(response).isEqualTo(new AppUpdateResponse(60, 42));
        Template published = gateway.published;
        assertThat(published).isSameAs(gateway.current);
        assertThat(published.getParameters()).containsOnlyKeys(
                "min_supported_build", "recommended_build", "welcome_text");
        Parameter welcome = published.getParameters().get("welcome_text");
        assertThat(((ParameterValue.Explicit) welcome.getDefaultValue()).getValue()).isEqualTo("Salut");
        assertThat(welcome.getConditionalValues()).containsKey("ios_only");
        assertThat(published.getConditions()).hasSize(1);
        assertThat(published.getParameterGroups()).containsKey("promo");
        Parameter min = published.getParameters().get("min_supported_build");
        assertThat(((ParameterValue.Explicit) min.getDefaultValue()).getValue()).isEqualTo("42");
    }

    @Test
    @DisplayName("update — cree le parametre NUMBER s'il manque")
    void updateCreatesMissing() throws Exception {
        gateway.current = template("{\"parameters\": {\"x\": {\"defaultValue\": {\"value\": \"1\"}}}}");

        AppUpdateResponse response = service.updateRecommendedBuild(5, ADMIN);

        assertThat(response.recommendedBuild()).isEqualTo(5);
        assertThat(gateway.published.getParameters()).containsOnlyKeys("x", "recommended_build");
        Parameter created = gateway.published.getParameters().get("recommended_build");
        assertThat(created.getValueType().name()).isEqualTo("NUMBER");
        assertThat(((ParameterValue.Explicit) created.getDefaultValue()).getValue()).isEqualTo("5");
    }

    @Test
    @DisplayName("update — modifie le parametre range dans un groupe, sans en creer un second")
    void updateInGroup() throws Exception {
        gateway.current = template("""
                {"parameterGroups": {"g": {"parameters": {
                  "recommended_build": {"defaultValue": {"value": "77"}}}}}}""");

        service.updateRecommendedBuild(80, ADMIN);

        assertThat(gateway.published.getParameters()).isEmpty();
        assertThat(service.read().recommendedBuild()).isEqualTo(80);
    }

    @Test
    @DisplayName("update — audite ancienne et nouvelle valeur avec l'acteur")
    @SuppressWarnings("unchecked")
    void updateAudits() {
        service.updateRecommendedBuild(60, ADMIN);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("remote_config"), eq(AppUpdateService.AUDIT_ENTITY_ID),
                eq("APP_RECOMMENDED_BUILD_CHANGED"), eq(ADMIN), payload.capture());
        assertThat(payload.getValue()).containsEntry("key", "recommended_build")
                .containsEntry("oldValue", 50).containsEntry("newValue", 60);
    }

    @Test
    @DisplayName("update — bornes : 0 et 100000 acceptes, -1, 100001 et null refuses en 422 sans rien publier")
    void updateBounds() {
        service.updateRecommendedBuild(0, ADMIN);
        service.updateRecommendedBuild(100_000, ADMIN);

        for (Integer bad : new Integer[]{-1, 100_001, null}) {
            assertThatThrownBy(() -> service.updateRecommendedBuild(bad, ADMIN))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus().value()).isEqualTo(422);
                        assertThat(ex.getMessage()).contains("entier").contains("100000");
                    });
        }
    }

    @Test
    @DisplayName("update — conflit d'ETag (VERSION_MISMATCH) -> 409, ni publication ni audit")
    void updateConflict() {
        gateway.publishError = error(ErrorCode.FAILED_PRECONDITION, RemoteConfigErrorCode.VERSION_MISMATCH);

        assertThatThrownBy(() -> service.updateRecommendedBuild(60, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("La configuration a changé entre-temps, réessaie.");
                });
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("update — ABORTED est aussi un conflit")
    void updateAborted() {
        gateway.publishError = error(ErrorCode.ABORTED, null);
        assertThatThrownBy(() -> service.updateRecommendedBuild(60, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("update — permission refusee -> 503 en francais, jamais de 500")
    void updatePermissionDenied() {
        gateway.publishError = error(ErrorCode.PERMISSION_DENIED, null);

        assertThatThrownBy(() -> service.updateRecommendedBuild(60, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                    assertThat(ex.getMessage()).isEqualTo(
                            "Firebase Remote Config est injoignable ou le compte de service n'a pas le droit de publier.");
                });
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("read — erreur Firebase a la lecture -> 503")
    void readError() {
        gateway.fetchError = error(ErrorCode.UNAVAILABLE, null);
        assertThatThrownBy(() -> service.read())
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(503));
    }

    @Test
    @DisplayName("update — erreur Firebase a la lecture -> 503, rien n'est publie")
    void updateFetchError() {
        gateway.fetchError = error(ErrorCode.UNAUTHENTICATED, null);
        assertThatThrownBy(() -> service.updateRecommendedBuild(60, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(503));
        assertThat(gateway.published).isNull();
    }
}

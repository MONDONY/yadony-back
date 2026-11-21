package com.yadony.api.promo;

import com.yadony.api.admin.AdminPromoController;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.promo.dto.CreatePromoRequest;
import com.yadony.api.promo.dto.UpdatePromoStatusRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPromoControllerTest {

    @Mock PromoCodeRepository promoCodeRepository;
    @Mock AuditService auditService;
    @Mock UserRepository userRepository;

    private AdminPromoController controller() {
        return new AdminPromoController(promoCodeRepository, auditService, userRepository);
    }

    private PromoCodeEntity savedPromo(String code, BigDecimal rate) {
        PromoCodeEntity p = new PromoCodeEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setCode(code);
        p.setRate(rate);
        p.setTarget(PromoCodeTarget.ANY);
        p.setStatus(PromoCodeStatus.ACTIVE);
        p.setPerUserLimit(1);
        return p;
    }

    @Test
    @DisplayName("create: code unique → 201 + entity sauvegardée")
    void create_newCode_returns201() {
        when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.empty());
        PromoCodeEntity saved = savedPromo("WELCOME10", new BigDecimal("0.06"));
        when(promoCodeRepository.save(any())).thenReturn(saved);

        var req = new CreatePromoRequest("WELCOME10", new BigDecimal("0.06"), PromoCodeTarget.ANY,
                null, null, null, null);
        var resp = controller().create(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().code()).isEqualTo("WELCOME10");
        assertThat(resp.getBody().rate()).isEqualByComparingTo("0.06");
        verify(promoCodeRepository).save(any(PromoCodeEntity.class));
    }

    @Test
    @DisplayName("create: code déjà existant → 409 promo-code-exists")
    void create_duplicateCode_throws409() {
        PromoCodeEntity existing = savedPromo("WELCOME10", new BigDecimal("0.06"));
        when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(existing));

        var req = new CreatePromoRequest("WELCOME10", new BigDecimal("0.06"), null, null, null, null, null);

        assertThatThrownBy(() -> controller().create(req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-code-exists"));
    }

    @Test
    @DisplayName("list: retourne toutes les entités")
    void list_returnsAll() {
        when(promoCodeRepository.findAll()).thenReturn(List.of(
                savedPromo("A", new BigDecimal("0.06")),
                savedPromo("B", new BigDecimal("0.08"))
        ));

        var list = controller().list(null);
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("updateStatus: DISABLED → enregistré")
    void updateStatus_disabled() {
        PromoCodeEntity p = savedPromo("WELCOME10", new BigDecimal("0.06"));
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);

        var resp = controller().updateStatus(id, new UpdatePromoStatusRequest(PromoCodeStatus.DISABLED));

        assertThat(resp.getBody().status()).isEqualTo(PromoCodeStatus.DISABLED);
    }

    @Test
    @DisplayName("delete: soft delete + audit")
    void delete_softDeletes() {
        PromoCodeEntity p = savedPromo("WELCOME10", new BigDecimal("0.06"));
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);

        controller().delete(id);

        assertThat(p.getDeletedAt()).isNotNull();
        verify(auditService).log(eq("PROMO"), eq(id), eq("PROMO_CODE_DELETED"), any(), any());
    }

    @Test
    @DisplayName("DTO: taux négatif rejeté par bean validation")
    void createPromoRequest_negativeRate_isRejected() {
        try (var f = Validation.buildDefaultValidatorFactory()) {
            Validator v = f.getValidator();
            var req = new CreatePromoRequest("X", new BigDecimal("-0.01"), null, null, null, null, null);
            assertThat(v.validate(req)).isNotEmpty();
        }
    }

    private PromoCodeEntity owned(String code, PromoCodeOrigin origin, UUID owner) {
        PromoCodeEntity p = savedPromo(code, new BigDecimal("0.06"));
        p.setOrigin(origin);
        p.setOwnerUserId(owner);
        return p;
    }

    private UserEntity user(UUID id, String first) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(first);
        u.setLastName("Secret");
        return u;
    }

    private void assertReadonly(Throwable e) {
        assertThat(e).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException b = (YadonyBusinessException) e;
        assertThat(b.getErrorCode()).isEqualTo("promo-code-readonly");
        assertThat(b.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("list: origine et prénom du propriétaire, jamais le nom, chargés en une requête")
    void list_exposesOriginAndFirstName_singleUserQuery() {
        UUID o1 = UUID.randomUUID();
        UUID o2 = UUID.randomUUID();
        when(promoCodeRepository.findAll()).thenReturn(List.of(
                owned("R1", PromoCodeOrigin.REFERRAL_REWARD, o1),
                owned("R2", PromoCodeOrigin.REFERRAL_WELCOME, o2),
                owned("R3", PromoCodeOrigin.REFERRAL_REWARD, o1),
                savedPromo("G", new BigDecimal("0.06"))));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(o1, " Awa "), user(o2, null)));

        var list = controller().list(null);

        assertThat(list).extracting("ownerFirstName").containsExactly("Awa", null, "Awa", null);
        assertThat(list.get(0).origin()).isEqualTo(PromoCodeOrigin.REFERRAL_REWARD);
        assertThat(list.get(3).origin()).isEqualTo(PromoCodeOrigin.ADMIN);
        assertThat(list.toString()).doesNotContain("Secret");
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("list: origin=REFERRAL ne charge que les codes de parrainage")
    void list_filterReferral() {
        when(promoCodeRepository.findByOriginIn(List.of(PromoCodeOrigin.REFERRAL_REWARD, PromoCodeOrigin.REFERRAL_WELCOME)))
                .thenReturn(List.of());
        assertThat(controller().list(AdminPromoController.OriginFilter.REFERRAL)).isEmpty();
        verify(promoCodeRepository, never()).findAll();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("list: origin=ADMIN ne charge que les codes admin")
    void list_filterAdmin() {
        when(promoCodeRepository.findByOriginIn(List.of(PromoCodeOrigin.ADMIN)))
                .thenReturn(List.of(savedPromo("A", new BigDecimal("0.06"))));
        assertThat(controller().list(AdminPromoController.OriginFilter.ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("update d'un code de parrainage → 409 promo-code-readonly")
    void update_referralCodes_409() {
        for (PromoCodeOrigin o : List.of(PromoCodeOrigin.REFERRAL_REWARD, PromoCodeOrigin.REFERRAL_WELCOME)) {
            PromoCodeEntity p = owned("R", o, UUID.randomUUID());
            UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
            var req = new com.yadony.api.promo.dto.UpdatePromoRequest(new BigDecimal("0.1"), null, null, null, null, null);
            assertThatThrownBy(() -> controller().update(id, req)).satisfies(this::assertReadonly);
        }
        verify(promoCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete d'un code de parrainage → 409 promo-code-readonly")
    void delete_referralCodes_409() {
        for (PromoCodeOrigin o : List.of(PromoCodeOrigin.REFERRAL_REWARD, PromoCodeOrigin.REFERRAL_WELCOME)) {
            PromoCodeEntity p = owned("R", o, UUID.randomUUID());
            UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
            assertThatThrownBy(() -> controller().delete(id)).satisfies(this::assertReadonly);
            assertThat(p.getDeletedAt()).isNull();
        }
    }

    @Test
    @DisplayName("update d'un code ADMIN : toujours permis")
    void update_adminCode_ok() {
        PromoCodeEntity p = savedPromo("A", new BigDecimal("0.06"));
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);
        var req = new com.yadony.api.promo.dto.UpdatePromoRequest(new BigDecimal("0.1"), null, null, null, null, null);
        assertThat(controller().update(id, req).getBody().rate()).isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("setStatus d'un code de parrainage : permis")
    void setStatus_referralCode_ok() {
        UUID owner = UUID.randomUUID();
        PromoCodeEntity p = owned("R", PromoCodeOrigin.REFERRAL_WELCOME, owner);
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);
        when(userRepository.findById(owner)).thenReturn(Optional.of(user(owner, "Awa")));

        var resp = controller().updateStatus(id, new UpdatePromoStatusRequest(PromoCodeStatus.DISABLED));

        assertThat(resp.getBody().status()).isEqualTo(PromoCodeStatus.DISABLED);
        assertThat(resp.getBody().ownerFirstName()).isEqualTo("Awa");
    }
}

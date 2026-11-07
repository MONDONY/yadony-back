package com.yadony.api.admin.broadcast;

import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BroadcastTarget — normalisation et validation")
class BroadcastTargetTest {

    private static final AudienceRule.Group RULE = new AudienceRule.Group(AudienceRule.Op.AND, List.of());

    @Test
    @DisplayName("RULE sans règle : 422 broadcast-target-invalid")
    void rule_sansRegle_422() {
        assertThatThrownBy(() -> new BroadcastTarget(BroadcastTargetType.RULE, null, null, null, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("type ancien + règle : la règle est écartée")
    void typeAncien_avecRegle_regleEcartee() {
        assertThat(new BroadcastTarget(BroadcastTargetType.SENDERS, null, null, null, RULE).rule()).isNull();
    }

    @Test
    @DisplayName("RULE garde sa règle et vide ville et utilisateur")
    void rule_gardeLaRegle() {
        BroadcastTarget t = new BroadcastTarget(BroadcastTargetType.RULE, "Paris", "Dakar", UUID.randomUUID(), RULE);
        assertThat(t.rule()).isSameAs(RULE);
        assertThat(t.origin()).isNull();
        assertThat(t.userId()).isNull();
    }

    @Test
    @DisplayName("constructeur à quatre arguments = ciblage sans règle")
    void constructeurHeritage() {
        assertThat(new BroadcastTarget(BroadcastTargetType.ALL, null, null, null).rule()).isNull();
    }
}

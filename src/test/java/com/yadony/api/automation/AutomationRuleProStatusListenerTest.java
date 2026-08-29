package com.yadony.api.automation;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomationRuleProStatusListenerTest {

    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @Mock AutomationRuleRepository ruleRepository;

    private AutomationRuleProStatusListener listener() {
        return new AutomationRuleProStatusListener(ruleRepository);
    }

    private AutomationRuleEntity rule(boolean enabled, boolean disabledByDowngrade) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(TRAVELER_ID);
        r.setEnabled(enabled);
        r.setDisabledByDowngrade(disabledByDowngrade);
        return r;
    }

    @Test
    @DisplayName("le downgrade éteint les règles actives et les marque")
    void downgradeDisablesActiveRules() {
        AutomationRuleEntity active = rule(true, false);
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                .thenReturn(List.of(active));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, false));

        assertThat(active.isEnabled()).isFalse();
        assertThat(active.isDisabledByDowngrade()).isTrue();
    }

    @Test
    @DisplayName("le downgrade ne marque pas une règle déjà éteinte par le voyageur")
    void downgradeLeavesUserDisabledRulesUnmarked() {
        AutomationRuleEntity userDisabled = rule(false, false);
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                .thenReturn(List.of(userDisabled));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, false));

        assertThat(userDisabled.isEnabled()).isFalse();
        assertThat(userDisabled.isDisabledByDowngrade())
                .as("une règle éteinte volontairement ne doit pas être rallumée au réabonnement")
                .isFalse();
    }

    @Test
    @DisplayName("le réabonnement rallume uniquement les règles marquées")
    void upgradeRestoresOnlyMarkedRules() {
        AutomationRuleEntity marked = rule(false, true);
        when(ruleRepository.findByTravelerIdAndDisabledByDowngradeTrue(TRAVELER_ID))
                .thenReturn(List.of(marked));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, true));

        assertThat(marked.isEnabled()).isTrue();
        assertThat(marked.isDisabledByDowngrade()).isFalse();
    }
}

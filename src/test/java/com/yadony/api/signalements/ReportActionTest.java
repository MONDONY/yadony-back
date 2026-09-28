package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReportActionTest {

    private static Set<ReportAction> applicableTo(ReportTargetType type) {
        return Arrays.stream(ReportAction.values())
                .filter(a -> a.appliesTo(type))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ReportAction.class)));
    }

    @ParameterizedTest
    @EnumSource(ReportTargetType.class)
    void resolveEtDismiss_s_appliquentATousLesTypes(ReportTargetType type) {
        assertThat(ReportAction.RESOLVE.appliesTo(type)).isTrue();
        assertThat(ReportAction.DISMISS.appliesTo(type)).isTrue();
    }

    @Test
    void user_avertirOuSuspendreLaCible() {
        assertThat(applicableTo(ReportTargetType.USER)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN, ReportAction.SUSPEND_TARGET);
    }

    @Test
    void annonceEtDemande_retirerLeContenu() {
        assertThat(applicableTo(ReportTargetType.ANNOUNCEMENT)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.REMOVE_CONTENT);
        assertThat(applicableTo(ReportTargetType.PACKAGE_REQUEST)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.REMOVE_CONTENT);
    }

    @Test
    void message_supprimerLeMessageOuSanctionnerSonAuteur() {
        assertThat(applicableTo(ReportTargetType.MESSAGE)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.DELETE_MESSAGE,
                ReportAction.WARN_AUTHOR, ReportAction.SUSPEND_AUTHOR);
    }

    @Test
    void avis_exclureSupprimerOuSanctionnerSonAuteur() {
        assertThat(applicableTo(ReportTargetType.RATING)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.EXCLUDE_RATING,
                ReportAction.DELETE_RATING, ReportAction.WARN_AUTHOR, ReportAction.SUSPEND_AUTHOR);
    }

    @Test
    void offre_sanctionnerLaPartieAdverse() {
        assertThat(applicableTo(ReportTargetType.BID)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS, ReportAction.WARN_AUTHOR, ReportAction.SUSPEND_AUTHOR);
    }

    @Test
    void app_seulementTraiterOuRejeter() {
        assertThat(applicableTo(ReportTargetType.APP)).containsExactlyInAnyOrder(
                ReportAction.RESOLVE, ReportAction.DISMISS);
    }

    @Test
    void seulDismiss_rejetteLeSignalement() {
        assertThat(Arrays.stream(ReportAction.values()).filter(a -> a.resultingStatus() == ReportStatus.DISMISSED))
                .containsExactly(ReportAction.DISMISS);
    }

    @Test
    void lesValeursTiennentDansLaColonneActionTaken() {
        // reports.action_taken est un VARCHAR(40), sans CHECK (V164).
        assertThat(ReportAction.values()).allSatisfy(a -> assertThat(a.name().length()).isLessThanOrEqualTo(40));
    }
}

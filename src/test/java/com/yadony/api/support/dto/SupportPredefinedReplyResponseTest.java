package com.yadony.api.support.dto;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.support.SupportPredefinedReplyEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selection de langue de {@link SupportPredefinedReplyResponse#from} (V265) :
 * l'anglais n'est rendu que si la langue demandee est EN ET que la traduction
 * existe, sinon le francais reste le repli.
 */
class SupportPredefinedReplyResponseTest {

    @Test
    void from_rendLAnglaisQuandLaTraductionExiste() {
        SupportPredefinedReplyEntity entity = entityWithTranslation();

        SupportPredefinedReplyResponse response = SupportPredefinedReplyResponse.from(entity, AppLanguage.EN);

        assertThat(response.question()).isEqualTo("When will my refund arrive?");
        assertThat(response.answer()).isEqualTo("Within 5 to 10 business days.");
    }

    @Test
    void from_replieSurLeFrancaisQuandLaTraductionManque() {
        SupportPredefinedReplyEntity entity = entityWithoutTranslation();

        SupportPredefinedReplyResponse response = SupportPredefinedReplyResponse.from(entity, AppLanguage.EN);

        assertThat(response.question()).isEqualTo("Quand arrive mon remboursement ?");
        assertThat(response.answer()).isEqualTo("Comptez 5 a 10 jours ouvres.");
    }

    @Test
    void from_rendLeFrancaisMemeSiUneTraductionExiste() {
        SupportPredefinedReplyEntity entity = entityWithTranslation();

        SupportPredefinedReplyResponse response = SupportPredefinedReplyResponse.from(entity, AppLanguage.FR);

        assertThat(response.question()).isEqualTo("Quand arrive mon remboursement ?");
        assertThat(response.answer()).isEqualTo("Comptez 5 a 10 jours ouvres.");
    }

    @Test
    void from_sansLangueRendLeFrancaisParDefaut() {
        SupportPredefinedReplyEntity entity = entityWithTranslation();

        SupportPredefinedReplyResponse response = SupportPredefinedReplyResponse.from(entity);

        assertThat(response.question()).isEqualTo("Quand arrive mon remboursement ?");
        assertThat(response.answer()).isEqualTo("Comptez 5 a 10 jours ouvres.");
    }

    @Test
    void from_ilNeManqueQuUneTraduction_replieAussiSurLeFrancais() {
        SupportPredefinedReplyEntity entity = entityWithoutTranslation();
        entity.setQuestionEn("When will my refund arrive?");
        // answerEn reste null : la paire question/reponse doit rester coherente.

        SupportPredefinedReplyResponse response = SupportPredefinedReplyResponse.from(entity, AppLanguage.EN);

        assertThat(response.question()).isEqualTo("Quand arrive mon remboursement ?");
        assertThat(response.answer()).isEqualTo("Comptez 5 a 10 jours ouvres.");
    }

    private static SupportPredefinedReplyEntity entityWithTranslation() {
        SupportPredefinedReplyEntity entity = entityWithoutTranslation();
        entity.setQuestionEn("When will my refund arrive?");
        entity.setAnswerEn("Within 5 to 10 business days.");
        return entity;
    }

    private static SupportPredefinedReplyEntity entityWithoutTranslation() {
        SupportPredefinedReplyEntity entity = new SupportPredefinedReplyEntity();
        entity.setCode("payment-refund-delay");
        entity.setCategory("PAYMENT");
        entity.setQuestion("Quand arrive mon remboursement ?");
        entity.setAnswer("Comptez 5 a 10 jours ouvres.");
        entity.setSortOrder(40);
        entity.setActive(true);
        return entity;
    }
}

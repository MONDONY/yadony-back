package com.yadony.api.support.dto;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.support.SupportPredefinedReplyEntity;

public record SupportPredefinedReplyResponse(
        String code,
        String category,
        String question,
        String answer) {

    /** Francais par defaut : usage historique (tests, appelants sans contexte de langue). */
    public static SupportPredefinedReplyResponse from(SupportPredefinedReplyEntity entity) {
        return from(entity, AppLanguage.FR);
    }

    /**
     * Anglais seulement si la langue demandee est EN et que la traduction (V265)
     * existe pour cette ligne ; francais dans tous les autres cas.
     */
    public static SupportPredefinedReplyResponse from(SupportPredefinedReplyEntity entity, AppLanguage language) {
        boolean useEnglish = language == AppLanguage.EN
                && entity.getQuestionEn() != null
                && entity.getAnswerEn() != null;
        return new SupportPredefinedReplyResponse(
                entity.getCode(),
                entity.getCategory(),
                useEnglish ? entity.getQuestionEn() : entity.getQuestion(),
                useEnglish ? entity.getAnswerEn() : entity.getAnswer());
    }
}

package com.yadony.api.support.dto;

import com.yadony.api.support.SupportPredefinedReplyEntity;

public record SupportPredefinedReplyResponse(
        String code,
        String category,
        String question,
        String answer) {

    public static SupportPredefinedReplyResponse from(SupportPredefinedReplyEntity entity) {
        return new SupportPredefinedReplyResponse(
                entity.getCode(),
                entity.getCategory(),
                entity.getQuestion(),
                entity.getAnswer());
    }
}

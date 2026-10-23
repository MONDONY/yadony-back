package com.yadony.api.payments.amendment;

import com.yadony.api.common.BaseEntity;

import java.lang.reflect.Field;
import java.util.UUID;

/** Pose l'identifiant d'une entité sans la persister (champ protégé de {@link BaseEntity}). */
final class BidAmendmentTestSupport {

    private BidAmendmentTestSupport() {
    }

    static void setId(BaseEntity entity, UUID id) {
        try {
            Field f = BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientIsSenderTest {

    private static final String SENDER_PHONE = "+33612345678";

    private static UserEntity sender() {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        return u;
    }

    private static void setId(Object o, UUID id) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(o, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("id introuvable");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BidEntity bid(UUID recipientUserId, String phone, String name) {
        BidEntity b = new BidEntity();
        b.setRecipientUserId(recipientUserId);
        b.setRecipientPhone(phone);
        b.setRecipientName(name);
        return b;
    }

    @Test
    void byUserId_true() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(s.getId(), null, "Autre"), s)).isTrue();
    }

    @Test
    void byUserId_otherAccount_false() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(UUID.randomUUID(), SENDER_PHONE, null), s, SENDER_PHONE)).isFalse();
    }

    @Test
    void byPhone_sameNumberDifferentFormats_true() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, "+33 6 12 34 56 78", null), s, SENDER_PHONE)).isTrue();
        assertThat(RecipientIsSender.of(bid(null, "0033612345678", null), s, SENDER_PHONE)).isTrue();
        assertThat(RecipientIsSender.of(bid(null, "0612345678", null), s, SENDER_PHONE)).isTrue();
        assertThat(RecipientIsSender.of(bid(null, "06 12 34 56 78", null), s, SENDER_PHONE)).isTrue();
    }

    @Test
    void byNameOnly_false() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, "+33 7 99 99 99 99", "Aboubakar"), s, SENDER_PHONE)).isFalse();
    }

    @Test
    void nullRecipientPhoneAndUser_false() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, null, "Moi"), s, SENDER_PHONE)).isFalse();
        assertThat(RecipientIsSender.of(bid(null, "  ", "Moi"), s, SENDER_PHONE)).isFalse();
    }

    @Test
    void differentPhone_false() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, "+33 6 12 34 56 79", null), s, SENDER_PHONE)).isFalse();
        assertThat(RecipientIsSender.of(bid(null, "0612345679", null), s, SENDER_PHONE)).isFalse();
    }

    @Test
    void unknownSenderPhone_false() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, "+33612345678", null), s, null)).isFalse();
        assertThat(RecipientIsSender.of(bid(null, "+33612345678", null), s)).isFalse();
    }

    @Test
    void shortNationalNumber_neverMatches() {
        UserEntity s = sender();
        assertThat(RecipientIsSender.of(bid(null, "01234567", null), s, "+33601234567")).isFalse();
        assertThat(RecipientIsSender.of(bid(null, "0123", null), s, "+33612340123")).isFalse();
    }

    @Test
    void nullBidOrSender_false() {
        assertThat(RecipientIsSender.of(null, sender())).isFalse();
        assertThat(RecipientIsSender.of(bid(null, null, null), null)).isFalse();
    }
}

package com.yadony.api.config;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementSearchMapper;
import com.yadony.api.notifications.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D — un reglage modifie depuis le back-office doit changer ce que le serveur
 * <b>APPLIQUE</b>, pas seulement ce qu'il affiche.
 *
 * <p>{@code ConfigControllerPlatformSettingsIT} verrouille deja la lecture publique : les
 * quatre endpoints suivent la table. Mais suivre la table cote lecture ne prouve rien sur le
 * comportement — c'est exactement l'ecart que ces tests ferment. Un taux de commission
 * modifie qui ne changerait que l'affichage ferait annoncer un montant a l'expediteur et en
 * prelever un autre chez Stripe ; un {@code sms_enabled} qui ne changerait que l'affichage
 * proposerait une connexion par OTP qui echoue systematiquement.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PlatformSettingsWiringIT — les reglages sont appliques, pas seulement affiches")
class PlatformSettingsWiringIT {

    @Autowired PlatformSettingsService settings;
    @Autowired CommissionRateResolver commissionRateResolver;
    @Autowired SmsService smsService;
    @Autowired AnnouncementSearchMapper searchMapper;

    private static final UUID ADMIN = UUID.randomUUID();

    @Test
    @DisplayName("Le taux de commission modifie est celui que le calcul serveur applique")
    void commissionRateChangeReachesTheResolver() {
        BigDecimal before = settings.commissionRate();
        BigDecimal changed = before.add(new BigDecimal("0.02"));

        settings.update(Map.of(PlatformSettingKey.COMMISSION_RATE, changed.toPlainString()), ADMIN);
        try {
            // CommissionRateResolver.globalRate() est la source unique lue par PaymentService,
            // BidService, CashCommissionService et PriceGridService : la verrouiller ici
            // couvre tout l'aval, y compris l'application_fee_amount envoye a Stripe.
            assertThat(commissionRateResolver.globalRate()).isEqualByComparingTo(changed);
        } finally {
            settings.update(Map.of(PlatformSettingKey.COMMISSION_RATE, before.toPlainString()), ADMIN);
        }

        assertThat(commissionRateResolver.globalRate()).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("La bascule des SMS modifiee est celle que le service SMS applique")
    void smsEnabledChangeReachesTheSmsService() {
        boolean before = settings.smsEnabled();

        settings.update(Map.of(PlatformSettingKey.SMS_ENABLED, String.valueOf(!before)), ADMIN);
        try {
            // SmsOtpService gate l'envoi des codes de connexion sur smsService.isEnabled() :
            // si cette valeur ignorait la table, couper les SMS depuis le back-office pendant
            // un incident ne couperait rien du tout.
            assertThat(smsService.isEnabled()).isEqualTo(!before);
        } finally {
            settings.update(Map.of(PlatformSettingKey.SMS_ENABLED, String.valueOf(before)), ADMIN);
        }

        assertThat(smsService.isEnabled()).isEqualTo(before);
    }

    @Test
    @DisplayName("Le seuil d'urgence modifie change le drapeau « urgent » calcule par le mapper")
    void urgencyThresholdChangeReachesTheSearchMapper() {
        // Depart dans 5 jours : hors du seuil par defaut (3 jours), dans le seuil a 7.
        // On interroge la SORTIE du mapper, pas le service de reglages — sans quoi le test
        // se contenterait de se relire lui-meme.
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setDepartureDate(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        // Coordonnees requises par le mapper, sans rapport avec le seuil teste.
        announcement.setPickupLat(new BigDecimal("48.8566"));
        announcement.setPickupLng(new BigDecimal("2.3522"));
        announcement.setDeliveryLat(new BigDecimal("14.7167"));
        announcement.setDeliveryLng(new BigDecimal("-17.4677"));

        int before = settings.urgencyThresholdDays();
        settings.update(Map.of(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, "3"), ADMIN);
        try {
            assertThat(searchMapper.toSearchResponse(announcement, false).urgent())
                    .as("depart a J+5, seuil a 3 jours")
                    .isFalse();

            settings.update(Map.of(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, "7"), ADMIN);
            assertThat(searchMapper.toSearchResponse(announcement, false).urgent())
                    .as("meme trajet, seuil porte a 7 jours depuis le back-office")
                    .isTrue();
        } finally {
            settings.update(Map.of(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, String.valueOf(before)),
                    ADMIN);
        }
    }
}

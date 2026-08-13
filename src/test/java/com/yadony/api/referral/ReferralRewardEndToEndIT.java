package com.yadony.api.referral;

import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Prouve que la récompense promise au parrain atterrit bien sur son solde
 * dépensable quand son filleul confirme sa première livraison.
 *
 * <p>Les deux moitiés de la chaîne étaient déjà testées séparément : la détection
 * de la première livraison en test unitaire (mock du publisher) et le versement au
 * portefeuille dans {@code ReferralRewardWalletIT} (événement publié à la main).
 * La soudure entre les deux ne l'était pas, alors que c'est elle qui porte le
 * risque : {@link DeliveryConfirmedReferralListener} publie son événement depuis
 * un callback {@code AFTER_COMMIT}, un contexte où une transaction vient de se
 * terminer. Si la transaction {@code REQUIRES_NEW} n'en réamorçait pas une, le
 * listener portefeuille serait ignoré <b>en silence</b> — le parrain verrait son
 * parrainage marqué « récompensé » sans qu'un centime n'atteigne son solde.
 *
 * <p>{@link BidRepository} est mocké pour fixer le rang de la livraison sans avoir
 * à faire persister un colis complet ; tout le reste (transactions, événements,
 * portefeuille, base) est réel.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Parrainage — la récompense atteint le portefeuille du parrain")
class ReferralRewardEndToEndIT {

    @Autowired private DeliveryConfirmedReferralListener listener;
    @Autowired private ReferralInvitationRepository invitationRepository;
    @Autowired private UserCreditRepository userCreditRepository;
    @Autowired private UserBusinessPrefsRepository businessPrefsRepository;
    @Autowired private WalletService walletService;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private BidRepository bidRepository;

    /** Crée une invitation acceptée mais pas encore récompensée. */
    private UUID seedSignedUpInvitation(UUID referrerId, UUID refereeId) {
        return transactionTemplate.execute(status -> {
            ReferralInvitationEntity inv = new ReferralInvitationEntity();
            inv.setReferrerUserId(referrerId);
            inv.setRefereeUserId(refereeId);
            inv.setStatus("SIGNED_UP");
            return invitationRepository.save(inv).getId();
        });
    }

    private void givenReferrerWorksIn(UUID referrerId, String currencyCode) {
        transactionTemplate.executeWithoutResult(status -> {
            UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
            prefs.setUserId(referrerId);
            prefs.setCurrencyCode(currencyCode);
            businessPrefsRepository.save(prefs);
        });
    }

    /** Fixe le nombre de livraisons déjà terminées par le filleul. */
    private void givenCompletedDeliveries(UUID refereeId, long count) {
        when(bidRepository.countByStatusAndSenderId(eq(BidStatus.COMPLETED), eq(refereeId)))
                .thenReturn(count);
    }

    @Test
    @DisplayName("première livraison du filleul : les 5 € promis sont crédités")
    void firstDelivery_creditsTheReferrerWallet() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        UUID invitationId = seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 1);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(walletService.getBalance(referrerId, "EUR"))
                .as("solde dépensable du parrain")
                .isEqualByComparingTo(new BigDecimal("5.00"));

        ReferralInvitationEntity inv = invitationRepository.findById(invitationId).orElseThrow();
        assertThat(inv.getStatus()).isEqualTo("REWARDED");
        assertThat(inv.getRewardedAt()).isNotNull();
    }

    @Test
    @DisplayName("parrain en franc CFA : il reçoit la valeur des 5 €, pas 5 F CFA")
    void firstDelivery_convertsTheRewardIntoTheReferrerCurrency() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        seedSignedUpInvitation(referrerId, refereeId);
        givenReferrerWorksIn(referrerId, "XOF");
        givenCompletedDeliveries(refereeId, 1);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        // 5 € × 655,957 arrondi au franc près. Reprendre le barème tel quel aurait
        // versé 5 F CFA, soit moins d'un centime d'euro.
        assertThat(walletService.getBalance(referrerId, "XOF"))
                .as("solde en franc CFA")
                .isEqualByComparingTo(new BigDecimal("3280"));

        // Aucun portefeuille en euros ne doit avoir été ouvert au passage.
        assertThat(walletService.getBalance(referrerId, "EUR"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("le montant annoncé au parrain égale ce qu'il peut dépenser")
    void grantedCreditMatchesTheWalletBalance() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        seedSignedUpInvitation(referrerId, refereeId);
        givenReferrerWorksIn(referrerId, "XOF");
        givenCompletedDeliveries(refereeId, 1);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        // Le total affiché sur l'écran de parrainage se lit dans user_credits alors
        // que le solde se lit dans le portefeuille : les deux doivent porter le même
        // montant, sans quoi l'écran promettrait une somme absente du solde.
        List<UserCreditEntity> credits = userCreditRepository.findByUserId(referrerId);
        assertThat(credits).hasSize(1);
        assertThat(credits.getFirst().getCurrency()).isEqualToIgnoringCase("XOF");
        assertThat(credits.getFirst().getAmountCents()).isEqualTo(3280);
    }

    @Test
    @DisplayName("deuxième livraison : aucune récompense, la promesse ne vaut qu'une fois")
    void laterDelivery_grantsNothing() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 2);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(walletService.getBalance(referrerId, "EUR")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(userCreditRepository.findByUserId(referrerId)).isEmpty();
    }

    @Test
    @DisplayName("filleul sans parrain : rien n'est crédité et rien n'échoue")
    void deliveryWithoutInvitation_isANoOp() {
        UUID refereeId = UUID.randomUUID();
        when(bidRepository.countByStatusAndSenderId(any(), any())).thenReturn(1L);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(userCreditRepository.findByUserId(refereeId)).isEmpty();
    }
}

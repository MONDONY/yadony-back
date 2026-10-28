package com.yadony.api.payments.wallet;

import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filet des crédits de portefeuille portés par un écouteur après commit, qui n'étaient jamais
 * rejoués quand ils échouaient :
 * <ul>
 *   <li>recharge mobile money confirmée par pawaPay ({@code WalletTopupOutcomeListener}) :
 *       l'argent est encaissé, le solde n'était crédité qu'à la main sur alerte ;</li>
 *   <li>récompense de parrainage choisie en crédit de solde ({@code ReferralRewardWalletListener}) :
 *       le choix est définitif, la récompense était perdue.</li>
 * </ul>
 *
 * <p>Toutes les 15 minutes, chaque crédit manquant est rejoué avec SA clé d'origine
 * ({@code pawapay-topup-<opération>}, {@code referral-reward:<invitation>}) : si l'écouteur aboutit
 * en même temps, le verrou du portefeuille et la clé font qu'un seul crédit est écrit. Chaque
 * crédit vit dans sa propre transaction ({@link WalletService#credit}).
 */
@Component
public class WalletCreditRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditRecoveryJob.class);

    /** Laisse à l'écouteur le temps d'aboutir avant de le considérer en échec. */
    static final Duration GRACE = Duration.ofMinutes(15);

    static final Duration TOPUP_WINDOW = Duration.ofDays(7);

    static final Duration REFERRAL_WINDOW = Duration.ofDays(30);

    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletService walletService;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    @Autowired
    public WalletCreditRecoveryJob(WalletTransactionRepository walletTransactionRepository,
                                   WalletService walletService,
                                   AdminAlertService adminAlertService) {
        this(walletTransactionRepository, walletService, adminAlertService, Clock.systemUTC());
    }

    WalletCreditRecoveryJob(WalletTransactionRepository walletTransactionRepository,
                            WalletService walletService,
                            AdminAlertService adminAlertService,
                            Clock clock) {
        this.walletTransactionRepository = walletTransactionRepository;
        this.walletService = walletService;
        this.adminAlertService = adminAlertService;
        this.clock = clock;
    }

    /** Sans transaction englobante : chaque crédit dans la sienne. */
    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void recoverMissedCredits() {
        Instant now = clock.instant();
        recover(walletTransactionRepository.findCompletedWalletTopupsWithoutCredit(
                        OffsetDateTime.ofInstant(now.minus(GRACE), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(now.minus(TOPUP_WINDOW), ZoneOffset.UTC)),
                WalletTransactionType.TOP_UP, "pawapay:", "pawapay-topup-", "wallet-topup-credit-recovered",
                "Recharge mobile money créditée par le rattrapage : le crédit à la confirmation avait échoué");
        recover(walletTransactionRepository.findChosenReferralRewardsWithoutCredit(
                        LocalDateTime.ofInstant(now.minus(GRACE), ZoneOffset.UTC),
                        LocalDateTime.ofInstant(now.minus(REFERRAL_WINDOW), ZoneOffset.UTC)),
                WalletTransactionType.REFERRAL_REWARD, "", "referral-reward:", "referral-reward-recovered",
                "Récompense de parrainage créditée par le rattrapage : le crédit au choix avait échoué");
    }

    /**
     * Rejoue chaque crédit avec la même référence et la même clé que l'écouteur d'origine
     * ({@code paymentRefPrefix} + id, {@code keyPrefix} + id), puis alerte sur ceux rattrapés.
     */
    private void recover(List<WalletTransactionRepository.MissedCredit> missed, WalletTransactionType type,
                         String paymentRefPrefix, String keyPrefix, String alertCode, String alertDetail) {
        List<String> recovered = new ArrayList<>();
        for (WalletTransactionRepository.MissedCredit credit : missed) {
            try {
                walletService.credit(credit.getUserId(), credit.getCurrency(), credit.getAmount(), type,
                        paymentRefPrefix + credit.getId(), keyPrefix + credit.getId());
                recovered.add(credit.getId().toString());
            } catch (RuntimeException e) {
                log.error("Rattrapage du crédit {} {} en échec", type, credit.getId(), e);
            }
        }
        if (!recovered.isEmpty()) {
            log.warn("{} : {}", alertDetail, recovered);
            adminAlertService.raise(alertCode, alertDetail,
                    Map.of("count", recovered.size(), "ids", String.join(",", recovered)));
        }
    }
}

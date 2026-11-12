package com.yadony.api.common.i18n;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou du chantier « textes serveur dans la langue de l'app » (défaut n° 9).
 *
 * <p>Les sources listées dans {@link #MIGRATED} ne doivent plus contenir de refus à texte
 * en dur : chaque erreur métier passe par {@code YadonyBusinessException.localized(..., clé)}
 * et la clé existe en français ET en anglais (titre et détail). La parité des deux fichiers de
 * messages est vérifiée par {@code MessagesBundleTest}. Quand un nouveau paquet est migré,
 * ajouter ses fichiers ici et cocher « migré » dans {@code docs/i18n-messages-serveur.md}.
 */
class ServerMessagesGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/yadony/api");

    /**
     * Sources dont tous les refus métier sont localisés (paquet 1 : matching ; paquet 2 : annulation côté
     * utilisateur, KYC côté utilisateur, avis ; paquet 3 : reste de matching et auth). Les services
     * d'administration des autres packages restent en français.
     */
    private static final List<String> MIGRATED = List.of(
            "matching/AnnouncementService.java",
            "matching/BidNegotiationService.java",
            "cancellation/CancellationController.java",
            "cancellation/CancellationGuard.java",
            "cancellation/CancellationService.java",
            "cancellation/DeliveryNoShowProcedureService.java",
            "cancellation/PrePaymentCancellationController.java",
            "cancellation/PrePaymentCancellationService.java",
            "cancellation/RescheduleDecisionService.java",
            "kyc/KycService.java",
            "kyc/provider/IdentityProviderResolver.java",
            "kyc/provider/didit/DiditIdentityProvider.java",
            "kyc/provider/stripe/StripeIdentityProvider.java",
            "ratings/RatingService.java",
            "ratings/ReceptionRatingService.java",
            // Paquet 3 : reste de matching (réservation, paiement, statistiques, report, réception) et auth.
            "auth/AdminGdprService.java",
            "auth/AuthController.java",
            "auth/AuthService.java",
            "auth/BlockService.java",
            "auth/ConnectedDevicesController.java",
            "auth/ConnectedDevicesService.java",
            "auth/GuestClaimService.java",
            "auth/GuestUserProvisioner.java",
            "auth/PhoneOwnershipService.java",
            "auth/ProfilePublicService.java",
            "auth/PublishCommitmentService.java",
            "auth/UserLanguageService.java",
            "auth/UserService.java",
            "matching/AnnouncementController.java",
            "matching/AnnouncementViewService.java",
            "matching/ArrivalRules.java",
            "matching/BidCheckoutService.java",
            "matching/BidContentRules.java",
            "matching/BidController.java",
            "matching/BidNegotiationController.java",
            "matching/BidPhotoService.java",
            "matching/BidService.java",
            "matching/HandoverDeadlineRules.java",
            "matching/PriceGridController.java",
            "matching/PriceGridService.java",
            "matching/TravelerStatsController.java",
            "matching/TripGroupService.java",
            "matching/TripLegRules.java",
            "matching/TripRecurrenceController.java",
            "matching/TripRecurrenceService.java",
            "matching/TripRescheduleController.java",
            "matching/TripRescheduleService.java",
            "matching/TransportMode.java",
            "matching/TransportModeGuard.java",
            "matching/TripSeriesService.java",
            "matching/TripStops.java",
            "matching/TripsSummaryController.java",
            "matching/reception/ReceptionService.java",
            "matching/reception/RecipientChangeService.java",
            "matching/reception/RecipientReplacementService.java",
            // Paquet 4a : paiements côté utilisateur (paiement carte, mobile money, portefeuille, commission
            // espèces, supplément). Les webhooks, jobs, rapprochement et l'administration restent pour le 4b.
            "payments/PaymentService.java",
            "payments/ConnectDashboardLinkService.java",
            "payments/cash/CashCommissionService.java",
            "payments/mobilemoney/MobileMoneyAccountController.java",
            "payments/mobilemoney/MobileMoneyBidPaymentService.java",
            "payments/mobilemoney/MobileMoneyCountriesController.java",
            "payments/mobilemoney/MobileMoneyNegotiationPaymentService.java",
            "payments/mobilemoney/MobileMoneyPayerRefusals.java",
            "payments/mobilemoney/MobileMoneyPaymentController.java",
            "payments/mobilemoney/MobileMoneyPayoutAccountsController.java",
            "payments/mobilemoney/MobileMoneyProvidersController.java",
            "payments/supplement/BidSupplementCardRail.java",
            "payments/supplement/BidSupplementCashRail.java",
            "payments/supplement/BidSupplementMobileMoneyRail.java",
            "payments/supplement/BidSupplementRail.java",
            "payments/supplement/BidSupplementController.java",
            "payments/supplement/BidSupplementMobileMoneyLedger.java",
            "payments/supplement/BidSupplementService.java",
            "payments/supplement/BidSupplementWindow.java",
            "payments/mobilemoney/MobileMoneyAcceptedOperatorsService.java",
            "payments/mobilemoney/MobileMoneyBidPricing.java",
            "payments/wallet/WalletRefundRequestService.java",
            "payments/wallet/WalletSelfRefundService.java",
            "payments/wallet/WalletService.java",
            "payments/wallet/WalletTopupOrchestrator.java",
            "payments/wallet/topupcode/WalletTopupCodeRedemptionService.java",
            // Paquet 4b : reste de payments (avenant, partage, administration du portefeuille, export fiscal,
            // bornes de prix, taux de change) et tracking. Restent hors liste : webhook Stripe (serveur à serveur),
            // services mobile money dont le détail est déjà résolu, CurrencyCatalog (texte anglais).
            "payments/ConnectDashboardController.java",
            "payments/FiscalExportController.java",
            "payments/PaymentBusy.java",
            "payments/PaymentController.java",
            "payments/PaymentStripeResyncService.java",
            "payments/PrePaymentReleaseAdapter.java",
            "payments/StripeV2AccountProvisioner.java",
            "payments/amendment/BidAmendmentCapacity.java",
            "payments/amendment/BidAmendmentCardRail.java",
            "payments/amendment/BidAmendmentCashRail.java",
            "payments/amendment/BidAmendmentController.java",
            "payments/amendment/BidAmendmentEligibility.java",
            "payments/amendment/BidAmendmentPricing.java",
            "payments/amendment/BidAmendmentService.java",
            "payments/cash/CashCommissionController.java",
            "payments/cash/CommissionUnfundedProblem.java",
            "payments/cash/WalletCommissionCollector.java",
            "payments/currency/ExchangeRateLookup.java",
            "payments/currency/ExchangeRateUpdateService.java",
            "payments/currency/GridItemPriceBounds.java",
            "payments/currency/PricePerKgBounds.java",
            "payments/overview/MoneyOverviewService.java",
            "payments/pawapay/PawapayErrors.java",
            "payments/pawapay/PawapayOperationService.java",
            "payments/split/PaymentSplitService.java",
            "payments/supplement/BidSupplementAdminService.java",
            "payments/wallet/WalletAdminAdjustmentService.java",
            "payments/wallet/WalletController.java",
            "payments/wallet/WalletExternalRefundService.java",
            "payments/wallet/WalletRefundRetryService.java",
            "payments/wallet/topupcode/WalletTopupCodeAdminService.java",
            "payments/wallet/topupcode/WalletTopupCodeController.java",
            "tracking/PickupCodeRequestService.java",
            "tracking/TrackingService.java",
            "tracking/TripPositionService.java");

    private static final Pattern HARDCODED_BUSINESS = Pattern.compile(
            "new\\s+(?:YadonyBusinessException\\s*\\(|ResponseStatusException\\s*\\("
                    + "|YadonyNotFoundException\\s*\\(\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\))");
    private static final Pattern FRENCH_IN_EXCEPTION = Pattern.compile(
            "new\\s+(?:Yadony\\w*|IllegalState|IllegalArgument|ResponseStatus)Exception\\s*\\(\\s*\"[^\"]*"
                    + "(?:[àâçéèêëîïôûùü]|\\b(?:Vous|Votre|introuvable|impossible)\\b)");
    /**
     * Sources migrées qui gardent des {@code IllegalStateException} françaises : elles ne sont jamais rendues
     * telles quelles (réponse 500 générique, voir l'inventaire), seul le texte du journal est en français
     * (paquet 4a : fichiers de paiement dont seules les {@code IllegalState/IllegalArgument} internes restent françaises).
     */
    private static final List<String> INDIRECT_ILLEGAL_STATE_ALLOWED = List.of(
            "cancellation/CancellationService.java",
            "kyc/provider/didit/DiditIdentityProvider.java",
            "auth/GuestClaimService.java",
            "auth/GuestUserProvisioner.java",
            "payments/cash/CashCommissionService.java",
            "payments/mobilemoney/MobileMoneyBidPaymentService.java",
            "payments/mobilemoney/MobileMoneyNegotiationPaymentService.java",
            "payments/wallet/WalletService.java",
            "payments/wallet/WalletTopupOrchestrator.java",
            "payments/wallet/topupcode/WalletTopupCodeRedemptionService.java",
            // Paquet 4b : IllegalState/IllegalArgument internes restées françaises (journal seulement).
            "payments/amendment/BidAmendmentService.java",
            "payments/cash/WalletCommissionCollector.java",
            "payments/pawapay/PawapayOperationService.java",
            "payments/split/PaymentSplitService.java",
            "payments/wallet/topupcode/WalletTopupCodeAdminService.java");
    private static final Pattern FRENCH_IN_BUSINESS_EXCEPTION = Pattern.compile(
            "new\\s+(?:Yadony\\w*|ResponseStatus)Exception\\s*\\(\\s*\"[^\"]*"
                    + "(?:[àâçéèêëîïôûùü]|\\b(?:Vous|Votre|introuvable|impossible)\\b)");
    private static final Pattern KEY = Pattern.compile("\"(problem\\.[a-z0-9.\\-]+)\"");

    private static String source(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    @DisplayName("aucun refus métier à texte en dur dans les sources migrées")
    void noHardcodedBusinessExceptionInMigratedSources() throws IOException {
        for (String file : MIGRATED) {
            String src = source(file);
            assertThat(HARDCODED_BUSINESS.matcher(src).find())
                    .as("%s : utiliser YadonyBusinessException.localized(...) et une clé i18n", file)
                    .isFalse();
            Pattern french = INDIRECT_ILLEGAL_STATE_ALLOWED.contains(file)
                    ? FRENCH_IN_BUSINESS_EXCEPTION : FRENCH_IN_EXCEPTION;
            assertThat(french.matcher(src).find())
                    .as("%s : message français en dur dans une exception", file)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("chaque clé utilisée par les sources migrées existe en FR et en EN (titre et détail)")
    void everyKeyUsedByMigratedSourcesExistsInBothLanguages() throws IOException {
        Messages fr = TestMessages.fr();
        Messages en = TestMessages.en();
        int checked = 0;
        for (String file : MIGRATED) {
            Matcher m = KEY.matcher(source(file));
            while (m.find()) {
                String key = m.group(1);
                // Clé à titre + détail (YadonyBusinessException) ou clé à valeur unique (KYC : raison d'un
                // ResponseStatusException ou détail d'un introuvable).
                boolean single = fr.find(key).isPresent();
                List<String> suffixes = single ? List.of("") : List.of(".title", ".detail");
                for (String suffix : suffixes) {
                    assertThat(fr.find(key + suffix)).as("%s%s absente de messages_fr (%s)", key, suffix, file).isPresent();
                    assertThat(en.find(key + suffix)).as("%s%s absente de messages_en (%s)", key, suffix, file).isPresent();
                }
                checked++;
            }
        }
        assertThat(checked).as("aucune clé repérée : le garde-fou ne vérifie plus rien").isGreaterThan(100);
    }

    @Test
    @DisplayName("localized : getMessage et getTitle restent en français, la langue demandée est résolue à part")
    void localizedExceptionKeepsFrenchMessageAndResolvesRequestedLanguage() {
        YadonyBusinessException e = YadonyBusinessException.localized(
                HttpStatus.FORBIDDEN, "draft-limit-reached", "problem.draft-limit-reached.upgrade", 3);

        assertThat(e.isLocalized()).isTrue();
        assertThat(e.getMessage()).isEqualTo("Limite de 3 brouillon(s) atteinte. Passez en PRO pour en créer davantage.");
        assertThat(e.getTitle()).isEqualTo("Draft Limit Reached");
        assertThat(e.getDetail(TestMessages.fr())).isEqualTo(e.getMessage());
        assertThat(e.getDetail(TestMessages.en())).isEqualTo("Limit of 3 draft(s) reached. Upgrade to PRO to create more.");
        assertThat(e.withProperties(java.util.Map.of("legIndex", 1)).getDetail(TestMessages.en()))
                .isEqualTo("Limit of 3 draft(s) reached. Upgrade to PRO to create more.");
    }

    @Test
    @DisplayName("exception historique à texte en dur : renvoyée telle quelle quelle que soit la langue")
    void legacyExceptionIsReturnedAsIs() {
        YadonyBusinessException e = new YadonyBusinessException(HttpStatus.CONFLICT, "x", "Titre", "Texte fixe");

        assertThat(e.isLocalized()).isFalse();
        assertThat(e.getDetail(TestMessages.en())).isEqualTo("Texte fixe");
        assertThat(e.getTitle(TestMessages.en())).isEqualTo("Titre");
        assertThat(Locale.ROOT).isNotNull();
    }
}

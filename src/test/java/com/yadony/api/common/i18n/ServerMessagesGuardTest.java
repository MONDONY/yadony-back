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
            "tracking/TripPositionService.java",
            // Paquet 5 : petits packages (priorité 2) : stockage et images, grille de commission, GP Pro, codes
            // SMS et e-mail, adresses, messagerie, support, demandes, signalements, alertes, appels, parrainage,
            // codes promo, vols, statut voyageur, litiges, abonnement PRO, réglages, carnet d'adresses,
            // notifications, modèles de trajet, favoris, activation, recherche, maintenance, export.
            "activation/ActivationService.java",
            "address/AddressController.java",
            "addressbook/delivery/DeliveryAddressController.java",
            "addressbook/invitation/RecipientInvitationService.java",
            "addressbook/pickup/PickupAddressController.java",
            "addressbook/recipient/RecipientController.java",
            "billing/BillingController.java",
            "billing/ProSubscriptionService.java",
            "billing/StripeBillingService.java",
            "calls/CallService.java",
            "common/ImageProcessingService.java",
            "common/StorageController.java",
            "common/commission/CommissionGridService.java",
            "common/stripe/StripeUnavailable.java",
            "common/stripe/StripeWebhookIngestService.java",
            "disputes/AdminDisputeOpeningService.java",
            "disputes/DisputeController.java",
            "emailotp/EmailOtpController.java",
            "emailotp/EmailOtpService.java",
            "emailotp/ResendEmailService.java",
            "export/UserDataExportController.java",
            "favorites/FavoriteService.java",
            "favorites/FavoriteTargetType.java",
            "flights/FlightAlertOffersScope.java",
            "flights/FlightAlertService.java",
            "flights/FlightClickService.java",
            "flights/FlightErrors.java",
            "flights/FlightQuery.java",
            "gppro/GpProAdminService.java",
            "gppro/GpProApplicationService.java",
            "gppro/GpProController.java",
            "gppro/GpProDocumentController.java",
            "gppro/GpProDocumentService.java",
            "maintenance/MaintenanceService.java",
            "messaging/ConversationMediaController.java",
            "messaging/ConversationMediaPolicy.java",
            "messaging/RecipientConversationController.java",
            "messaging/RecipientConversationService.java",
            "notifications/NotificationController.java",
            "notifications/NotificationFeedService.java",
            "notifications/NotificationPrefsService.java",
            "notifications/NotificationService.java",
            "referral/ReferralService.java",
            "requests/service/PackageRequestModerationService.java",
            "requests/service/PackageRequestPhotoService.java",
            "search/SearchParseController.java",
            "settings/MinPricePerKgGuard.java",
            "settings/UserBusinessPrefsService.java",
            "signalements/ReportController.java",
            "signalements/ReportService.java",
            "smsotp/SmsOtpController.java",
            "support/SupportAdminToolsService.java",
            "support/SupportAttachmentService.java",
            "support/SupportController.java",
            "support/SupportTicketService.java",
            "travelerstatus/IndividualFrequencyAdminService.java",
            "travelerstatus/PublishingEligibilityController.java",
            "travelerstatus/PublishingLimitGate.java",
            "travelerstatus/TravelerStatusController.java",
            "travelerstatus/TravelerStatusService.java",
            "triptemplate/TripTemplateController.java",
            "triptemplate/TripTemplateService.java",
            "videomodal/VideoModalService.java",
            // Paquet 6 : administration, assistant, DAC7 (voyageur et administration), automatisations, outils.
            // Restent hors liste : règles de ciblage de diffusion (AudienceField/Rule/SqlCompiler, BroadcastTarget),
            // comptes administrateurs (AdminAccountService, déjà en anglais), grilles et simulations avec map de champs.
            "admin/AdminAlertController.java",
            "admin/AdminAnnouncementModerationController.java",
            "admin/AdminBidsController.java",
            "admin/AdminBroadcastController.java",
            "admin/AdminBroadcastSegmentController.java",
            "admin/AdminConversationToolsController.java",
            "admin/AdminConversationToolsService.java",
            "admin/AdminDisputesController.java",
            "admin/AdminGdprController.java",
            "admin/AdminIncidentContextController.java",
            "admin/AdminMaintenanceController.java",
            "admin/AdminPackageRequestQueryService.java",
            "admin/AdminPaymentController.java",
            "admin/AdminPhotoStorageController.java",
            "admin/AdminPromoController.java",
            "admin/AdminRatingContextController.java",
            "admin/AdminRatingModerationService.java",
            "admin/AdminRatingsController.java",
            "admin/AdminRecetteController.java",
            "admin/AdminRecetteTesterController.java",
            "admin/AdminRecetteTesterService.java",
            "admin/AdminReleaseIntentController.java",
            "admin/AdminReportContextController.java",
            "admin/AdminReportReplyController.java",
            "admin/AdminReportReplyService.java",
            "admin/AdminReportsController.java",
            "admin/AdminSettingsController.java",
            "admin/AdminSupportController.java",
            "admin/AdminUserController.java",
            "admin/AdminUserDeletionService.java",
            "admin/AdminUserKycController.java",
            "admin/AdminUserWalletController.java",
            "admin/AdminWalletAdjustmentRequestController.java",
            "admin/AdminWalletRefundController.java",
            "admin/ReportActionPolicy.java",
            "admin/dto/BroadcastTargetRequest.java",
            "admin/export/AdminExportController.java",
            "admin/notifications/AdminNotificationService.java",
            "admin/userview/AdminUserViewController.java",
            "assistant/AssistantController.java",
            "assistant/AssistantQuotaGuard.java",
            "assistant/formdraft/FormDraftController.java",
            "assistant/formdraft/FormDraftEngine.java",
            "assistant/formdraft/FormDraftQuotaGuard.java",
            "automation/AutomationHistoryController.java",
            "automation/AutomationRuleController.java",
            "automation/AutomationRuleService.java",
            "dac7/Dac7DeclarationService.java",
            "dac7/Dac7DueDiligenceService.java",
            "dac7/Dac7ExportService.java",
            "dac7/Dac7SellerStatementService.java",
            "dac7/TravelerTaxInfoController.java",
            "dac7/TravelerTaxInfoService.java",
            "toolkit/ToolsCompletionController.java");

    /**
     * Sources partiellement migrées (paquet 5) qui gardent des refus déjà en anglais neutre (statuts Google,
     * contrôle de fichier, secret interne, ressources techniques) ou un détail déjà résolu dans la langue de la
     * requête (SMS) : le garde-fou n'impose pas {@code localized}, il interdit seulement un message français en dur.
     */
    private static final List<String> FRENCH_FREE_ONLY = List.of(
            "address/GoogleAddressService.java",
            "alerts/AlertService.java",
            "alerts/TripSpaceAlertService.java",
            "common/StorageService.java",
            "flights/FlightUsers.java",
            "messaging/ConversationController.java",
            "messaging/ConversationMediaService.java",
            "messaging/ConversationService.java",
            "messaging/MessagingNotifyController.java",
            "requests/controller/NegotiationController.java",
            "requests/controller/PackageRequestController.java",
            "requests/controller/PackageRequestInsightController.java",
            "requests/service/NegotiationService.java",
            "requests/service/PackageRequestService.java",
            "smsotp/SmsOtpService.java",
            // Paquet 6 : refus d'administration déjà en anglais (conversation) ou à détail calculé (note DAC7 sensible).
            "admin/AdminConversationController.java",
            "dac7/Dac7AdminService.java");

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
            "payments/wallet/topupcode/WalletTopupCodeAdminService.java",
            // Paquet 5 : IllegalState/IllegalArgument internes restées françaises (calcul, génération de code).
            "common/commission/CommissionCalculator.java",
            "promo/PromoService.java",
            // Paquet 6 : IllegalState/IllegalArgument internes restées françaises (règle de ciblage illisible).
            "admin/broadcast/BroadcastAudienceService.java",
            "admin/broadcast/segment/BroadcastSegmentService.java");
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
    @DisplayName("sources partiellement migrées : aucun message français en dur dans une exception métier")
    void partiallyMigratedSourcesHaveNoHardcodedFrench() throws IOException {
        for (String file : FRENCH_FREE_ONLY) {
            assertThat(FRENCH_IN_BUSINESS_EXCEPTION.matcher(source(file)).find())
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
        List<String> all = new java.util.ArrayList<>(MIGRATED);
        all.addAll(FRENCH_FREE_ONLY);
        for (String file : all) {
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

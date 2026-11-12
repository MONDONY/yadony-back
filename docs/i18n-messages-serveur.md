# Textes serveur en dur : inventaire i18n (défaut n° 9)

Recette conformité 1.14 : « textes serveur en français quand l'app est en anglais ». Les refus métier renvoyaient du français en dur quelle que soit la langue de l'app.

Inventaire généré par lecture des sources de `origin/main` (avant le paquet 1) : tout `new XxxException(...)`, `ResponseStatusException(...)`, `setDetail(...)` ou `ProblemDetail.forStatusAndDetail(...)` dont un littéral a l'air français (accents ou mots français courants). Détection heuristique, donc quelques faux positifs possibles. Les numéros de ligne sont ceux de `origin/main` avant migration.

**Bilan** : 1324 occurrences repérées. Paquet 5 (priorité 2, petits packages) : **environ 290 sites de lancer migrés** (183 clés `problem.*` à titre + détail par migration automatique, une trentaine de clés pour les aides privées `invalid(…)` / `conflict(…)` / `badRequest(…)` (vols, support, GP Pro, invitations, maintenance, modération, statut voyageur) et 3 clés à valeur unique `problem.not-found.*`), soit une trentaine de packages ; reste de la priorité 2 : `videomodal` et `config` (journal ou administration), et la priorité 3 entière (traitée au paquet 6, voir le bilan final en fin de document). Paquet 1 : **78 migrées** (58 clés `problem.*`, titre et détail, `messages_fr` / `messages_en`). Paquet 2 (`cancellation` côté utilisateur, `kyc` côté utilisateur, `ratings`) : **112 migrées** (116 sites de lancer au total : quelques refus sans accent, non repérés par l'heuristique, ont été migrés avec les autres, 68 clés `problem.*` à titre + détail et 3 clés à valeur unique `problem.kyc.*`). Paquet 3 (reste de `matching` et `auth`) : **218 migrées** (81 clés `problem.*` à titre + détail côté `matching`, 37 côté `auth`, et 7 clés à valeur unique : `problem.price-grid.*`, `problem.devices.*` ; les refus déjà couverts par une clé existante, comme « Utilisateur introuvable », la réutilisent). Paquet 4a (`payments` côté utilisateur : paiement carte, Stripe Connect, mobile money payeur, portefeuille, commission espèces, supplément, code de recharge) : **152 occurrences de l'inventaire migrées**, plus une quarantaine de sites que l'heuristique ne voyait pas (aides `notFound(code, texte)`, `payerUnsupported(texte)`, `unprocessable(code, texte)`, `reserve(…, texte)`, détails assemblés par ternaire), soit environ 190 sites de lancer pour 131 nouvelles clés (titre + détail, plus 8 motifs `problem.mobile-money-payer-unsupported.*` et 4 clés `.resolved` qui transmettent un détail déjà résolu dans la langue de la requête). Paquet 4b (reste de `payments` et `tracking`) : **162 occurrences de l'inventaire migrées** (100 `payments`, 62 `tracking`), plus une cinquantaine de sites que l'heuristique ne voyait pas (aides privées `unprocessable(…)`, `badRequest(…)`, `notEligible(…)`, `refusal(…)`, `invalid(…)`, détails assemblés par concaténation), soit environ 220 sites de lancer pour 160 nouvelles clés (titre + détail, dont 6 clés `.resolved` ou `.pending-request` pour le blocage de remise). **Reste environ 600** (`IllegalState` / `IllegalArgument` et services d'administration compris), dont 66 lignes de `payments` et 1 de `tracking` laissées volontairement (voir « Laissé tel quel (paquet 4b) »).

## Mode d'emploi (nouvelle erreur ou migration)

1. Ajouter dans `src/main/resources/i18n/messages_fr.properties` et `messages_en.properties`, avec les mêmes clés : `problem.<code>.title` et `problem.<code>.detail` (apostrophe doublée `''`, arguments `{0}`, `{1}`). Le français reprend le texte d'origine mot pour mot ; l'anglais ne contient ni tiret cadratin ni « Dony ».
2. Lever `throw YadonyBusinessException.localized(HttpStatus.X, "code-erreur", "problem.<code>", args...)` (variante avec `Map<String,Object>` de propriétés). `GlobalExceptionHandler` résout titre et détail selon l'`Accept-Language` de la requête (français par défaut) ; `getMessage()` et `getTitle()` restent en français pour les logs et les tests.
3. Ajouter le fichier migré à `MIGRATED` dans `ServerMessagesGuardTest` : il échouera si un `new YadonyBusinessException(` ou un message français en dur y réapparaît, ou si une clé manque en FR ou EN. `MessagesBundleTest` garantit déjà les mêmes clés dans les deux fichiers.
4. Hors requête HTTP (notification, tâche planifiée), ne pas utiliser `forRequest()` : passer par `messagesResolver.forUser(userId)` ou `dispatcher.messagesFor(userId)` (voir `GpProNotificationListener`).
5. Cocher « migré » ci-dessous.
6. Variantes (paquet 2) : une `ResponseStatusException` à texte français se remplace par `new LocalizedResponseStatusException(status, "problem.<clé>")` et une `YadonyNotFoundException("…")` par `YadonyNotFoundException.localized("problem.<clé>")` ; ces deux formes prennent une clé à valeur unique (pas de `.title` / `.detail`), le type et le titre de la réponse restent ceux d'avant (`generic` / `Not Found`). Le garde-fou accepte les deux formes de clé.
7. Détail déjà résolu (paquet 4a) : quand le texte est calculé plus haut dans la langue de la requête (`messagesResolver.forRequest().get(...)`), le passer comme argument d'une clé `problem.<code>.resolved` dont le détail vaut `{0}` (voir `problem.mobile-money-payer-unsupported.resolved`). Une aide privée qui recevait le texte (`notFound(code, texte)`, `unprocessable(code, texte)`) reçoit désormais la clé. Motifs partagés mobile money : `MobileMoneyPayerRefusals`.

## Reste à migrer, par priorité

Priorité 1 : erreurs qui remontent jusqu'à l'écran de l'app (paiement, réservation, annulation, suivi, compte, KYC, avis). Priorité 2 : fonctions secondaires visibles de l'utilisateur. Priorité 3 : admin, DAC7, assistant LLM, webhooks (textes lus par l'équipe ou jamais affichés).

| Priorité | Package | Reste | Fichiers principaux |
|---|---|---|---|
| P1 | `payments` | 66 | **4a migré** (152) et **4b migré** (100) : tout ce qui peut remonter à l'utilisateur ou à l'admin dans une réponse HTTP (`amendment/`, `split/`, `wallet/WalletAdminAdjustmentService`, `WalletRefundRetryService`, `WalletExternalRefundService`, `topupcode/WalletTopupCodeAdminService` et contrôleur, `supplement/BidSupplementAdminService`, `FiscalExportController`, `currency/` (taux de change, bornes de prix), `cash/CashCommissionController`, `WalletCommissionCollector`, `CommissionUnfundedProblem`, `PaymentController`, `ConnectDashboardController`, `PaymentBusy`, `PaymentStripeResyncService`, `StripeV2AccountProvisioner`, `PrePaymentReleaseAdapter`, `overview/MoneyOverviewService`, `pawapay/PawapayErrors` et `PawapayOperationService`, `WalletController`). **Reste** (voir « Laissé tel quel (paquet 4b) ») : `PaymentStripeWebhookHandler` (5, webhook serveur à serveur), `pawapay/PawapaySignatureVerifier`, `PawapayClient`, `StripeReleaseExecutor`, `EscrowCaptureService`, `EscrowScheduler` et les `IllegalState` / `IllegalArgument` internes (journal seulement). |
| P1 | `matching` | 0 | migré (paquet 3), sauf 5 `IllegalArgumentException` de calcul / configuration jamais rendues (`BidNegotiationPricing.java` (4), `ranking/TripRankingProperties.java` (1)) et les messages anglais de la recherche publique (`PublicAnnouncement*`, `AnnouncementInsightController` : `user/not-found`) |
| P1 | `cancellation` | 14 | `NoShowArbitrationService.java` (5, admin), `CancellationService.java` (6 `IllegalStateException` jamais rendues telles quelles : réponse 500 générique), `AdminBidCancellationService.java` (2, admin), `job/ReturnDeadlineScheduler.java` (1, alerte lue par l'équipe) ; non listés : `AdminBidCancellationService.conflict(...)` et `AdminNoShowQueryService` (admin) |
| P1 | `auth` | 0 | migré (paquet 3), sauf 6 `IllegalStateException` d'environnement ou de développement (`DevTokenController.java` (3), `FirebaseTokenFilter.java` (1), `GuestClaimService.java` (1), `GuestUserProvisioner.java` (1)) et `FirebaseContactService.java` (1, détail technique Firebase) |
| P1 | `tracking` | 1 | **4b migré** (62) : `TrackingService`, `TripPositionService`, `PickupCodeRequestService`. Reste `RecipientController.java` (1, 410 « lien de suivi remplacé » rendu par une `ResponseEntity<ProblemDetail>` statique de la page publique du destinataire, sans résolveur de langue). |
| P1 | `ratings` | 0 | migré (paquet 2) |
| P1 | `kyc` | 11 | `KycAdminReviewService.java` (4, admin), `KycAdminService.java` (2, admin), `reminder/KycReminderProperties.java` (4, validation de configuration au démarrage), `DiditIdentityProvider.java` (1 `IllegalStateException`, journal) |
| P2 | `common`, `gppro`, `smsotp`, `emailotp`, `address`, `messaging`, `support`, `requests`, `signalements`, `alerts`, `calls`, `referral`, `promo`, `flights`, `travelerstatus`, `disputes`, `billing`, `settings`, `addressbook`, `notifications`, `triptemplate`, `favorites`, `activation`, `search`, `maintenance`, `export` | 0 | **5 migré** (voir « Laissé tel quel (paquet 5) » pour les refus gardés tels quels) |
| P2 | `videomodal` | 4 | `VideoModalJsonConverters.java` (3, `IllegalStateException` de sérialisation, journal), `VideoModalAdminService.java` et `VideoModalImageService.java` (`invalid(code, detail)`, administration) ; `VideoModalService` (introuvable) est migré |
| P2 | `config` | 1 | `CallingCodeList.java` (1, validation de configuration au démarrage) |
| P3 | `admin` | 116 | `AdminPaymentController.java` (24), `AdminAlertController.java` (11), `AdminReportsController.java` (9), `AdminDisputesController.java` (8) |
| P3 | `assistant` | 50 | `provider/BaseUrlValidator.java` (13), `llm/OpenAiCompatibleClient.java` (12), `llm/AnthropicMessagesClient.java` (6), `llm/ProviderAddressGuard.java` (4) |
| P3 | `dac7` | 40 | `Dac7ExportService.java` (12), `Dac7AdminService.java` (7), `export/Dac7FileNaming.java` (3), `export/Dac7FileEncryptor.java` (3) |
| P3 | `automation` | 8 | `AutomationHistoryController.java` (4), `AutomationRuleController.java` (3), `AutomationRuleService.java` (1) |
| P3 | `toolkit` | 1 | `ToolsCompletionController.java` (1) |

Ordre conseillé pour les paquets suivants : (2, fait) `cancellation` côté utilisateur, `kyc` côté utilisateur, `ratings` ; (3, fait) `matching` restant et `auth` ; (4a, fait) `payments` côté utilisateur (`PaymentService`, `cash`, `mobilemoney`, `supplement`, `wallet`) ; (4b, fait) `payments` restant (`amendment`, `split`, administration du portefeuille, export fiscal, taux de change, bornes de prix) et `tracking` ; (5, fait) priorité 2 par petits packages ; (6, prochain) priorité 3 (`admin`, `assistant`, `dac7`, `automation`, `toolkit`) ; ancien libellé : priorité 2 par petits packages, du plus visible au moins visible : `common` (stockage, images), `gppro`, `smsotp` et `emailotp`, `address`, `messaging`, `support`, `requests`, `signalements`, `alerts`, `calls`, `referral`, `promo`, `flights`, `travelerstatus`, `disputes`, `billing`, `settings`, `addressbook`, `notifications`, `triptemplate`, `favorites`, puis les packages d'une à quatre lignes ; (6) priorité 3 : `admin`, `assistant`, `dac7`, `automation`, `toolkit` (dont les services d'administration `cancellation` et `kyc` restants).

Les `IllegalStateException`, `IllegalArgumentException`, `LlmCallException`, `PawapaySignatureException` etc. ne sont pas rendus tels quels par `GlobalExceptionHandler` (réponse générique ou webhook) : colonne « exposition » = `indirect`, à vérifier au cas par cas (un contrôleur qui les attrape et recopie le message les expose).

## Laissé tel quel (paquet 3)

- `matching/BidNegotiationPricing.java` (4) et `matching/ranking/TripRankingProperties.java` (1) : `IllegalArgumentException` de garde de calcul ou de configuration au démarrage, jamais rendues à l'utilisateur (les montants sont validés avant en 422 localisés).
- `auth/DevTokenController.java` (3, contrôleur de développement), `auth/FirebaseTokenFilter.java` (1), `auth/GuestClaimService.java` (1) et `auth/GuestUserProvisioner.java` (1) : `IllegalStateException` d'environnement ou de programmation (réponse 500 générique). Les deux dernières sont tolérées par `ServerMessagesGuardTest` (liste `INDIRECT_ILLEGAL_STATE_ALLOWED`).
- `auth/FirebaseContactService.java` (1) : détail technique d'une erreur Firebase (variable `errorDetail`, journal et diagnostic), pas un texte français en dur.
- `matching/PublicAnnouncementQuery.java`, `PublicAnnouncementSearchController.java`, `AnnouncementInsightController` (`user/not-found`) : codes ou textes anglais déjà neutres, hors périmètre de la traduction ; `AnnouncementInsightController.java` n'est donc pas dans `MIGRATED` (son refus d'authentification est migré).
- `YadonyNotFoundException("TripRecurrence", id)` (`TripRecurrenceService`) : forme technique anglaise à deux arguments ; le garde-fou ne flague plus que la forme à un seul littéral.

## Laissé tel quel (paquet 4b)

- `payments/PaymentStripeWebhookHandler.java` (5 `YadonyBusinessException` + 4 `IllegalStateException`) : erreurs d'un webhook Stripe, rendues à Stripe et lues par l'équipe, jamais affichées à un utilisateur. Le fichier n'est pas dans `MIGRATED`.
- `payments/mobilemoney/MobileMoneyPayoutAccountService.java`, `MobileMoneyAccountService.java` et `wallet/WalletMobileMoneyTopupService.java` : ils lèvent déjà leurs refus avec un détail résolu dans la langue de la requête (`messagesResolver.forRequest().get(...)`, clés `problem.mobile-money-account.*`, `problem.topup.*`), via l'ancien constructeur. Aucun texte français en dur : hors `MIGRATED` pour ne pas casser la garde sur `new YadonyBusinessException(`.
- `payments/currency/CurrencyCatalog.java` : détail déjà en anglais neutre (« The requested currency is not supported. »).
- `payments/pawapay/PawapaySignatureVerifier.java` (14), `PawapayClient.java` (4), `StripeReleaseExecutor`, `EscrowCaptureService`, `EscrowScheduler` (détail d'alerte admin), `CashCommissionService` (`RuntimeException` et `CommissionChargeFailedException`), `PayoutHeldException` : exceptions techniques ou alertes internes, journal et équipe seulement (réponse 500 générique ou webhook). `BidAmendmentService`, `PaymentSplitService`, `WalletCommissionCollector`, `PawapayOperationService` et `WalletTopupCodeAdminService` gardent des `IllegalState` / `IllegalArgument` françaises, listées dans `INDIRECT_ILLEGAL_STATE_ALLOWED`.
- `tracking/RecipientController.java` (`linkRevoked`, 410) : réponse statique de la page publique du destinataire, sans accès au résolveur de langue ; à traiter avec la page publique.
- `payments/wallet/WalletController`, `topupcode/WalletTopupCodeController` et `overview/MoneyOverviewService` : le refus « utilisateur introuvable » avait le titre en français et un détail vide ; il passe par `problem.user-not-found.title-only` (titre français identique, détail « Utilisateur introuvable » ajouté, anglais « User not found »).

## Détail des occurrences

| Statut | Exposition | Package | Fichier:ligne | Type | Message |
|---|---|---|---|---|---|
| migré | écran | matching | `matching/AnnouncementService.java:163` | YadonyBusinessException | La devise ne peut plus changer : ce trajet a déjà reçu une demande ou une offre. |
| migré | écran | matching | `matching/AnnouncementService.java:176` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | matching | `matching/AnnouncementService.java:904` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:908` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:911` | YadonyBusinessException | Trajet à étapes / Seul un trajet direct peut être répété à d'autres dates. |
| migré | écran | matching | `matching/AnnouncementService.java:916` | YadonyBusinessException | Trajet retiré / Ce trajet a été retiré par la modération. |
| migré | écran | matching | `matching/AnnouncementService.java:980` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1002` | YadonyBusinessException |  Passez en PRO pour en créer davantage. |
| migré | écran | matching | `matching/AnnouncementService.java:1084` | YadonyBusinessException | Prix invalide / Le prix par kg est obligatoire en mode KG |
| migré | écran | matching | `matching/AnnouncementService.java:1093` | YadonyBusinessException | Date invalide / La date de départ ne peut pas être dans le passé |
| migré | écran | matching | `matching/AnnouncementService.java:1188` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1222` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1341` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1352` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1360` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1563` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1566` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1569` | YadonyBusinessException | Vous n'êtes pas autorisé à modifier cette annonce |
| migré | écran | matching | `matching/AnnouncementService.java:1577` | YadonyBusinessException | Modification Impossible / Modification impossible : des colis sont déjà acceptés pour ce trajet |
| migré | écran | matching | `matching/AnnouncementService.java:1594` | YadonyBusinessException | Prix invalide / Le prix par kg est obligatoire en mode KG |
| migré | écran | matching | `matching/AnnouncementService.java:1816` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1820` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1825` | YadonyBusinessException | Vous n'êtes pas autorisé à publier cette annonce |
| migré | écran | matching | `matching/AnnouncementService.java:1830` | YadonyBusinessException | Seul un brouillon peut être publié |
| migré | écran | matching | `matching/AnnouncementService.java:1846` | YadonyBusinessException | La date de départ est passée. Modifiez le trajet avant de le publier. |
| migré | écran | matching | `matching/AnnouncementService.java:1885` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1889` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1893` | YadonyBusinessException | Vous n'êtes pas autorisé à dépublier cette annonce |
| migré | écran | matching | `matching/AnnouncementService.java:1897` | YadonyBusinessException | Seul un trajet actif peut être dépublié |
| migré | écran | matching | `matching/AnnouncementService.java:1901` | YadonyBusinessException | Ce trajet a déjà reçu des demandes et ne peut plus être dépublié |
| migré | écran | matching | `matching/AnnouncementService.java:1905` | YadonyBusinessException | Ce trajet est lié à une négociation et ne peut plus être dépublié |
| migré | écran | matching | `matching/AnnouncementService.java:1917` | YadonyBusinessException |  Passez en PRO pour en créer davantage. |
| migré | écran | matching | `matching/AnnouncementService.java:1980` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1984` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:1988` | YadonyBusinessException | Vous n'êtes pas autorisé à modifier ce trajet |
| migré | écran | matching | `matching/AnnouncementService.java:2014` | YadonyBusinessException | Aucun colis n'est actuellement pris en charge sur ce trajet |
| migré | écran | matching | `matching/AnnouncementService.java:2022` | YadonyBusinessException | Aucun colis n'a été récupéré : scannez le départ avant de marquer l'arrivée |
| migré | écran | matching | `matching/AnnouncementService.java:2067` | YadonyBusinessException | Ce trajet est totalement soldé, les instructions ne peuvent plus être modifiées |
| migré | écran | matching | `matching/AnnouncementService.java:2079` | YadonyBusinessException | Marquez d'abord le trajet comme arrivé avant de modifier les instructions de retrait |
| migré | écran | matching | `matching/AnnouncementService.java:2137` | YadonyBusinessException | Vous devez compléter votre vérification d'identité pour effectuer cette action |
| migré | écran | matching | `matching/AnnouncementService.java:2158` | YadonyBusinessException | Vous avez atteint votre limite de  |
| migré | écran | matching | `matching/AnnouncementService.java:2178` | YadonyBusinessException | ou publiez votre trajet en espèces uniquement |
| migré | écran | matching | `matching/AnnouncementService.java:2190` | YadonyBusinessException | La publication de trajets est suspendue. Contactez le support. |
| migré | écran | matching | `matching/AnnouncementService.java:2246` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:2250` | YadonyBusinessException | Des colis acceptés sont en cours sur cette annonce. |
| migré | écran | matching | `matching/AnnouncementService.java:2321` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:2325` | YadonyBusinessException | Cette annonce n'a pas été retirée par la modération. |
| migré | écran | matching | `matching/AnnouncementService.java:2351` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:2354` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/AnnouncementService.java:2357` | YadonyBusinessException | Vous n'êtes pas autorisé à supprimer cette annonce |
| migré | écran | matching | `matching/AnnouncementService.java:2396` | YadonyBusinessException | Deletion Impossible / Seuls les trajets actifs ou annulés peuvent être supprimés |
| migré | écran | matching | `matching/AnnouncementService.java:2403` | YadonyBusinessException | Un expéditeur a accepté votre offre sur ce trajet et procède au paiement :  / vous ne pouvez pas le supprimer  |
| migré | écran | matching | `matching/AnnouncementService.java:2413` | YadonyBusinessException | Deletion Impossible / Suppression impossible : des colis sont déjà acceptés pour ce trajet |
| migré | écran | matching | `matching/AnnouncementService.java:2767` | YadonyBusinessException | Wave et Orange Money ne sont plus proposés. Le mobile money passe par le compte  |
| migré | écran | matching | `matching/AnnouncementService.java:2775` | YadonyBusinessException | Choisissez au moins un moyen de paiement pour ce trajet. |
| migré | écran | matching | `matching/AnnouncementService.java:2828` | YadonyBusinessException | Heure de départ passée / L'heure de départ de ce trajet est déjà passée |
| migré | écran | matching | `matching/AnnouncementService.java:2834` | YadonyBusinessException | Date limite passée / La date limite de dépôt est déjà passée |
| migré | écran | matching | `matching/AnnouncementService.java:2857` | YadonyBusinessException | Date limite requise / La date limite de dépôt est obligatoire |
| migré | écran | matching | `matching/AnnouncementService.java:2868` | YadonyBusinessException | Date limite après le départ / La date limite de dépôt doit précéder le départ du voyageur |
| migré | écran | matching | `matching/BidNegotiationService.java:137` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidNegotiationService.java:141` | YadonyBusinessException | Ce trajet n'accepte pas les propositions de prix |
| migré | écran | matching | `matching/BidNegotiationService.java:152` | YadonyBusinessException | Le disclaimer légal doit être accepté |
| migré | écran | matching | `matching/BidNegotiationService.java:165` | YadonyBusinessException | Au moins un article ou un poids doit être renseigné |
| migré | écran | matching | `matching/BidNegotiationService.java:174` | YadonyBusinessException | Poids demandé supérieur à la capacité disponible |
| migré | écran | matching | `matching/BidNegotiationService.java:265` | YadonyBusinessException | Le nombre maximum de propositions échangées est atteint |
| migré | écran | matching | `matching/BidNegotiationService.java:274` | YadonyBusinessException | Ta contre-proposition est identique à la dernière proposition : accepte-la ou change le montant. |
| migré | écran | matching | `matching/BidNegotiationService.java:322` | YadonyBusinessException | Indique soit le montant payé par l'expéditeur, soit ton net, pas les deux. |
| migré | écran | matching | `matching/BidNegotiationService.java:362` | YadonyBusinessException | Aucune proposition de prix à accepter sur ce fil |
| migré | écran | matching | `matching/BidNegotiationService.java:679` | YadonyBusinessException | Discussion de prix introuvable |
| migré | écran | matching | `matching/BidNegotiationService.java:694` | YadonyBusinessException | Seule une discussion de prix terminée peut être archivée ou supprimée. |
| migré | écran | matching | `matching/BidNegotiationService.java:803` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidNegotiationService.java:806` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidNegotiationService.java:812` | YadonyBusinessException | Vous ne participez pas à cette discussion |
| migré | écran | matching | `matching/BidNegotiationService.java:820` | YadonyBusinessException | Cette discussion de prix est terminée |
| migré | écran | matching | `matching/BidNegotiationService.java:835` | YadonyBusinessException | Ce trajet est déjà parti |
| migré | écran | matching | `matching/BidNegotiationService.java:843` | YadonyBusinessException | C'est à la contrepartie de répondre |
| migré | écran | matching | `matching/BidNegotiationService.java:899` | YadonyBusinessException | Article hors grille de cette annonce :  |
| migré | écran | matching | `matching/BidNegotiationService.java:1103` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AdminGdprService.java:67` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AdminGdprService.java:76` | YadonyBusinessException | Cet utilisateur n'a pas demandé la suppression de son compte. Si la demande  / a été reçue hors application, c |
| migré | écran | auth | `auth/AdminGdprService.java:83` | YadonyBusinessException | Impossible — cet utilisateur a des transactions en cours |
| migré | écran | auth | `auth/AuthController.java:237` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | auth | `auth/AuthService.java:103` | YadonyBusinessException | Mode d'authentification non supporté |
| migré | écran | auth | `auth/AuthService.java:128` | YadonyBusinessException | Compte supprimé / Ce compte a été supprimé ou banni et ne peut pas être réactivé. |
| migré | écran | auth | `auth/AuthService.java:203` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:218` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:262` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:283` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:295` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:333` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:376` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:393` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:425` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:451` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:472` | ResponseStatusException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:496` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:515` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:519` | YadonyBusinessException | Ce compte est banni et ne peut pas être supprimé |
| migré | écran | auth | `auth/AuthService.java:524` | YadonyBusinessException | Impossible — vous avez des transactions en cours |
| migré | écran | auth | `auth/AuthService.java:558` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:571` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:574` | YadonyBusinessException | Ce compte n'est pas un compte PRO |
| migré | écran | auth | `auth/AuthService.java:611` | YadonyBusinessException | Le numéro de téléphone est requis |
| migré | écran | auth | `auth/AuthService.java:624` | YadonyBusinessException | L'email est introuvable dans le token Firebase |
| migré | écran | auth | `auth/AuthService.java:641` | YadonyBusinessException | Le numéro de téléphone est introuvable |
| migré | écran | auth | `auth/AuthService.java:655` | YadonyBusinessException | L'adresse email est introuvable |
| migré | écran | auth | `auth/AuthService.java:670` | YadonyBusinessException | L'adresse email est requise |
| migré | écran | auth | `auth/AuthService.java:675` | YadonyBusinessException | L'email fourni ne correspond pas au token Firebase |
| migré | écran | auth | `auth/AuthService.java:688` | YadonyBusinessException | Mode d'authentification non supporté |
| migré | écran | auth | `auth/AuthService.java:748` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/AuthService.java:761` | YadonyBusinessException | Image trop volumineuse (max 5 Mo) |
| migré | écran | auth | `auth/BlockService.java:55` | YadonyBusinessException | Action invalide |
| migré | écran | auth | `auth/BlockService.java:133` | YadonyBusinessException | Ressource introuvable |
| migré | écran | auth | `auth/ConnectedDevicesService.java:52` | ResponseStatusException | Impossible de révoquer l'appareil courant |
| migré | écran | auth | `auth/ConnectedDevicesService.java:57` | ResponseStatusException | Appareil introuvable |
| migré | écran | auth | `auth/ConnectedDevicesService.java:64` | ResponseStatusException | Utilisateur introuvable |
| migré | écran | auth | `auth/ConnectedDevicesService.java:71` | ResponseStatusException | Erreur lors de la révocation Firebase |
| reste | indirect | auth | `auth/DevTokenController.java:66` | IllegalStateException | Firebase non initialisé — vérifiez firebase.service-account-path dans application-dev.yml |
| reste | indirect | auth | `auth/DevTokenController.java:70` | IllegalStateException | Variable FIREBASE_API_KEY manquante dans .env.dev (Web API Key Firebase console > Paramètres du projet > Génér |
| reste | indirect | auth | `auth/DevTokenController.java:117` | IllegalStateException | Échec de l'échange du custom token Firebase — vérifiez FIREBASE_WEB_API_KEY |
| reste | indirect | auth | `auth/FirebaseTokenFilter.java:82` | IllegalStateException | yadony.dev.auth-bypass=true est interdit en profil prod — backdoor d'authentification |
| migré | écran | auth | `auth/GuestClaimService.java:114` | YadonyBusinessException | Le jeton présenté n'est pas celui d'une session visiteur. |
| migré | écran | auth | `auth/GuestClaimService.java:126` | YadonyBusinessException | Session identique à l'appelant / Le jeton présenté est celui de la session appelante : il n'y a rien à transfé |
| migré | écran | auth | `auth/GuestClaimService.java:156` | YadonyBusinessException | Compte réel / La session désignée appartient à un compte inscrit, pas à un visiteur. |
| migré | écran | auth | `auth/GuestClaimService.java:165` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/GuestClaimService.java:206` | YadonyBusinessException | Jeton visiteur invalide / Le jeton de la session visiteur est invalide ou a expiré. |
| migré | écran | auth | `auth/GuestClaimService.java:214` | YadonyBusinessException | Session déjà promue / Cette session visiteur est devenue un compte inscrit : ses données ne  / peuvent plus êt |
| reste | indirect | auth | `auth/GuestClaimService.java:226` | IllegalStateException | FirebaseAuth indisponible dans cet environnement : impossible de vérifier  |
| migré | écran | auth | `auth/GuestClaimService.java:235` | YadonyBusinessException | Jeton visiteur invalide / Le jeton de la session visiteur est invalide ou a expiré. |
| reste | indirect | auth | `auth/GuestUserProvisioner.java:58` | IllegalStateException | GuestUserProvisioner.resolveOrProvision ne doit jamais être appelé depuis  / d'écriture. |
| migré | écran | auth | `auth/GuestUserProvisioner.java:103` | YadonyBusinessException | Compte supprimé / Ce compte a été supprimé ou banni. Il ne peut pas être réactivé  / par cette action : reconn |
| migré | écran | auth | `auth/GuestUserProvisioner.java:133` | YadonyBusinessException | Requête concurrente / Une autre requête vient de créer ce compte invité. Réessayez. |
| migré | écran | auth | `auth/PhoneOwnershipService.java:147` | YadonyBusinessException | Ce numéro appartient à un compte en cours de suppression.  / Connectez-vous avec ce numéro pour annuler la sup |
| migré | écran | auth | `auth/PhoneOwnershipService.java:155` | YadonyBusinessException | Ce numéro a déjà son propre compte Yadony : connectez-vous avec ce numéro,  |
| migré | écran | auth | `auth/ProfilePublicService.java:71` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/ProfilePublicService.java:172` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/PublishCommitmentService.java:64` | YadonyBusinessException | Les engagements ont été mis à jour : relisez-les avant de continuer |
| migré | écran | auth | `auth/PublishCommitmentService.java:98` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserLanguageService.java:44` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:221` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:294` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:302` | YadonyBusinessException | Impossible — vous avez des transactions en cours |
| migré | écran | auth | `auth/UserService.java:319` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:323` | YadonyBusinessException | Ce compte n'est pas en cours de suppression |
| migré | écran | auth | `auth/UserService.java:351` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:354` | YadonyBusinessException | Ce compte n'a pas de demande de suppression en cours, ou sa suppression est déjà finalisée |
| migré | écran | auth | `auth/UserService.java:379` | YadonyBusinessException | Le taux doit être dans [0, 1[ ou null (taux global) |
| migré | écran | auth | `auth/UserService.java:383` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:396` | YadonyBusinessException | La limite doit être comprise entre 1 et 100, ou vide (aucune limite) |
| migré | écran | auth | `auth/UserService.java:400` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:437` | YadonyBusinessException | Le numéro SIRET doit contenir exactement 14 chiffres |
| migré | écran | auth | `auth/UserService.java:479` | YadonyBusinessException | Vous avez un abonnement PRO payant en cours — gérez sa résiliation  / depuis la gestion de votre abonnement (p |
| migré | écran | auth | `auth/UserService.java:501` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:515` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:539` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:544` | YadonyBusinessException | Ce compte n'est ni suspendu ni banni : il n'y a rien à lever |
| migré | écran | auth | `auth/UserService.java:569` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:586` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:611` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | auth | `auth/UserService.java:643` | YadonyBusinessException | Utilisateur introuvable |
| reste | écran | cancellation | `cancellation/AdminBidCancellationService.java:87` | YadonyBusinessException | Précisez le motif « Autre » en 10 caractères au moins. |
| reste | écran | cancellation | `cancellation/AdminBidCancellationService.java:94` | YadonyBusinessException | Colis introuvable |
| migré | écran | cancellation | `cancellation/CancellationController.java:191` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | cancellation | `cancellation/CancellationController.java:198` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | cancellation | `cancellation/CancellationGuard.java:54` | YadonyBusinessException | Le colis est en transit (ou le départ est dépassé) : annulation impossible. |
| migré | écran | cancellation | `cancellation/CancellationService.java:101` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:105` | YadonyBusinessException | Vous n'êtes pas autorisé à annuler cette annonce |
| migré | écran | cancellation | `cancellation/CancellationService.java:110` | YadonyBusinessException | Ce trajet est déjà annulé |
| migré | écran | cancellation | `cancellation/CancellationService.java:114` | YadonyBusinessException | Seul un trajet ACTIVE peut être annulé |
| migré | écran | cancellation | `cancellation/CancellationService.java:126` | YadonyBusinessException | Un expéditeur a accepté votre offre sur ce trajet et procède au paiement :  / vous ne pouvez pas l'annuler pen |
| migré | écran | cancellation | `cancellation/CancellationService.java:352` | YadonyBusinessException | Annulation introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:356` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:360` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:363` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:370` | YadonyBusinessException | Vous n'êtes pas concerné par cette annulation |
| migré | écran | cancellation | `cancellation/CancellationService.java:440` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:445` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:448` | YadonyBusinessException | Vous n'êtes pas le voyageur de ce bid. |
| reste | indirect | cancellation | `cancellation/CancellationService.java:452` | IllegalStateException | Le bid doit être en statut ACCEPTED. |
| reste | indirect | cancellation | `cancellation/CancellationService.java:455` | IllegalStateException | Vous ne pouvez signaler qu'après l'heure de remise prévue. |
| reste | indirect | cancellation | `cancellation/CancellationService.java:459` | IllegalStateException | Une annulation est déjà en cours pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:464` | YadonyBusinessException | Une déclaration d'absence existe déjà pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:507` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:510` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce bid. |
| reste | indirect | cancellation | `cancellation/CancellationService.java:514` | IllegalStateException | Le bid doit être en statut ACCEPTED. |
| reste | indirect | cancellation | `cancellation/CancellationService.java:517` | IllegalStateException | Vous ne pouvez signaler qu'après l'heure de remise prévue. |
| migré | écran | cancellation | `cancellation/CancellationService.java:533` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:537` | YadonyBusinessException | Vous n'êtes pas le voyageur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:571` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:574` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:612` | YadonyBusinessException | Le colis doit avoir été récupéré par le voyageur. |
| migré | écran | cancellation | `cancellation/CancellationService.java:616` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:620` | YadonyBusinessException | Le trajet n'est pas encore parti. |
| migré | écran | cancellation | `cancellation/CancellationService.java:625` | YadonyBusinessException | Un signalement d'absence à la livraison est déjà en cours pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:632` | YadonyBusinessException | Un signalement d'absence à la livraison a déjà été tranché pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:645` | YadonyBusinessException | Aucun signalement d'absence à la livraison pour ce bid |
| migré | écran | cancellation | `cancellation/CancellationService.java:650` | YadonyBusinessException | Le délai de contestation est dépassé. |
| migré | écran | cancellation | `cancellation/CancellationService.java:660` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:665` | YadonyBusinessException | Vous n'êtes pas le voyageur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:692` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:703` | YadonyBusinessException | Vous n'êtes pas partie prenante de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:708` | YadonyBusinessException | L'annulation après remise n'est possible que sur un colis remis (HANDED_OVER). |
| migré | écran | cancellation | `cancellation/CancellationService.java:724` | YadonyBusinessException | Une annulation existe déjà pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:845` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:848` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:853` | YadonyBusinessException | Seul le voyageur peut confirmer le retour du colis. |
| migré | écran | cancellation | `cancellation/CancellationService.java:857` | YadonyBusinessException | Aucun retour de colis en attente pour ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:861` | YadonyBusinessException | Le colis a déjà été marqué comme rendu. |
| migré | écran | cancellation | `cancellation/CancellationService.java:866` | YadonyBusinessException | Le code de retour a expiré — contactez le support. |
| migré | écran | cancellation | `cancellation/CancellationService.java:899` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:902` | YadonyBusinessException | Seul l'expéditeur peut consulter le code de retour. |
| migré | écran | cancellation | `cancellation/CancellationService.java:930` | YadonyBusinessException | Cette déclaration d'absence est déjà tranchée |
| migré | écran | cancellation | `cancellation/CancellationService.java:957` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:960` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:977` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/CancellationService.java:980` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce bid. |
| migré | écran | cancellation | `cancellation/CancellationService.java:985` | YadonyBusinessException | Aucune déclaration d'absence pour ce bid |
| migré | écran | cancellation | `cancellation/CancellationService.java:989` | YadonyBusinessException | Cette déclaration d'absence est déjà tranchée |
| reste | indirect | cancellation | `cancellation/CancellationService.java:994` | IllegalStateException | Le délai de contestation est dépassé. |
| migré | écran | cancellation | `cancellation/CancellationService.java:1008` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:114` | YadonyBusinessException | Déclarez d'abord votre arrivée à destination avant de signaler le destinataire absent. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:120` | YadonyBusinessException |  minutes après votre arrivée avant de le signaler absent. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:128` | YadonyBusinessException | Appelez ou écrivez au destinataire ou à l'expéditeur depuis l'app avant de signaler l'absence. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:133` | YadonyBusinessException | Confirmez avoir attendu sur place et tenté de joindre le destinataire. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:171` | YadonyBusinessException | Vous n'êtes ni l'expéditeur ni le voyageur de ce colis. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:219` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce colis. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:224` | YadonyBusinessException | Aucun signalement de destinataire absent pour ce colis. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:228` | YadonyBusinessException | Le colis n'est plus en garde : un nouveau rendez-vous ne peut plus être fixé. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:234` | YadonyBusinessException | Choisissez un rendez-vous à venir, avant la fin de la garde du colis. |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:267` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/DeliveryNoShowProcedureService.java:273` | YadonyBusinessException | Annonce introuvable |
| reste | écran | cancellation | `cancellation/NoShowArbitrationService.java:122` | YadonyBusinessException | Le colis a déjà été remis au voyageur ( /  l'expéditeur ne peut plus être confirmée. Rejetez la déclaration. |
| reste | écran | cancellation | `cancellation/NoShowArbitrationService.java:140` | YadonyBusinessException | Annonce introuvable pour ce bid |
| reste | écran | cancellation | `cancellation/NoShowArbitrationService.java:177` | YadonyBusinessException | Déclaration d'absence introuvable |
| reste | écran | cancellation | `cancellation/NoShowArbitrationService.java:182` | YadonyBusinessException | Cette déclaration d'absence est déjà tranchée |
| reste | écran | cancellation | `cancellation/NoShowArbitrationService.java:193` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationController.java:36` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:106` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:109` | YadonyBusinessException | Cette demande n'existe plus. |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:112` | YadonyBusinessException | Seul l'expéditeur peut annuler cette demande. |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:215` | YadonyBusinessException | Cette demande n'attend plus de paiement. |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:220` | YadonyBusinessException | Un paiement est en cours de validation. Réessayez dans quelques minutes. |
| migré | écran | cancellation | `cancellation/PrePaymentCancellationService.java:225` | YadonyBusinessException | Votre paiement vient d'être validé. Rouvrez le colis pour l'annuler avec remboursement. |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:69` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:72` | YadonyBusinessException | Bid introuvable |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:75` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce colis. |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:79` | YadonyBusinessException | Aucun report de trajet n'attend votre réponse. |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:83` | YadonyBusinessException | Annonce introuvable |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:86` | YadonyBusinessException | Le délai pour répondre au report est dépassé : votre colis reste sur le trajet. |
| migré | écran | cancellation | `cancellation/RescheduleDecisionService.java:109` | YadonyBusinessException | Une annulation existe déjà pour ce colis. |
| reste | écran | cancellation | `cancellation/job/ReturnDeadlineScheduler.java:88` | .setDetail |  annulé après remise : le voyageur ne l'a pas rendu à  / l'expéditeur avant l'échéance du  |
| reste | écran | kyc | `kyc/KycAdminReviewService.java:454` | YadonyBusinessException | Utilisateur introuvable |
| reste | écran | kyc | `kyc/KycAdminReviewService.java:474` | YadonyBusinessException | Aucune session de vérification chez le fournisseur pour cet utilisateur |
| reste | écran | kyc | `kyc/KycAdminReviewService.java:479` | YadonyBusinessException | L'identité de cet utilisateur est déjà vérifiée |
| reste | écran | kyc | `kyc/KycAdminReviewService.java:484` | YadonyBusinessException | L'identité de cet utilisateur n'est pas vérifiée |
| reste | écran | kyc | `kyc/KycAdminService.java:104` | YadonyBusinessException | Cet utilisateur n'a jamais démarré de vérification d'identité |
| reste | écran | kyc | `kyc/KycAdminService.java:151` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | kyc | `kyc/KycService.java:50` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | kyc | `kyc/KycService.java:53` | ResponseStatusException | KYC déjà vérifié |
| migré | écran | kyc | `kyc/KycService.java:91` | ResponseStatusException | Impossible de créer la session de vérification |
| migré | écran | kyc | `kyc/KycService.java:138` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | kyc | `kyc/KycService.java:160` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | kyc | `kyc/provider/IdentityProviderResolver.java:45` | ResponseStatusException | Impossible de créer la session de vérification |
| reste | indirect | kyc | `kyc/provider/didit/DiditIdentityProvider.java:89` | IllegalStateException | réponse Didit sans session_id ni url |
| migré | écran | kyc | `kyc/provider/didit/DiditIdentityProvider.java:98` | ResponseStatusException | Impossible de créer la session de vérification |
| migré | écran | kyc | `kyc/provider/stripe/StripeIdentityProvider.java:112` | ResponseStatusException | Impossible de créer la session de vérification |
| reste | indirect | kyc | `kyc/reminder/KycReminderProperties.java:40` | IllegalArgumentException | yadony.kyc.verification-reminders : fenêtre horaire invalide ( |
| reste | indirect | kyc | `kyc/reminder/KycReminderProperties.java:45` | IllegalArgumentException | yadony.kyc.verification-reminders : les délais doivent être positifs |
| reste | indirect | kyc | `kyc/reminder/KycReminderProperties.java:48` | IllegalArgumentException | yadony.kyc.verification-reminders : les délais doivent être strictement croissants |
| reste | indirect | kyc | `kyc/reminder/KycReminderProperties.java:53` | IllegalArgumentException | yadony.kyc.verification-reminders : max-anchor-age doit dépasser third-delay |
| migré | écran | matching | `matching/AnnouncementController.java:309` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | matching | `matching/AnnouncementInsightController.java:49` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | matching | `matching/AnnouncementViewService.java:97` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/ArrivalRules.java:30` | YadonyBusinessException | Arrivée avant le départ / La date d'arrivée ne peut pas précéder la date de départ |
| migré | écran | matching | `matching/ArrivalRules.java:35` | YadonyBusinessException | Arrivée trop tardive / La date d'arrivée doit être au plus  /  jours après le départ |
| migré | écran | matching | `matching/ArrivalRules.java:42` | YadonyBusinessException | Heure d'arrivée avant le départ / Le même jour, l'heure d'arrivée doit suivre l'heure de départ |
| migré | écran | matching | `matching/BidCheckoutService.java:81` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/BidCheckoutService.java:97` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidCheckoutService.java:101` | YadonyBusinessException | Cette annonce n'est plus disponible |
| migré | écran | matching | `matching/BidCheckoutService.java:116` | YadonyBusinessException | La zone CFA n'accepte pas le paiement par carte pour un colis. |
| migré | écran | matching | `matching/BidCheckoutService.java:123` | YadonyBusinessException | Cette annonce n'accepte pas le paiement par carte |
| migré | écran | matching | `matching/BidCheckoutService.java:133` | YadonyBusinessException | La capacité supplémentaire de ce trajet n'est pas ouverte aux autres expéditeurs |
| migré | écran | matching | `matching/BidCheckoutService.java:141` | YadonyBusinessException | Vous avez déjà un colis réservé sur ce trajet |
| migré | écran | matching | `matching/BidCheckoutService.java:147` | YadonyBusinessException | Vous ne pouvez pas faire une demande sur votre propre annonce |
| migré | écran | matching | `matching/BidCheckoutService.java:167` | YadonyBusinessException | Vous avez déjà une demande en attente de paiement mobile money pour ce trajet |
| migré | écran | matching | `matching/BidCheckoutService.java:196` | YadonyBusinessException | Vous avez déjà une demande en cours pour ce trajet |
| migré | écran | matching | `matching/BidCheckoutService.java:208` | YadonyBusinessException | Poids demandé supérieur à la capacité disponible |
| migré | écran | matching | `matching/BidCheckoutService.java:218` | YadonyBusinessException | Au moins un article ou un poids doit être renseigné |
| migré | écran | matching | `matching/BidCheckoutService.java:258` | YadonyBusinessException | Article hors grille de cette annonce :  |
| migré | écran | matching | `matching/BidCheckoutService.java:327` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/BidCheckoutService.java:331` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidCheckoutService.java:336` | YadonyBusinessException | Cette demande ne vous appartient pas |
| migré | écran | matching | `matching/BidCheckoutService.java:341` | YadonyBusinessException | Cette demande n'est pas issue d'une négociation de prix |
| migré | écran | matching | `matching/BidCheckoutService.java:351` | YadonyBusinessException | Cette demande se règle par mobile money, pas par carte. |
| migré | écran | matching | `matching/BidCheckoutService.java:360` | YadonyBusinessException | La zone CFA n'accepte pas le paiement par carte pour un colis. |
| migré | écran | matching | `matching/BidCheckoutService.java:371` | YadonyBusinessException | Cette demande n'attend pas de paiement (statut :  |
| migré | écran | matching | `matching/BidCheckoutService.java:383` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidCheckoutService.java:386` | YadonyBusinessException | Cette annonce n'est plus disponible |
| migré | écran | matching | `matching/BidContentRules.java:30` | YadonyBusinessException | Le voyageur n'accepte pas :  |
| migré | écran | matching | `matching/BidController.java:245` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | matching | `matching/BidNegotiationController.java:154` | YadonyBusinessException | Un token Firebase valide est requis |
| reste | indirect | matching | `matching/BidNegotiationPricing.java:25` | IllegalArgumentException | Le montant négocié doit être strictement positif |
| reste | indirect | matching | `matching/BidNegotiationPricing.java:28` | IllegalArgumentException | Le taux de commission ne peut pas être négatif |
| reste | indirect | matching | `matching/BidNegotiationPricing.java:51` | IllegalArgumentException | Le net demandé doit être strictement positif |
| reste | indirect | matching | `matching/BidNegotiationPricing.java:54` | IllegalArgumentException | Le taux de commission ne peut pas être négatif |
| migré | écran | matching | `matching/BidPhotoService.java:51` | YadonyBusinessException |  photos par colis |
| migré | écran | matching | `matching/BidPhotoService.java:60` | YadonyBusinessException | Clé photo invalide |
| migré | écran | matching | `matching/BidService.java:191` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:200` | YadonyBusinessException | Au moins un article ou un poids doit être renseigné |
| migré | écran | matching | `matching/BidService.java:210` | YadonyBusinessException | Article hors grille de cette annonce :  |
| migré | écran | matching | `matching/BidService.java:221` | YadonyBusinessException | Le prix au kilo est requis pour calculer le devis au poids |
| migré | écran | matching | `matching/BidService.java:291` | YadonyBusinessException | Le montant calculé est invalide (≤ 0) |
| migré | écran | matching | `matching/BidService.java:324` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:334` | YadonyBusinessException | Poids demandé supérieur à la capacité disponible |
| migré | écran | matching | `matching/BidService.java:340` | YadonyBusinessException | Le disclaimer légal doit être accepté |
| migré | écran | matching | `matching/BidService.java:350` | YadonyBusinessException | Au moins un article ou un poids doit être renseigné |
| migré | écran | matching | `matching/BidService.java:407` | YadonyBusinessException | Article hors grille de cette annonce :  |
| migré | écran | matching | `matching/BidService.java:466` | YadonyBusinessException | Cette annonce n'est plus disponible |
| migré | écran | matching | `matching/BidService.java:486` | YadonyBusinessException | La capacité supplémentaire de ce trajet n'est pas ouverte aux autres expéditeurs |
| migré | écran | matching | `matching/BidService.java:495` | YadonyBusinessException | Vous avez déjà un colis réservé sur ce trajet |
| migré | écran | matching | `matching/BidService.java:501` | YadonyBusinessException | Vous ne pouvez pas faire une demande sur votre propre annonce |
| migré | écran | matching | `matching/BidService.java:517` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:536` | YadonyBusinessException | Vous avez déjà une demande en cours ou acceptée pour ce trajet |
| migré | écran | matching | `matching/BidService.java:558` | YadonyBusinessException | Cet utilisateur n'accepte que les profils vérifiés |
| migré | écran | matching | `matching/BidService.java:581` | YadonyBusinessException | Vous avez déjà une demande en attente de paiement mobile money pour ce trajet |
| migré | écran | matching | `matching/BidService.java:594` | YadonyBusinessException | Votre demande carte sur ce trajet est déjà en cours de paiement |
| migré | écran | matching | `matching/BidService.java:626` | YadonyBusinessException | Numéro de téléphone invalide pour le paiement mobile money. |
| migré | écran | matching | `matching/BidService.java:647` | YadonyBusinessException | Méthode de paiement inconnue :  |
| migré | écran | matching | `matching/BidService.java:653` | YadonyBusinessException | Le paiement mobile money direct par l'expéditeur n'est plus disponible  |
| migré | écran | matching | `matching/BidService.java:668` | YadonyBusinessException | La zone CFA n'accepte pas ce moyen de paiement pour un colis.  |
| migré | écran | matching | `matching/BidService.java:677` | YadonyBusinessException | Ce voyageur n'accepte pas le paiement mobile money. |
| migré | écran | matching | `matching/BidService.java:686` | YadonyBusinessException | Le compte de versement mobile money de ce voyageur ne correspond pas  / à la devise de ce trajet. |
| migré | écran | matching | `matching/BidService.java:698` | YadonyBusinessException | La zone CFA n'accepte pas ce moyen de paiement pour un colis.  |
| migré | écran | matching | `matching/BidService.java:706` | YadonyBusinessException | Cette annonce n'accepte pas le paiement en espèces |
| migré | écran | matching | `matching/BidService.java:713` | YadonyBusinessException | Cette annonce n'accepte pas le paiement par carte |
| migré | écran | matching | `matching/BidService.java:726` | YadonyBusinessException | Seul l'expéditeur peut confirmer le paiement |
| migré | écran | matching | `matching/BidService.java:760` | YadonyBusinessException | Accès non autorisé à ce bid |
| migré | écran | matching | `matching/BidService.java:769` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidService.java:778` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidService.java:813` | YadonyBusinessException | Accès non autorisé à ce colis |
| migré | écran | matching | `matching/BidService.java:819` | YadonyBusinessException | Le numéro n'est communiqué qu'une fois le colis accepté |
| migré | écran | matching | `matching/BidService.java:827` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/BidService.java:835` | YadonyBusinessException | Ce membre préfère échanger par la messagerie Yadony |
| migré | écran | matching | `matching/BidService.java:856` | YadonyBusinessException | Vous n'êtes pas autorisé à voir ces demandes |
| migré | écran | matching | `matching/BidService.java:991` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidService.java:994` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:1012` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidService.java:1015` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:1031` | YadonyBusinessException | Acceptez ce colis via le parcours mobile money. |
| migré | écran | matching | `matching/BidService.java:1048` | YadonyBusinessException | Le voyageur est déjà parti, ce trajet n'accepte plus de colis |
| migré | écran | matching | `matching/BidService.java:1056` | YadonyBusinessException | Capacité insuffisante pour accepter cette demande |
| migré | écran | matching | `matching/BidService.java:1174` | YadonyBusinessException | Confirmation de présence uniquement pour les bids acceptés |
| migré | écran | matching | `matching/BidService.java:1206` | YadonyBusinessException | Vous n'êtes pas autorisé à annuler ce bid |
| migré | écran | matching | `matching/BidService.java:1212` | YadonyBusinessException | Impossible d'annuler un bid déjà terminé |
| migré | écran | matching | `matching/BidService.java:1339` | YadonyBusinessException | Vous n'êtes pas autorisé à masquer ce bid |
| migré | écran | matching | `matching/BidService.java:1356` | YadonyBusinessException | Vous n'êtes pas autorisé à effectuer cette action |
| migré | écran | matching | `matching/BidService.java:1361` | YadonyBusinessException | Seules les demandes refusées ou annulées peuvent être supprimées |
| migré | écran | matching | `matching/BidService.java:1383` | YadonyBusinessException | Le refus de colis n'est possible que sur un envoi accepté ou remis |
| migré | écran | matching | `matching/BidService.java:1390` | YadonyBusinessException | Le trajet est parti : le colis ne peut plus être refusé |
| migré | écran | matching | `matching/BidService.java:1449` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/BidService.java:1455` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/BidService.java:1461` | YadonyBusinessException | Demande introuvable |
| migré | écran | matching | `matching/BidService.java:1467` | YadonyBusinessException | Vous n'êtes pas autorisé à effectuer cette action |
| migré | écran | matching | `matching/BidService.java:1474` | YadonyBusinessException | Cette action n'est pas possible pour un bid en statut  |
| migré | écran | matching | `matching/HandoverDeadlineRules.java:65` | YadonyBusinessException | La date limite de remise des colis pour ce trajet est passée |
| migré | écran | matching | `matching/PriceGridController.java:97` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/PriceGridController.java:106` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | matching | `matching/PriceGridService.java:62` | ResponseStatusException | price-grid-limit: maximum 20 articles autorisés |
| migré | écran | matching | `matching/TravelerStatsController.java:77` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TravelerStatsController.java:81` | YadonyBusinessException | Les statistiques sont réservées aux voyageurs PRO. |
| migré | écran | matching | `matching/TravelerStatsController.java:94` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TravelerStatsController.java:97` | YadonyBusinessException | Analytics réservés aux voyageurs PRO. |
| migré | écran | matching | `matching/TravelerStatsController.java:102` | YadonyBusinessException | Période invalide. Valeurs acceptées: month, quarter, year. |
| migré | écran | matching | `matching/TravelerStatsController.java:113` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TravelerStatsController.java:116` | YadonyBusinessException | Calendrier réservé aux voyageurs PRO. |
| migré | écran | matching | `matching/TravelerStatsController.java:139` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TravelerStatsController.java:142` | YadonyBusinessException | Réservé aux voyageurs. |
| migré | écran | matching | `matching/TravelerStatsController.java:153` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TravelerStatsController.java:156` | YadonyBusinessException | Invitations réservées aux voyageurs PRO. |
| migré | écran | matching | `matching/TravelerStatsController.java:162` | YadonyBusinessException | Annonce introuvable. |
| migré | écran | matching | `matching/TravelerStatsController.java:166` | YadonyBusinessException | Cette annonce ne vous appartient pas. |
| migré | écran | matching | `matching/TravelerStatsController.java:172` | YadonyBusinessException | Demande introuvable. |
| migré | écran | matching | `matching/TravelerStatsController.java:176` | YadonyBusinessException | Cette demande n'est plus disponible. |
| migré | écran | matching | `matching/TripGroupService.java:156` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/TripLegRules.java:36` | YadonyBusinessException | Nombre d'étapes invalide / Un voyage compte de  /  étapes |
| migré | écran | matching | `matching/TripRecurrenceController.java:61` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TripRecurrenceService.java:392` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | matching | `matching/TripRecurrenceService.java:407` | YadonyBusinessException | Choisissez au moins un moyen de paiement pour ce trajet. |
| migré | écran | matching | `matching/TripRescheduleController.java:41` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | matching | `matching/TripRescheduleService.java:68` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TripRescheduleService.java:71` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/TripRescheduleService.java:74` | YadonyBusinessException | Vous n'êtes pas autorisé à reporter ce trajet |
| migré | écran | matching | `matching/TripRescheduleService.java:143` | YadonyBusinessException | Reschedule Impossible / Seul un trajet publié et pas encore terminé peut être reporté |
| migré | écran | matching | `matching/TripRescheduleService.java:149` | YadonyBusinessException | Reschedule Impossible / Un colis est déjà en route : le trajet ne peut plus être reporté |
| migré | écran | matching | `matching/TripRescheduleService.java:154` | YadonyBusinessException | Ce trajet a déjà été reporté  /  fois. Annulez-le et publiez un nouveau trajet. |
| migré | écran | matching | `matching/TripRescheduleService.java:164` | YadonyBusinessException | Choisissez une date ou une heure de départ différente de l'actuelle |
| migré | écran | matching | `matching/TripRescheduleService.java:170` | YadonyBusinessException | La nouvelle date de départ doit être dans le futur |
| migré | écran | matching | `matching/TripRescheduleService.java:178` | YadonyBusinessException | La date limite de remise doit être dans le futur |
| migré | écran | matching | `matching/TripSeriesService.java:73` | YadonyBusinessException | La date du trajet d'origine ne peut pas être répétée. |
| migré | écran | matching | `matching/TripStops.java:31` | YadonyBusinessException | Nombre d'escales invalide / Le nombre d'escales doit valoir 0 (direct), 1 ou 2 (deux escales ou plus) |
| migré | écran | matching | `matching/TripsSummaryController.java:72` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/TripsSummaryController.java:77` | YadonyBusinessException | Réservé aux voyageurs. |
| reste | indirect | matching | `matching/ranking/TripRankingProperties.java:39` | IllegalArgumentException | Les poids du classement ne peuvent pas être négatifs |
| migré | écran | matching | `matching/reception/ReceptionService.java:232` | YadonyBusinessException | Ce colis est déjà livré ou n'est plus en cours |
| migré | écran | matching | `matching/reception/ReceptionService.java:330` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/reception/ReceptionService.java:335` | YadonyBusinessException | Colis introuvable |
| migré | écran | matching | `matching/reception/RecipientChangeService.java:107` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/reception/RecipientChangeService.java:110` | YadonyBusinessException | Bid introuvable |
| migré | écran | matching | `matching/reception/RecipientChangeService.java:113` | YadonyBusinessException | Seul l'expéditeur peut modifier le destinataire |
| migré | écran | matching | `matching/reception/RecipientChangeService.java:117` | YadonyBusinessException | Le colis a déjà été remis ou n'est plus en cours : le destinataire ne peut plus changer |
| migré | écran | matching | `matching/reception/RecipientChangeService.java:150` | YadonyBusinessException | Annonce introuvable |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:90` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:93` | YadonyBusinessException | Bid introuvable |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:99` | YadonyBusinessException | Seul le voyageur du colis peut demander un autre destinataire |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:103` | YadonyBusinessException | Le colis a déjà été remis ou n'est plus en cours : le destinataire ne peut plus changer |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:109` | YadonyBusinessException | Le destinataire n'a pas refusé ce colis |
| migré | écran | matching | `matching/reception/RecipientReplacementService.java:116` | YadonyBusinessException | Vous avez déjà prévenu l'expéditeur. Nouvelle demande possible 12 h après la précédente. |
| migré | écran | payments | `payments/ConnectDashboardController.java:41` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | payments | `payments/ConnectDashboardLinkService.java:46` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/ConnectDashboardLinkService.java:97` | YadonyBusinessException | Aucun compte de paiement n'est relié à votre profil. |
| migré | écran | payments | `payments/ConnectDashboardLinkService.java:102` | YadonyBusinessException | Impossible d'ouvrir votre espace Stripe pour le moment. |
| reste | indirect | payments | `payments/EscrowCaptureService.java:105` | IllegalStateException | Paiement introuvable :  |
| reste | indirect | payments | `payments/EscrowCaptureService.java:162` | EscrowCaptureException | le paiement n'est plus en séquestre ( |
| reste | écran | payments | `payments/EscrowScheduler.java:76` | .setDetail |  en séquestre depuis plus de  |
| migré | écran | payments | `payments/FiscalExportController.java:45` | YadonyBusinessException | Année invalide. Plage autorisée: 2020– |
| migré | écran | payments | `payments/FiscalExportController.java:49` | YadonyBusinessException | Format invalide. Valeurs acceptées: pdf, csv. |
| migré | écran | payments | `payments/FiscalExportController.java:53` | YadonyBusinessException | Type invalide. Valeurs acceptées: summary, transactions, dac7. |
| migré | écran | payments | `payments/FiscalExportController.java:90` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/FiscalExportController.java:93` | YadonyBusinessException | Export fiscal réservé aux voyageurs PRO. |
| migré | écran | payments | `payments/PaymentBusy.java:20` | YadonyBusinessException | Paiement en cours de validation, réessayez dans un instant |
| migré | écran | payments | `payments/PaymentController.java:134` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | payments | `payments/PaymentService.java:225` | YadonyBusinessException | Vérifiez votre identité avant d'activer les paiements |
| migré | écran | payments | `payments/PaymentService.java:237` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/PaymentService.java:252` | YadonyBusinessException | Impossible de vérifier le compte de paiement |
| migré | écran | payments | `payments/PaymentService.java:292` | YadonyBusinessException | Impossible de créer le compte de paiement |
| migré | écran | payments | `payments/PaymentService.java:305` | YadonyBusinessException | Créez d'abord un compte Stripe Connect |
| migré | écran | payments | `payments/PaymentService.java:327` | YadonyBusinessException | Votre compte de paiement n'est plus valide. Réessayez pour en créer un nouveau. |
| migré | écran | payments | `payments/PaymentService.java:332` | YadonyBusinessException | Impossible de générer le lien d'onboarding |
| migré | écran | payments | `payments/PaymentService.java:337` | YadonyBusinessException | Impossible de générer le lien d'onboarding |
| migré | écran | payments | `payments/PaymentService.java:378` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/PaymentService.java:388` | YadonyBusinessException | Impossible de changer de pays : votre compte de paiement  / est déjà activé dans ce pays et ne peut plus en ch |
| migré | écran | payments | `payments/PaymentService.java:417` | YadonyBusinessException | Aucun compte Stripe à rafraîchir — créez-en un d'abord |
| migré | écran | payments | `payments/PaymentService.java:465` | YadonyBusinessException | Impossible de récupérer l'état du compte Stripe |
| migré | écran | payments | `payments/PaymentService.java:492` | YadonyBusinessException | La zone CFA n'accepte pas le paiement par carte pour un colis. |
| migré | écran | payments | `payments/PaymentService.java:519` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/PaymentService.java:523` | YadonyBusinessException | Cette demande ne vous appartient pas |
| migré | écran | payments | `payments/PaymentService.java:532` | YadonyBusinessException | Cette demande ne peut plus être payée (statut :  |
| migré | écran | payments | `payments/PaymentService.java:541` | YadonyBusinessException | Le paiement pour cette demande a déjà été effectué |
| migré | écran | payments | `payments/PaymentService.java:557` | YadonyBusinessException | La zone CFA n'accepte pas le paiement par carte pour un colis. |
| migré | écran | payments | `payments/PaymentService.java:582` | YadonyBusinessException | Le paiement pour cette demande a déjà été effectué |
| migré | écran | payments | `payments/PaymentService.java:589` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/PaymentService.java:602` | YadonyBusinessException | Cette annonce n'est plus disponible |
| migré | écran | payments | `payments/PaymentService.java:646` | YadonyBusinessException | Le montant calculé est invalide (≤ 0) |
| migré | écran | payments | `payments/PaymentService.java:662` | YadonyBusinessException | Le taux de commission figé de cette négociation est introuvable |
| migré | écran | payments | `payments/PaymentService.java:704` | YadonyBusinessException | Le taux de commission figé de ce paiement est introuvable |
| migré | écran | payments | `payments/PaymentService.java:763` | YadonyBusinessException | Le paiement pour cette demande a déjà été effectué |
| migré | écran | payments | `payments/PaymentService.java:780` | YadonyBusinessException | Voyageur introuvable |
| migré | écran | payments | `payments/PaymentService.java:793` | YadonyBusinessException | Le montant fourni ne correspond pas au montant calculé pour cette demande |
| migré | écran | payments | `payments/PaymentService.java:900` | YadonyBusinessException | Le voyageur doit reconfigurer son compte de paiement avant de pouvoir recevoir cette demande. |
| migré | écran | payments | `payments/PaymentService.java:911` | YadonyBusinessException | Impossible de créer le paiement. Veuillez réessayer. |
| migré | écran | payments | `payments/PaymentService.java:975` | YadonyBusinessException | Impossible de préparer la fiche de paiement. Veuillez réessayer. |
| migré | écran | payments | `payments/PaymentService.java:1022` | YadonyBusinessException | Paiement introuvable |
| migré | écran | payments | `payments/PaymentService.java:1029` | YadonyBusinessException | Ce paiement ne vous appartient pas |
| migré | écran | payments | `payments/PaymentService.java:1035` | YadonyBusinessException | Le paiement est déjà confirmé, l'option d'enregistrement ne peut plus changer |
| migré | écran | payments | `payments/PaymentService.java:1059` | YadonyBusinessException | Impossible de mettre à jour l'option d'enregistrement de la carte |
| migré | écran | payments | `payments/PaymentService.java:1467` | YadonyBusinessException | Bid introuvable |
| migré | écran | payments | `payments/PaymentService.java:1541` | YadonyBusinessException | Impossible de vérifier le paiement auprès de Stripe |
| migré | écran | payments | `payments/PaymentService.java:1643` | YadonyBusinessException | Vous n'êtes pas autorisé à accéder à ce paiement |
| migré | écran | payments | `payments/PaymentService.java:1666` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/PaymentService.java:1773` | YadonyBusinessException | Impossible de vérifier ou d'annuler le paiement existant auprès de Stripe |
| migré | écran | payments | `payments/PaymentService.java:2150` | YadonyBusinessException | Sender introuvable |
| migré | écran | payments | `payments/PaymentService.java:2159` | YadonyBusinessException | La zone CFA n'accepte pas le paiement par carte pour un colis. |
| migré | écran | payments | `payments/PaymentService.java:2165` | YadonyBusinessException | Voyageur introuvable |
| migré | écran | payments | `payments/PaymentService.java:2187` | YadonyBusinessException | Le montant calculé est invalide (≤ 0) |
| migré | écran | payments | `payments/PaymentService.java:2235` | YadonyBusinessException | Le paiement pour cette négociation a déjà été effectué |
| migré | écran | payments | `payments/PaymentService.java:2262` | YadonyBusinessException | Le paiement pour cette négociation a déjà été effectué |
| migré | écran | payments | `payments/PaymentService.java:2357` | YadonyBusinessException | Échec création PaymentIntent :  |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:160` | YadonyBusinessException | Ce paiement n'a pas de PaymentIntent Stripe : rien à resynchroniser |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:203` | YadonyBusinessException | ) : rien n'a été écrit,  / le paiement reste en séquestre |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:264` | YadonyBusinessException | Paiement introuvable |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:366` | YadonyBusinessException | L'autorisation carte a expiré ou a été annulée chez Stripe : plus rien à capturer.  / Le paiement reste en séq |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:412` | YadonyBusinessException |  introuvable chez Stripe : rien n'a été écrit |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:417` | YadonyBusinessException | Stripe n'a pas pu être interrogé ( / ) : réessayer plus tard |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:422` | YadonyBusinessException | Écart non géré automatiquement : base  / . Rien n'a été écrit, à trancher à la main |
| migré | écran | payments | `payments/PaymentStripeResyncService.java:431` | YadonyBusinessException |  différent du montant attendu  /  : rien n'a été écrit, à trancher à la main |
| reste | écran | payments | `payments/PaymentStripeWebhookHandler.java:258` | YadonyBusinessException | La devise de la recharge wallet est absente des métadonnées Stripe. |
| reste | écran | payments | `payments/PaymentStripeWebhookHandler.java:266` | YadonyBusinessException | La devise débitée par Stripe ne correspond pas à la devise du wallet. |
| reste | indirect | payments | `payments/PaymentStripeWebhookHandler.java:293` | IllegalStateException | L'objet Stripe résolu n'est pas un Charge |
| reste | indirect | payments | `payments/PaymentStripeWebhookHandler.java:307` | IllegalStateException | Stripe n'a retourné aucun Charge |
| reste | écran | payments | `payments/PaymentStripeWebhookHandler.java:313` | YadonyBusinessException | Impossible de résoudre le Charge Stripe du webhook. |
| reste | écran | payments | `payments/PaymentStripeWebhookHandler.java:337` | YadonyBusinessException | L'identifiant utilisateur de la recharge wallet est absent ou invalide  / dans les métadonnées Stripe. |
| reste | indirect | payments | `payments/PaymentStripeWebhookHandler.java:359` | IllegalStateException | L'objet Stripe résolu n'est pas un PaymentIntent |
| reste | indirect | payments | `payments/PaymentStripeWebhookHandler.java:373` | IllegalStateException | Stripe n'a retourné aucun PaymentIntent |
| reste | écran | payments | `payments/PaymentStripeWebhookHandler.java:379` | YadonyBusinessException | Impossible de résoudre le PaymentIntent Stripe du webhook. |
| migré | écran | payments | `payments/PrePaymentReleaseAdapter.java:208` | YadonyBusinessException | Impossible de joindre le service de paiement. Réessayez dans un instant. |
| reste | indirect | payments | `payments/StripeReleaseExecutor.java:192` | IllegalStateException |  déjà réservé ( |
| reste | indirect | payments | `payments/StripeReleaseExecutor.java:269` | IllegalStateException |  demandé sous transaction : refusé (PROD_CHECKLIST 9.23) |
| reste | indirect | payments | `payments/StripeReleaseExecutor.java:673` | IllegalStateException | Annulation de la réservation  /  sous transaction : refusée |
| migré | écran | payments | `payments/StripeV2AccountProvisioner.java:89` | YadonyBusinessException | Le paiement par carte n'est pas encore disponible dans votre pays.  / Vous pouvez continuer a recevoir vos pai |
| migré | écran | payments | `payments/StripeV2AccountProvisioner.java:113` | YadonyBusinessException | Ajoutez une adresse e-mail à votre compte avant de créer votre  |
| migré | écran | payments | `payments/amendment/BidAmendmentCapacity.java:61` | YadonyBusinessException | Le trajet n'a plus assez de kilos libres pour cet ajout |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:121` | YadonyBusinessException | Impossible de bloquer le montant de la modification. Veuillez réessayer. |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:142` | YadonyBusinessException | Expéditeur introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:186` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:191` | YadonyBusinessException | Vous n'êtes pas partie à cette modification |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:195` | YadonyBusinessException | Cette modification n'a pas de montant carte à confirmer |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:285` | YadonyBusinessException | Le montant de cette modification n'a pas pu être débité : la demande est annulée |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:412` | YadonyBusinessException | Impossible de vérifier le paiement auprès de Stripe |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:417` | YadonyBusinessException | Modification introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentCardRail.java:422` | YadonyBusinessException | Cette modification n'attend plus de réponse |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:260` | YadonyBusinessException | Le débit de la commission n'a pas pu aboutir. Veuillez réessayer. |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:300` | YadonyBusinessException | Un prélèvement de votre commission est encore en cours de traitement : réessayez dans quelques minutes |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:313` | YadonyBusinessException | Impossible de vérifier le paiement auprès de Stripe |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:332` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:337` | YadonyBusinessException | Vous n'êtes pas partie à cette modification |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:341` | YadonyBusinessException | Cette modification n'a pas de commission à confirmer |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:476` | YadonyBusinessException | Votre carte commission a été refusée : rien n'a été prélevé, vous pouvez réessayer d'accepter |
| migré | écran | payments | `payments/amendment/BidAmendmentCashRail.java:482` | YadonyBusinessException | Modification introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentController.java:127` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | payments | `payments/amendment/BidAmendmentEligibility.java:158` | YadonyBusinessException | Ce colis ne peut pas être modifié ( |
| reste | indirect | payments | `payments/amendment/BidAmendmentRailHandler.java:28` | IllegalArgumentException | Statut initial d'une modification invalide :  |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:291` | YadonyBusinessException | Un paiement de cette modification est en cours de traitement |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:358` | YadonyBusinessException | Cette modification n'est pas acceptée |
| reste | indirect | payments | `payments/amendment/BidAmendmentService.java:711` | IllegalArgumentException | Clôture vers  /  non prévue |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:723` | YadonyBusinessException | Modification introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:735` | YadonyBusinessException | Le délai de réponse à cette modification est dépassé |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:766` | YadonyBusinessException | Le montant de la modification est hors des limites autorisées |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:835` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:869` | YadonyBusinessException | Transaction introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:874` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:879` | YadonyBusinessException | Vous n'êtes pas partie à cette modification |
| migré | écran | payments | `payments/amendment/BidAmendmentService.java:884` | YadonyBusinessException | Cette modification n'attend plus de réponse |
| migré | écran | payments | `payments/cash/CashCommissionController.java:105` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/cash/CashCommissionController.java:112` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | payments | `payments/cash/CashCommissionService.java:358` | YadonyBusinessException | Le montant calculé est invalide (≤ 0) |
| migré | écran | payments | `payments/cash/CashCommissionService.java:376` | YadonyBusinessException | Le setup intent n'a pas encore de méthode de paiement attachée |
| migré | écran | payments | `payments/cash/CashCommissionService.java:385` | YadonyBusinessException | La méthode de paiement fournie n'est pas une carte |
| reste | indirect | payments | `payments/cash/CashCommissionService.java:396` | RuntimeException | Impossible de récupérer les données Stripe |
| migré | écran | payments | `payments/cash/CashCommissionService.java:544` | CommissionChargeFailedException | Erreur Stripe lors du débit de commission |
| migré | écran | payments | `payments/cash/CashCommissionService.java:881` | YadonyBusinessException | Ce fil de négociation ne vous appartient pas |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1214` | YadonyBusinessException | Devise de prélèvement inconnue |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1243` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1246` | YadonyBusinessException | Ce trajet ne vous appartient pas |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1250` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1253` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1257` | YadonyBusinessException | Ce trajet ne vous appartient pas |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1261` | InvalidPaymentMethodForAnnouncementException | Cette annonce n'accepte pas le paiement en espèces |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1265` | InvalidPaymentMethodForAnnouncementException | Ce bid n'est pas un bid cash |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1278` | YadonyBusinessException | Cette demande n'attend plus de réponse |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1294` | YadonyBusinessException | Ce trajet n'accepte plus de colis |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1307` | YadonyBusinessException | Capacité insuffisante pour accepter cette demande |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1424` | YadonyBusinessException | Impossible de vérifier le paiement par carte en cours de cette commission |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1444` | YadonyBusinessException | Un paiement par carte de cette commission est encore en cours. Réessayez dans un instant. |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1458` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1461` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1464` | YadonyBusinessException | Ce trajet ne vous appartient pas |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1468` | YadonyBusinessException | Cette demande n'est plus en attente de réponse |
| migré | écran | payments | `payments/cash/CashCommissionService.java:1492` | YadonyBusinessException | Ce trajet ne vous appartient pas |
| migré | écran | payments | `payments/cash/WalletCommissionCollector.java:449` | YadonyBusinessException | Devise de prélèvement inconnue |
| reste | indirect | payments | `payments/cash/WalletCommissionCollector.java:465` | IllegalStateException | Commission non couverte par les portefeuilles : aucun débit |
| migré | écran | payments | `payments/currency/ExchangeRateLookup.java:33` | YadonyBusinessException | Aucun taux de change n'est configure pour la devise  |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:94` | YadonyBusinessException |  a une parite fixe avec l'euro (655,957), elle ne se pilote pas depuis cet ecran. |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:103` | YadonyBusinessException | Aucun taux de change n'existe pour la devise  |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:112` | YadonyBusinessException | Le nouveau taux s'écarte de plus de 10 % du taux en place : confirmez la saisie. |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:145` | YadonyBusinessException | Le taux de change est obligatoire |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:150` | YadonyBusinessException | Le taux de change doit etre strictement positif |
| migré | écran | payments | `payments/currency/ExchangeRateUpdateService.java:156` | YadonyBusinessException | Le taux de change depasse la borne maximale autorisee ( |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyAcceptedOperatorsService.java:41` | YadonyBusinessException | Voyageur introuvable |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyAccountService.java:163` | YadonyBusinessException | Numéro de téléphone invalide pour le versement mobile money. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyAccountService.java:173` | YadonyBusinessException | Indiquez le numéro mobile money qui recevra vos versements. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyAccountService.java:253` | YadonyBusinessException | Utilisateur introuvable :  |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:188` | YadonyBusinessException | Ce trajet ne vous appartient pas |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:191` | YadonyBusinessException | Ce colis n'est pas payé en mobile money |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:203` | YadonyBusinessException | Ce colis n'est plus en attente de réponse |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:217` | YadonyBusinessException | Votre compte de versement est en  / , ce trajet est en  |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:225` | YadonyBusinessException | Ce trajet n'accepte plus de colis |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:232` | YadonyBusinessException | Capacité insuffisante pour accepter cette demande |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:404` | YadonyBusinessException | Indiquez le numéro mobile money qui paiera. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:421` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce colis |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:424` | YadonyBusinessException | Ce paiement n'est plus en attente ( |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:430` | YadonyBusinessException | Le délai de paiement est dépassé. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:465` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de ce colis |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:556` | IllegalStateException | Paiement introuvable :  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:567` | IllegalStateException | Bid introuvable :  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:583` | IllegalStateException | Annonce introuvable :  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:623` | IllegalStateException | Paiement PAWAPAY introuvable pour le bid  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:626` | IllegalStateException | Deposit pawaPay COMPLETED introuvable pour le paiement  |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPaymentService.java:864` | YadonyBusinessException | Vous n'avez pas accès à ce paiement |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyBidPricing.java:142` | YadonyBusinessException | Le montant calculé est invalide (≤ 0) |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyCountriesController.java:37` | YadonyBusinessException | L'opération doit être DEPOSIT ou PAYOUT. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:142` | YadonyBusinessException | Un paiement par carte est déjà en cours sur cette négociation. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:149` | YadonyBusinessException | Ce paiement n'est plus en attente ( |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:202` | YadonyBusinessException | Ce paiement n'est plus en attente ( |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:206` | YadonyBusinessException | Le délai de paiement est dépassé. |
| migré | écran | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:280` | YadonyBusinessException | Indiquez le numéro mobile money qui paiera. |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:365` | IllegalStateException | Paiement PAWAPAY introuvable pour le fil  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:368` | IllegalStateException | Deposit pawaPay COMPLETED introuvable pour le paiement  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:384` | IllegalStateException | Séquestre sans deposit COMPLETED : paiement  |
| reste | indirect | payments | `payments/mobilemoney/MobileMoneyNegotiationPaymentService.java:401` | IllegalStateException | Paiement introuvable :  |
| migré | écran | payments | `payments/overview/MoneyOverviewService.java:96` | YadonyBusinessException | Utilisateur introuvable |
| reste | indirect | payments | `payments/pawapay/PawapayClient.java:109` | RestClientException |  : FOUND avec un statut de donnée non mappable |
| reste | indirect | payments | `payments/pawapay/PawapayClient.java:244` | RestClientException |  réponse vide |
| reste | indirect | payments | `payments/pawapay/PawapayClient.java:249` | RestClientException |  réponse illisible |
| reste | indirect | payments | `payments/pawapay/PawapayClient.java:266` | RestClientException |  réponse illisible |
| migré | écran | payments | `payments/pawapay/PawapayErrors.java:21` | YadonyBusinessException | Le mobile money n'est pas encore disponible. |
| migré | écran | payments | `payments/pawapay/PawapayErrors.java:33` | YadonyBusinessException | Une recharge est déjà en attente de validation sur votre téléphone. |
| migré | écran | payments | `payments/pawapay/PawapayErrors.java:42` | YadonyBusinessException | Le service mobile money ne répond pas. Réessayez dans quelques instants. |
| reste | indirect | payments | `payments/pawapay/PawapayOperationService.java:87` | IllegalArgumentException | Opération de supplément sans utilisateur ou sans supplément |
| reste | indirect | payments | `payments/pawapay/PawapayOperationService.java:231` | IllegalStateException | pawaPay : opération disparue juste après sa transition  |
| reste | indirect | payments | `payments/pawapay/PawapayOperationService.java:270` | IllegalStateException | Opération pawaPay introuvable :  |
| migré | écran | payments | `payments/pawapay/PawapayOperationService.java:274` | YadonyBusinessException | Une opération  /  est déjà en cours pour ce paiement |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:94` | PawapaySignatureException | Paramètre alg absent |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:95` | PawapaySignatureException | Paramètre keyid absent |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:106` | PawapaySignatureException | En-tête couvert absent :  |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:117` | PawapaySignatureException | Signature invalide |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:130` | PawapaySignatureException | Paramètre created absent |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:133` | PawapaySignatureException | Signature-Input : created dans le futur (dérive d'horloge) |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:136` | PawapaySignatureException | Signature-Input : created trop ancien |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:142` | PawapaySignatureException | Signature expirée |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:152` | PawapaySignatureException | Paramètre  |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:162` | PawapaySignatureException | alg non supporté :  |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:187` | PawapaySignatureException | alg non supporté :  |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:202` | PawapaySignatureException | Content-Digest : algorithme non supporté  |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:208` | PawapaySignatureException | Content-Digest ne correspond pas au corps |
| reste | indirect | payments | `payments/pawapay/PawapaySignatureVerifier.java:254` | PawapaySignatureException | En-tête  |
| reste | indirect | payments | `payments/pawapay/PawapaySubmissionService.java:145` | IllegalArgumentException | Un dépôt s'initie par submitDeposit :  |
| migré | écran | payments | `payments/split/PaymentSplitService.java:178` | YadonyBusinessException | La somme des deux parts dépasse le net disponible ( |
| migré | écran | payments | `payments/split/PaymentSplitService.java:200` | YadonyBusinessException | Impossible de lire le paiement chez Stripe, réessayez. |
| migré | écran | payments | `payments/split/PaymentSplitService.java:221` | YadonyBusinessException | Le paiement a quitté le séquestre entre-temps : partage refusé. |
| reste | indirect | payments | `payments/split/PaymentSplitService.java:336` | IllegalStateException | Capture partielle incohérente : statut  / , encaissé  |
| migré | écran | payments | `payments/split/PaymentSplitService.java:379` | YadonyBusinessException | Les versements de ce voyageur sont gelés : part voyageur impossible (mettez-la à 0). |
| migré | écran | payments | `payments/split/PaymentSplitService.java:424` | YadonyBusinessException | Chargeback en cours sur ce paiement : partage refusé. |
| migré | écran | payments | `payments/split/PaymentSplitService.java:426` | YadonyBusinessException | Un partage existe déjà pour ce paiement. |
| migré | écran | payments | `payments/supplement/BidSupplementAdminService.java:80` | YadonyBusinessException | Ce supplément ne peut pas être versé (statut  |
| migré | écran | payments | `payments/supplement/BidSupplementAdminService.java:98` | YadonyBusinessException | Ce supplément est déjà versé au voyageur : pas de remboursement automatique, à traiter  |
| migré | écran | payments | `payments/supplement/BidSupplementAdminService.java:104` | YadonyBusinessException | Ce supplément ne peut pas être remboursé (statut  |
| migré | écran | payments | `payments/supplement/BidSupplementAdminService.java:129` | YadonyBusinessException | Supplément en espèces : l'argent n'est jamais passé par Yadony, rien à verser ni à rembourser |
| migré | écran | payments | `payments/supplement/BidSupplementAdminService.java:137` | YadonyBusinessException | Supplément introuvable |
| reste | indirect | payments | `payments/supplement/BidSupplementCardPayouts.java:113` | IllegalStateException | charge du supplément introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:121` | YadonyBusinessException | Le paiement du supplément est en cours de traitement |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:146` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:151` | YadonyBusinessException | Vous n'êtes pas partie à ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:208` | YadonyBusinessException | Expéditeur introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:239` | YadonyBusinessException | Impossible de créer le paiement du supplément. Veuillez réessayer. |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:255` | YadonyBusinessException | Impossible de vérifier le paiement auprès de Stripe |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:273` | YadonyBusinessException | Demande de supplément introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementCardRail.java:278` | YadonyBusinessException | Cette demande de supplément n'attend plus de paiement |
| reste | indirect | payments | `payments/supplement/BidSupplementCardRefunds.java:68` | IllegalStateException | PaymentIntent du supplément absent |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:242` | YadonyBusinessException | Le débit de la commission n'a pas pu aboutir. Veuillez réessayer. |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:253` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:258` | YadonyBusinessException | Vous n'êtes pas partie à ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:279` | YadonyBusinessException | Impossible de vérifier le paiement auprès de Stripe |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:368` | YadonyBusinessException | Votre carte commission a été refusée : rien n'a été prélevé et la demande est annulée |
| migré | écran | payments | `payments/supplement/BidSupplementCashRail.java:374` | YadonyBusinessException | Demande de supplément introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementController.java:143` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:102` | YadonyBusinessException | Ce supplément ne se paie pas en mobile money |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:116` | YadonyBusinessException | Ce colis n'attend plus de réponse à un supplément |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:129` | YadonyBusinessException | Le délai de réponse à cette demande de supplément est dépassé |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:332` | YadonyBusinessException | Demande de supplément introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:337` | YadonyBusinessException | Vous n'êtes pas partie à ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyLedger.java:342` | YadonyBusinessException | Cette demande de supplément n'attend plus de paiement |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyRail.java:237` | YadonyBusinessException | Indiquez le numéro mobile money qui paiera. |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyRail.java:259` | YadonyBusinessException | Ce supplément ne se paie pas en mobile money |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyRail.java:354` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyRail.java:360` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementMobileMoneyRail.java:362` | YadonyBusinessException | Voyageur introuvable |
| reste | indirect | payments | `payments/supplement/BidSupplementPricing.java:38` | IllegalArgumentException | Le net du supplément doit être strictement positif |
| migré | écran | payments | `payments/supplement/BidSupplementRail.java:54` | YadonyBusinessException | Un supplément n'est pas encore possible avec ce moyen de paiement |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:193` | YadonyBusinessException | Le nombre maximum de demandes de supplément pour ce colis est atteint |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:518` | YadonyBusinessException | Ce colis n'attend plus de réponse à un supplément |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:528` | YadonyBusinessException | Le délai de réponse à cette demande de supplément est dépassé |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:542` | YadonyBusinessException | Le montant du supplément est hors des limites autorisées |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:560` | YadonyBusinessException | La photo doit être une clé de stockage valide pour ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:576` | YadonyBusinessException | La demande de supplément n'est pas disponible |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:594` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:599` | YadonyBusinessException | Transaction introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:604` | YadonyBusinessException | Annonce introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:609` | YadonyBusinessException | Demande de supplément introuvable |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:635` | YadonyBusinessException | Vous n'êtes pas partie à ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:640` | YadonyBusinessException | Cette demande de supplément n'attend plus de réponse |
| migré | écran | payments | `payments/supplement/BidSupplementService.java:645` | YadonyBusinessException | Une demande de supplément est déjà en cours pour ce colis |
| migré | écran | payments | `payments/supplement/BidSupplementWindow.java:57` | YadonyBusinessException | Un supplément ne se demande que sur un colis accepté ou remis avant le départ |
| migré | écran | payments | `payments/supplement/BidSupplementWindow.java:60` | YadonyBusinessException | Le départ est trop proche pour demander un supplément |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:212` | YadonyBusinessException | Une correction doit être approuvée par un autre admin que son auteur |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:289` | YadonyBusinessException | Solde gelé : une demande de remboursement est en cours sur cette devise |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:295` | YadonyBusinessException | , montant à débiter :  |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:363` | YadonyBusinessException | Les corrections ne peuvent pas dépasser 500 € d'équivalent sur 24 h,  |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:395` | YadonyBusinessException | Aucun portefeuille dans cette devise : rien à débiter |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:428` | YadonyBusinessException | Une correction ne peut pas dépasser 500 € d'équivalent par opération |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:460` | YadonyBusinessException | Demande de correction introuvable |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:465` | YadonyBusinessException | Cette demande a déjà été approuvée ou rejetée |
| migré | écran | payments | `payments/wallet/WalletAdminAdjustmentService.java:534` | YadonyBusinessException | Cette clé d'idempotence a déjà servi à une autre correction |
| migré | écran | payments | `payments/wallet/WalletController.java:216` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | payments | `payments/wallet/WalletExternalRefundService.java:112` | YadonyBusinessException | Indiquez comment le solde a été régularisé ( /  caractères au plus) |
| migré | écran | payments | `payments/wallet/WalletExternalRefundService.java:118` | YadonyBusinessException | Gel introuvable |
| migré | écran | payments | `payments/wallet/WalletExternalRefundService.java:121` | YadonyBusinessException | Ce gel a déjà été levé |
| migré | écran | payments | `payments/wallet/WalletMobileMoneyTopupService.java:167` | YadonyBusinessException | Recharge introuvable. |
| migré | écran | payments | `payments/wallet/WalletMobileMoneyTopupService.java:235` | YadonyBusinessException | Indiquez le numéro mobile money qui paie la recharge. |
| migré | écran | payments | `payments/wallet/WalletMobileMoneyTopupService.java:242` | YadonyBusinessException | Numéro de téléphone invalide pour la recharge. |
| reste | indirect | payments | `payments/wallet/WalletRefundRail.java:27` | IllegalArgumentException | paymentRef n'est pas un rail pawaPay :  |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:78` | YadonyBusinessException | Aucun solde à rembourser |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:276` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:285` | YadonyBusinessException | Un remboursement mobile money est en cours pour cette demande :  / attendez son issue avant de la résoudre à l |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:290` | YadonyBusinessException | Cette demande a déjà été traitée |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:367` | YadonyBusinessException | Le montant versé doit être strictement positif |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:372` | YadonyBusinessException | Le montant versé dépasse le montant de la demande ( |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:380` | YadonyBusinessException | Le montant versé a trop de décimales pour la devise  |
| migré | écran | payments | `payments/wallet/WalletRefundRequestService.java:394` | YadonyBusinessException | La note dépasse  /  caractères |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:364` | YadonyBusinessException | Le montant à verser doit être strictement positif |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:369` | YadonyBusinessException | Le montant a trop de décimales pour la devise  |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:374` | YadonyBusinessException | Le montant dépasse le montant dû ( |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:379` | YadonyBusinessException | La relance verse le montant dû entier ( / ) : un versement partiel ferait perdre la différence à l'utilisateur |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:397` | YadonyBusinessException | Le wallet pawaPay  /  ne couvre pas le montant dû ( /  en sécurité) : alimentez-le d'au moins  |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:409` | YadonyBusinessException | Demande introuvable |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:419` | YadonyBusinessException | Un remboursement mobile money est déjà en cours pour cette demande |
| migré | écran | payments | `payments/wallet/WalletRefundRetryService.java:424` | YadonyBusinessException | Solde du wallet pawaPay illisible : réessayez dans quelques instants |
| migré | écran | payments | `payments/wallet/WalletService.java:87` | YadonyBusinessException | Solde gelé - une demande de remboursement est en cours sur cette devise |
| migré | écran | payments | `payments/wallet/WalletService.java:94` | YadonyBusinessException | Solde bloqué : une recharge de cette devise fait l'objet d'une contestation  |
| migré | écran | payments | `payments/wallet/WalletService.java:104` | YadonyBusinessException | Solde bloqué : une recharge de cette devise vous a déjà été remboursée sur  |
| reste | indirect | payments | `payments/wallet/WalletService.java:192` | IllegalStateException | Wallet introuvable après création : user= |
| reste | indirect | payments | `payments/wallet/WalletService.java:275` | IllegalStateException | Wallet introuvable après création : user= |
| reste | indirect | payments | `payments/wallet/WalletService.java:289` | IllegalArgumentException |  invalide :  |
| migré | écran | payments | `payments/wallet/WalletTopupOrchestrator.java:108` | YadonyBusinessException | Impossible de préparer la recharge du wallet. Veuillez réessayer. |
| migré | écran | payments | `payments/wallet/WalletTopupOrchestrator.java:124` | YadonyBusinessException | Le rechargement par mobile money n'est plus disponible pour le moment.  |
| migré | écran | payments | `payments/wallet/WalletTopupOrchestrator.java:172` | YadonyBusinessException | Impossible de préparer la recharge du wallet. Veuillez réessayer. |
| migré | écran | payments | `payments/wallet/WalletTopupOrchestrator.java:183` | YadonyBusinessException | Une recharge par carte est limitée à  |
| reste | indirect | payments | `payments/wallet/fees/PawapayRefundSafeAmount.java:68` | IllegalArgumentException | Taux de frais négatif :  |
| reste | indirect | payments | `payments/wallet/topupcode/TopupCodeHasher.java:52` | IllegalStateException | TOPUP_CODE_HMAC_KEY est obligatoire hors dev/test (profils actifs :  |
| reste | indirect | payments | `payments/wallet/topupcode/TopupCodeHasher.java:58` | IllegalStateException | TOPUP_CODE_HMAC_KEY trop courte :  /  caractères au moins (openssl rand -hex 32) |
| reste | indirect | payments | `payments/wallet/topupcode/WalletTopupCodeAdminService.java:140` | IllegalStateException | Impossible de générer un code de recharge libre |
| migré | écran | payments | `payments/wallet/topupcode/WalletTopupCodeAdminService.java:173` | YadonyBusinessException | Un code ne peut pas dépasser 500 € d'équivalent |
| migré | écran | payments | `payments/wallet/topupcode/WalletTopupCodeAdminService.java:190` | YadonyBusinessException | Un code de recharge existe déjà avec ce code |
| migré | écran | payments | `payments/wallet/topupcode/WalletTopupCodeAdminService.java:195` | YadonyBusinessException | Code de recharge introuvable |
| migré | écran | payments | `payments/wallet/topupcode/WalletTopupCodeController.java:47` | YadonyBusinessException | Utilisateur introuvable |
| reste | indirect | payments | `payments/wallet/topupcode/WalletTopupCodeRedemptionService.java:141` | IllegalStateException | Crédit du code introuvable :  |
| migré | écran | ratings | `ratings/RatingService.java:105` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/RatingService.java:109` | YadonyBusinessException | Envoi introuvable |
| migré | écran | ratings | `ratings/RatingService.java:113` | YadonyBusinessException | Vous n'êtes pas l'expéditeur de cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:118` | YadonyBusinessException | La livraison n'a pas encore été confirmée pour cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:123` | YadonyBusinessException | La fenêtre de notation est expirée |
| migré | écran | ratings | `ratings/RatingService.java:128` | YadonyBusinessException | Vous avez déjà noté ce voyageur pour cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:157` | YadonyBusinessException | Lien de suivi invalide |
| migré | écran | ratings | `ratings/RatingService.java:161` | YadonyBusinessException | La livraison n'a pas encore été confirmée |
| migré | écran | ratings | `ratings/RatingService.java:166` | YadonyBusinessException | Vous avez déjà noté ce voyageur |
| migré | écran | ratings | `ratings/RatingService.java:195` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/RatingService.java:199` | YadonyBusinessException | Envoi introuvable |
| migré | écran | ratings | `ratings/RatingService.java:203` | YadonyBusinessException | Annonce introuvable pour cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:208` | YadonyBusinessException | Vous n'êtes pas le voyageur de cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:213` | YadonyBusinessException | La livraison n'a pas encore été confirmée pour cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:218` | YadonyBusinessException | La fenêtre de notation est expirée |
| migré | écran | ratings | `ratings/RatingService.java:223` | YadonyBusinessException | Vous avez déjà noté cet expéditeur pour cet envoi |
| migré | écran | ratings | `ratings/RatingService.java:251` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/RatingService.java:277` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/RatingService.java:347` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/RatingService.java:356` | YadonyBusinessException | Annonce introuvable |
| migré | écran | ratings | `ratings/RatingService.java:408` | YadonyBusinessException | Annonce introuvable pour cet envoi |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:73` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:78` | YadonyBusinessException | Vous n'êtes pas le destinataire confirmé de ce colis |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:83` | YadonyBusinessException | Colis introuvable |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:87` | YadonyBusinessException | Le voyageur se note une fois le colis livré |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:97` | YadonyBusinessException | Annonce introuvable pour cet envoi |
| migré | écran | ratings | `ratings/ReceptionRatingService.java:131` | YadonyBusinessException | Vous avez déjà noté le voyageur pour ce colis |
| migré | écran | tracking | `tracking/PickupCodeRequestService.java:96` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/PickupCodeRequestService.java:100` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/PickupCodeRequestService.java:107` | YadonyBusinessException | Seul le voyageur du colis peut demander un nouveau code de retrait |
| reste | écran | tracking | `tracking/RecipientController.java:131` | ProblemDetail.forStatusAndDetail | Le destinataire de ce colis a changé. Ce lien de suivi n'est plus valide. |
| migré | écran | tracking | `tracking/TrackingService.java:134` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:139` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:149` | YadonyBusinessException | Accès interdit à ce QR code |
| migré | écran | tracking | `tracking/TrackingService.java:155` | YadonyBusinessException | Le QR code n'est pas encore disponible pour cette transaction |
| migré | écran | tracking | `tracking/TrackingService.java:169` | YadonyBusinessException | Aucun colis trouvé avec le numéro :  |
| migré | écran | tracking | `tracking/TrackingService.java:174` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:182` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:189` | YadonyBusinessException | Ce colis n'est pas le vôtre |
| migré | écran | tracking | `tracking/TrackingService.java:289` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:294` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:299` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:304` | YadonyBusinessException | Seul le voyageur de cette annonce peut scanner le QR code |
| migré | écran | tracking | `tracking/TrackingService.java:324` | YadonyBusinessException | Ce colis n'est pas dans un état scannable |
| migré | écran | tracking | `tracking/TrackingService.java:329` | YadonyBusinessException | Le QR code n'est pas encore disponible |
| migré | écran | tracking | `tracking/TrackingService.java:334` | YadonyBusinessException | L'arrivée doit être confirmée avec le code de confirmation fourni par l'expéditeur |
| migré | écran | tracking | `tracking/TrackingService.java:345` | YadonyBusinessException | Le timestamp du scan ne peut pas être dans le futur |
| migré | écran | tracking | `tracking/TrackingService.java:361` | YadonyBusinessException | Scannez d'abord le départ du colis |
| migré | écran | tracking | `tracking/TrackingService.java:494` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:498` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:502` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:506` | YadonyBusinessException | Seul le voyageur de cette annonce peut scanner le QR code |
| migré | écran | tracking | `tracking/TrackingService.java:512` | YadonyBusinessException | Ce scan est déjà en cours d'enregistrement, réessayez |
| migré | écran | tracking | `tracking/TrackingService.java:520` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:525` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:530` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:541` | YadonyBusinessException | Accès interdit à ces événements de tracking |
| migré | écran | tracking | `tracking/TrackingService.java:553` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:558` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:563` | YadonyBusinessException | Accès interdit à l'historique de ce trajet |
| migré | écran | tracking | `tracking/TrackingService.java:645` | YadonyBusinessException | L'URL de la photo doit être une clé S3 valide pour ce bid |
| migré | écran | tracking | `tracking/TrackingService.java:665` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:670` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:675` | YadonyBusinessException | Seul l'expéditeur peut consulter le code de confirmation |
| migré | écran | tracking | `tracking/TrackingService.java:688` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:693` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:698` | YadonyBusinessException | Seul l'expéditeur peut publier le code de confirmation |
| migré | écran | tracking | `tracking/TrackingService.java:703` | YadonyBusinessException | Le code de confirmation n'est pas encore disponible — le voyageur doit d'abord scanner le départ |
| migré | écran | tracking | `tracking/TrackingService.java:742` | YadonyBusinessException | Trop d'essais incorrects : ce code est bloqué. Demandez à l'expéditeur d'en générer un nouveau dans l'app |
| migré | écran | tracking | `tracking/TrackingService.java:750` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:755` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:760` | YadonyBusinessException | Seul l'expéditeur peut régénérer le code de confirmation |
| migré | écran | tracking | `tracking/TrackingService.java:768` | YadonyBusinessException | Ce colis ne peut pas recevoir un nouveau code dans son état actuel |
| migré | écran | tracking | `tracking/TrackingService.java:779` | YadonyBusinessException | Le code de confirmation n'est pas encore disponible — le voyageur doit d'abord scanner le départ |
| migré | écran | tracking | `tracking/TrackingService.java:789` | YadonyBusinessException | Limite atteinte : 5 régénérations par 24h. Patientez avant de réessayer. |
| migré | écran | tracking | `tracking/TrackingService.java:800` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:830` | YadonyBusinessException | Transaction introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:835` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:840` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TrackingService.java:845` | YadonyBusinessException | Seul le voyageur de cette annonce peut confirmer la livraison |
| migré | écran | tracking | `tracking/TrackingService.java:853` | YadonyBusinessException | Ce colis ne peut pas être confirmé dans son état actuel |
| migré | écran | tracking | `tracking/TrackingService.java:866` | YadonyBusinessException | Aucun code de retrait valide : demandez à l'expéditeur d'en générer un nouveau dans l'app |
| migré | écran | tracking | `tracking/TrackingService.java:870` | YadonyBusinessException | Le code de confirmation n'est pas encore disponible — scannez d'abord le départ du colis |
| migré | écran | tracking | `tracking/TrackingService.java:882` | YadonyBusinessException | Le code de confirmation a expiré — demandez à l'expéditeur de vous partager un nouveau code |
| migré | écran | tracking | `tracking/TrackingService.java:984` | YadonyBusinessException | Saisissez le numéro de suivi du colis, donné par l'expéditeur |
| migré | écran | tracking | `tracking/TrackingService.java:992` | YadonyBusinessException | Ce numéro de suivi ne correspond pas à ce colis |
| migré | écran | tracking | `tracking/TrackingService.java:1015` | YadonyBusinessException | Erreur lors de la génération du QR code |
| migré | écran | tracking | `tracking/TripPositionService.java:190` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | tracking | `tracking/TripPositionService.java:197` | YadonyBusinessException | Annonce introuvable |
| migré | écran | tracking | `tracking/TripPositionService.java:200` | YadonyBusinessException | Vous n'êtes pas autorisé à modifier ce trajet |
| migré | écran | activation | `activation/ActivationService.java:57` | YadonyBusinessException | La source INFERRED est réservée au serveur |
| migré | écran | activation | `activation/ActivationService.java:63` | YadonyBusinessException | Ce pays de destination n'est pas pris en charge |
| migré | écran | activation | `activation/ActivationService.java:122` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | address | `address/AddressController.java:77` | YadonyBusinessException | Aucune adresse trouvée pour ces coordonnées. |
| migré | écran | address | `address/AddressController.java:93` | YadonyBusinessException | Limite de recherche d'adresse atteinte. Réessayez dans 1 minute. |
| reste | écran | address | `address/GoogleAddressService.java:195` | YadonyBusinessException | Le service de recherche d'adresse est suspendu jusqu'a demain. Utilisez le bouton GPS pour indiquer votre posi |
| reste | écran | address | `address/GoogleAddressService.java:208` | YadonyBusinessException | Réponse Google incomplète |
| reste | écran | address | `address/GoogleAddressService.java:216` | YadonyBusinessException | Réponse Google incomplète |
| reste | écran | address | `address/GoogleAddressService.java:276` | YadonyBusinessException | Réponse Google incomplète |
| reste | écran | address | `address/GoogleAddressService.java:282` | YadonyBusinessException | Réponse Google incomplète |
| reste | écran | address | `address/GoogleAddressService.java:288` | YadonyBusinessException | Réponse Google incomplète |
| reste | écran | address | `address/GoogleAddressService.java:313` | YadonyBusinessException | Réponse Google vide |
| reste | écran | address | `address/GoogleAddressService.java:339` | YadonyBusinessException | Réponse Google vide |
| reste | écran | address | `address/GoogleAddressService.java:356` | YadonyBusinessException | Requête Google invalide |
| reste | écran | address | `address/GoogleAddressService.java:360` | YadonyBusinessException | Accès Google Places refusé |
| reste | écran | address | `address/GoogleAddressService.java:364` | YadonyBusinessException | Lieu introuvable |
| reste | écran | address | `address/GoogleAddressService.java:368` | YadonyBusinessException | Quota Google dépassé |
| reste | écran | address | `address/GoogleAddressService.java:383` | YadonyBusinessException | Réponse Google vide |
| reste | écran | address | `address/GoogleAddressService.java:404` | YadonyBusinessException | Requête Google invalide |
| reste | écran | address | `address/GoogleAddressService.java:409` | YadonyBusinessException | Accès Google Geocoding refusé |
| reste | écran | address | `address/GoogleAddressService.java:414` | YadonyBusinessException | Quota Google dépassé |
| migré | écran | addressbook | `addressbook/delivery/DeliveryAddressController.java:66` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | addressbook | `addressbook/invitation/RecipientInvitationService.java:99` | YadonyBusinessException | Vous avez envoyé trop d'invitations aujourd'hui. Réessayez demain. |
| migré | écran | addressbook | `addressbook/invitation/RecipientInvitationService.java:216` | YadonyBusinessException | Ajoutez un numéro de téléphone à votre profil pour accepter cette invitation. |
| migré | écran | addressbook | `addressbook/invitation/RecipientInvitationService.java:350` | YadonyBusinessException | Le nom ne peut pas dépasser 100 caractères. |
| migré | écran | addressbook | `addressbook/invitation/RecipientInvitationService.java:375` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | addressbook | `addressbook/pickup/PickupAddressController.java:77` | YadonyNotFoundException | Utilisateur introuvable |
| migré | écran | addressbook | `addressbook/recipient/RecipientController.java:69` | YadonyNotFoundException | Utilisateur introuvable |
| reste | écran | alerts | `alerts/AlertService.java:90` | YadonyBusinessException | Vous avez atteint la limite de  |
| reste | écran | alerts | `alerts/AlertService.java:108` | YadonyBusinessException | Une alerte identique existe déjà pour ce corridor. |
| reste | écran | alerts | `alerts/AlertService.java:160` | YadonyBusinessException | Votre rôle ne permet pas de créer ce type d'alerte. |
| reste | écran | alerts | `alerts/AlertService.java:167` | YadonyBusinessException | Les filtres poids/catégories ne s'appliquent pas aux alertes trajet. |
| reste | écran | alerts | `alerts/AlertService.java:186` | YadonyBusinessException | La zone de remise ne s'applique qu'aux alertes trajet. |
| reste | écran | alerts | `alerts/AlertService.java:191` | YadonyBusinessException | La zone de remise exige un centre (lat/lng) et un rayon. |
| reste | écran | alerts | `alerts/AlertService.java:196` | YadonyBusinessException | Le rayon doit être entre  |
| reste | écran | alerts | `alerts/TripSpaceAlertService.java:66` | YadonyNotFoundException | Trajet introuvable |
| reste | écran | alerts | `alerts/TripSpaceAlertService.java:68` | YadonyBusinessException | Vous ne pouvez pas vous abonner à votre propre trajet. |
| reste | écran | alerts | `alerts/TripSpaceAlertService.java:73` | YadonyBusinessException | Ce trajet est déjà parti. |
| reste | écran | alerts | `alerts/TripSpaceAlertService.java:84` | YadonyBusinessException | Ce trajet n'est pas complet. |
| migré | écran | billing | `billing/BillingController.java:88` | YadonyBusinessException | Utilisateur introuvable |
| reste | indirect | billing | `billing/ProBillingStripeWebhookHandler.java:201` | IllegalStateException |  impossible à vérifier chez Stripe :  |
| migré | écran | billing | `billing/ProSubscriptionService.java:146` | YadonyBusinessException | le Customer Portal Stripe pour le résilier avant d'offrir un accès. |
| migré | écran | billing | `billing/ProSubscriptionService.java:200` | YadonyBusinessException | Cet abonnement n'est pas un accès offert : il se résilie depuis Stripe. |
| migré | écran | billing | `billing/ProSubscriptionService.java:206` | YadonyBusinessException | Cet accès offert a déjà été révoqué. |
| migré | écran | billing | `billing/StripeBillingService.java:45` | YadonyBusinessException | Le service de billing de l'abonnement PRO n'est pas encore disponible. |
| migré | écran | billing | `billing/StripeBillingService.java:54` | YadonyBusinessException | Vous avez déjà un abonnement PRO en cours. |
| migré | écran | billing | `billing/StripeBillingService.java:124` | YadonyBusinessException | Aucun abonnement payant n'est rattaché à ce compte. |
| migré | écran | calls | `calls/CallService.java:89` | YadonyBusinessException | L'appel est momentanément indisponible. Réessayez dans un instant. |
| migré | écran | calls | `calls/CallService.java:99` | YadonyBusinessException | Un appel est déjà en cours dans cette conversation. |
| migré | écran | calls | `calls/CallService.java:114` | YadonyBusinessException | Vous ne participez pas à cette conversation. |
| migré | écran | calls | `calls/CallService.java:116` | YadonyBusinessException | Cette conversation est fermée. |
| migré | écran | calls | `calls/CallService.java:118` | YadonyBusinessException | L'appel est possible de l'acceptation de la commande jusqu'à 3 jours après la livraison. |
| migré | écran | calls | `calls/CallService.java:121` | YadonyBusinessException | Vous ne pouvez pas appeler cette personne. |
| migré | écran | calls | `calls/CallService.java:123` | YadonyBusinessException | Cette personne n'est pas joignable. |
| migré | écran | calls | `calls/CallService.java:129` | YadonyBusinessException | Les appels ne sont pas disponibles pour le moment. |
| migré | écran | calls | `calls/CallService.java:135` | YadonyNotFoundException | Utilisateur introuvable |
| reste | indirect | calls | `calls/StreamClient.java:80` | StreamUnavailableException |  a échoué |
| reste | indirect | common | `common/EncryptionSupport.java:26` | IllegalStateException | EncryptionService non initialisé : le contexte Spring doit être démarré  / avant toute conversion JPA chiffrée |
| reste | écran | common | `common/GlobalExceptionHandler.java:91` | ProblemDetail.forStatusAndDetail | Paramètres de requête invalides |
| migré | écran | common | `common/ImageProcessingService.java:83` | YadonyBusinessException | Échec du traitement de l'image :  |
| migré | écran | common | `common/ImageProcessingService.java:108` | YadonyBusinessException | Échec du traitement de l'image :  |
| migré | écran | common | `common/ImageProcessingService.java:136` | YadonyBusinessException | Le fichier est vide ou absent |
| migré | écran | common | `common/ImageProcessingService.java:145` | YadonyBusinessException | Le fichier n'est pas une image valide |
| migré | écran | common | `common/ImageProcessingService.java:154` | YadonyBusinessException | Le fichier n'est pas une image valide |
| migré | écran | common | `common/ImageProcessingService.java:187` | YadonyBusinessException | Image trop grande (dimensions) |
| reste | indirect | common | `common/Msisdn.java:17` | IllegalArgumentException | Numéro absent |
| reste | indirect | common | `common/Msisdn.java:24` | IllegalArgumentException | Numéro invalide |
| migré | écran | common | `common/StorageController.java:53` | YadonyBusinessException | bidId doit être un UUID |
| migré | écran | common | `common/StorageController.java:57` | YadonyBusinessException | Bid introuvable |
| migré | écran | common | `common/StorageController.java:60` | YadonyBusinessException | Annonce introuvable |
| migré | écran | common | `common/StorageController.java:62` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | common | `common/StorageController.java:65` | YadonyBusinessException | Seul le voyageur de l'annonce peut uploader une photo de suivi |
| migré | écran | common | `common/StorageController.java:91` | YadonyBusinessException | Non authentifié |
| migré | écran | common | `common/StorageController.java:94` | YadonyBusinessException | Utilisateur introuvable |
| reste | écran | common | `common/StorageService.java:208` | YadonyBusinessException | Vous avez atteint la limite de photos pour aujourd'hui. Réessayez demain. |
| reste | écran | common | `common/StorageService.java:215` | YadonyBusinessException | Ce colis a déjà le nombre maximum de photos de suivi. |
| reste | écran | common | `common/StorageService.java:556` | YadonyBusinessException | Clé de stockage invalide |
| reste | indirect | common | `common/commission/CommissionCalculator.java:53` | IllegalArgumentException | Le net doit être strictement positif |
| reste | indirect | common | `common/commission/CommissionCalculator.java:89` | IllegalArgumentException | Le calcul inverse ne prend pas de code promo |
| reste | écran | common | `common/commission/CommissionCalculator.java:95` | YadonyBusinessException | Le montant ne couvre pas la commission minimum de Yadony |
| migré | écran | common | `common/commission/CommissionGridService.java:165` | YadonyBusinessException | La grille augmente la commission : acquittez l'avertissement pour la programmer. |
| migré | écran | common | `common/commission/CommissionGridService.java:304` | YadonyBusinessException | Grille de commission invalide |
| migré | écran | common | `common/commission/CommissionGridService.java:427` | YadonyBusinessException | Une grille déjà en vigueur ne peut pas être annulée : programmez-en une nouvelle. |
| migré | écran | common | `common/commission/CommissionGridService.java:433` | YadonyBusinessException | Grille de commission introuvable |
| reste | indirect | common | `common/commission/IncrementalCommission.java:65` | IllegalArgumentException | Le net ajouté doit être strictement positif |
| reste | indirect | common | `common/i18n/YadonyLocaleResolver.java:24` | UnsupportedOperationException | La langue suit l'en-tête Accept-Language, elle ne se fixe pas |
| migré | écran | common | `common/stripe/StripeUnavailable.java:38` | YadonyBusinessException | Le service de paiement est momentanément injoignable, réessayez. |
| migré | écran | common | `common/stripe/StripeWebhookIngestService.java:62` | YadonyBusinessException | Signature webhook invalide |
| reste | indirect | config | `config/CallingCodeList.java:43` | IllegalArgumentException | Indicatif invalide : «  /  ». Attendu : 1 à 4 chiffres, séparés par des virgules (ex. 221,225) |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:73` | YadonyBusinessException | Colis introuvable |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:77` | YadonyBusinessException | Ce colis n'a pas de transaction engagée (statut  / ) : aucun litige ne peut être ouvert. |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:82` | YadonyBusinessException | Un litige est déjà ouvert sur ce colis : suivez-le dans Incidents. |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:96` | YadonyBusinessException | Le voyageur a déjà été payé pour ce colis : un litige ne peut plus geler l'argent.  / Traitez le différend ave |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:104` | YadonyBusinessException | Un litige pour ce motif a déjà été traité sur ce colis : choisissez un autre motif. |
| migré | écran | disputes | `disputes/AdminDisputeOpeningService.java:113` | YadonyBusinessException | Le trajet de ce colis n'existe plus : le voyageur est introuvable. |
| migré | écran | disputes | `disputes/DisputeController.java:42` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | disputes | `disputes/DisputeController.java:51` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | emailotp | `emailotp/EmailOtpController.java:72` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:72` | YadonyBusinessException | Trop de codes demandés, réessaie dans  |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:83` | YadonyBusinessException | Trop de codes demandés aujourd'hui, réessaie dans une heure |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:97` | YadonyBusinessException | L'envoi de codes est momentanément suspendu, réessaie dans quelques minutes |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:141` | YadonyBusinessException | Erreur lors de la création du token |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:167` | YadonyBusinessException | Erreur lors de la résolution du compte |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:183` | YadonyBusinessException | Erreur lors de la résolution du compte |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:188` | YadonyBusinessException | Erreur lors de la création du compte |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:207` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:215` | YadonyBusinessException | Une adresse est déjà associée à ce compte et ne peut pas être remplacée |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:222` | YadonyBusinessException | Cet email est déjà associé à un compte |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:253` | YadonyBusinessException | Trop de tentatives échouées |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:265` | YadonyBusinessException | Code invalide ou expiré |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:271` | YadonyBusinessException | Trop de tentatives échouées |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:280` | YadonyBusinessException | Code expiré |
| migré | écran | emailotp | `emailotp/EmailOtpService.java:288` | YadonyBusinessException | Code invalide |
| migré | écran | emailotp | `emailotp/ResendEmailService.java:81` | YadonyBusinessException | L'envoi des emails n'est pas configuré |
| migré | écran | emailotp | `emailotp/ResendEmailService.java:111` | YadonyBusinessException | L'envoi de l'email a échoué, veuillez réessayer |
| migré | écran | export | `export/UserDataExportController.java:41` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | favorites | `favorites/FavoriteService.java:282` | YadonyNotFoundException | Trajet introuvable:  |
| migré | écran | favorites | `favorites/FavoriteService.java:284` | YadonyNotFoundException | Trajet introuvable:  |
| migré | écran | favorites | `favorites/FavoriteService.java:287` | YadonyBusinessException | Impossible de mettre son propre trajet en favori |
| migré | écran | favorites | `favorites/FavoriteService.java:297` | YadonyNotFoundException | Demande d'envoi introuvable:  |
| migré | écran | favorites | `favorites/FavoriteService.java:300` | YadonyNotFoundException | Demande d'envoi introuvable:  |
| migré | écran | flights | `flights/FlightAlertService.java:57` | YadonyBusinessException | Vous avez atteint la limite de  |
| migré | écran | flights | `flights/FlightAlertService.java:68` | YadonyBusinessException | Une alerte identique existe déjà pour ce trajet. |
| reste | indirect | flights | `flights/FlightDate.java:18` | IllegalArgumentException | Un jour ou un mois, pas les deux |
| migré | écran | flights | `flights/FlightErrors.java:14` | YadonyBusinessException | La recherche de vols est momentanément indisponible. |
| migré | écran | flights | `flights/FlightErrors.java:19` | YadonyBusinessException | Le service de prix des vols ne répond pas. Réessayez dans un instant. |
| migré | écran | flights | `flights/FlightErrors.java:25` | YadonyBusinessException | Cette alerte prix n'existe pas. |
| reste | écran | flights | `flights/FlightUsers.java:54` | YadonyBusinessException | Créez un compte pour utiliser les alertes prix. |
| reste | indirect | flights | `flights/TravelpayoutsClient.java:104` | FlightProviderException | Réponse prix invalide pour  |
| migré | écran | gppro | `gppro/GpProAdminService.java:119` | YadonyBusinessException | Demande introuvable |
| migré | écran | gppro | `gppro/GpProAdminService.java:177` | YadonyBusinessException | Signalement de revue introuvable |
| migré | écran | gppro | `gppro/GpProAdminService.java:180` | YadonyBusinessException | Ce signalement de revue est déjà clos |
| migré | écran | gppro | `gppro/GpProAdminService.java:236` | YadonyBusinessException | Le badge GP Pro est déjà actif |
| migré | écran | gppro | `gppro/GpProAdminService.java:273` | YadonyBusinessException | Le badge GP Pro n'est pas actif |
| migré | écran | gppro | `gppro/GpProAdminService.java:323` | YadonyBusinessException | La vérification d'identité est requise |
| migré | écran | gppro | `gppro/GpProAdminService.java:327` | YadonyBusinessException | Le compte est suspendu, banni ou en cours de suppression |
| migré | écran | gppro | `gppro/GpProAdminService.java:336` | YadonyBusinessException | Un motif est obligatoire |
| migré | écran | gppro | `gppro/GpProAdminService.java:344` | YadonyBusinessException | Demande introuvable |
| migré | écran | gppro | `gppro/GpProAdminService.java:352` | YadonyBusinessException | La demande a déjà été décidée |
| migré | écran | gppro | `gppro/GpProAdminService.java:360` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | gppro | `gppro/GpProApplicationService.java:71` | YadonyBusinessException | Source de demande invalide |
| migré | écran | gppro | `gppro/GpProApplicationService.java:87` | YadonyBusinessException | La vérification d'identité est requise |
| migré | écran | gppro | `gppro/GpProApplicationService.java:94` | YadonyBusinessException | Justificatif invalide |
| migré | écran | gppro | `gppro/GpProApplicationService.java:137` | YadonyBusinessException | Justificatif invalide |
| migré | écran | gppro | `gppro/GpProApplicationService.java:182` | YadonyBusinessException | Le justificatif n'a pas pu être supprimé, réessayez plus tard |
| migré | écran | gppro | `gppro/GpProApplicationService.java:309` | YadonyBusinessException | Renseignez la raison sociale, le numéro d'entreprise et le pays. |
| migré | écran | gppro | `gppro/GpProApplicationService.java:318` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | gppro | `gppro/GpProApplicationService.java:328` | YadonyBusinessException | Justificatif introuvable |
| migré | écran | gppro | `gppro/GpProController.java:60` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | gppro | `gppro/GpProDocumentController.java:33` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | gppro | `gppro/GpProDocumentController.java:45` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | gppro | `gppro/GpProDocumentService.java:46` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | maintenance | `maintenance/MaintenanceService.java:120` | YadonyBusinessException | Ce compte n'est pas exempté |
| reste | écran | messaging | `messaging/ConversationController.java:104` | YadonyBusinessException | Conversation introuvable |
| migré | écran | messaging | `messaging/ConversationMediaController.java:82` | YadonyBusinessException | variant doit valoir thumb ou full. |
| migré | écran | messaging | `messaging/ConversationMediaController.java:89` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | messaging | `messaging/ConversationMediaController.java:93` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | messaging | `messaging/ConversationMediaPolicy.java:138` | YadonyBusinessException | Les photos sont disponibles une fois la demande acceptée et payée,  / jusqu'à  /  jours après la livraison. |
| reste | écran | messaging | `messaging/ConversationMediaService.java:134` | YadonyBusinessException | La photo n'a pas pu être envoyée, réessayez. |
| reste | écran | messaging | `messaging/ConversationMediaService.java:196` | YadonyBusinessException | Trop de photos envoyées. Réessayez dans quelques minutes. |
| reste | écran | messaging | `messaging/ConversationMediaService.java:210` | YadonyBusinessException | Identifiant du message cité invalide. |
| reste | écran | messaging | `messaging/ConversationMediaService.java:225` | YadonyBusinessException | Photo introuvable. |
| reste | écran | messaging | `messaging/ConversationMediaService.java:230` | YadonyBusinessException | Cette photo n'est plus disponible. |
| reste | écran | messaging | `messaging/ConversationService.java:208` | YadonyBusinessException | Ce membre a supprimé son compte : la conversation n'est plus disponible. |
| reste | écran | messaging | `messaging/MessagingNotifyController.java:58` | YadonyBusinessException | Conversation introuvable |
| migré | écran | messaging | `messaging/RecipientConversationController.java:50` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | messaging | `messaging/RecipientConversationService.java:112` | YadonyBusinessException | Bid introuvable |
| migré | écran | messaging | `messaging/RecipientConversationService.java:116` | YadonyBusinessException | Annonce introuvable |
| migré | écran | messaging | `messaging/RecipientConversationService.java:147` | YadonyBusinessException | Ce colis n'est plus en cours : la conversation avec le destinataire ne peut pas s'ouvrir |
| migré | écran | messaging | `messaging/RecipientConversationService.java:238` | YadonyBusinessException | Vous n'avez pas accès à la conversation avec le destinataire de ce colis |
| migré | écran | notifications | `notifications/NotificationController.java:148` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | notifications | `notifications/NotificationFeedService.java:126` | YadonyBusinessException | groupKey est requis |
| migré | écran | notifications | `notifications/NotificationFeedService.java:141` | YadonyBusinessException | groupKey invalide |
| migré | écran | notifications | `notifications/NotificationPrefsService.java:400` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | notifications | `notifications/NotificationService.java:106` | YadonyBusinessException | Notification introuvable |
| migré | écran | notifications | `notifications/NotificationService.java:109` | YadonyBusinessException | Accès refusé |
| migré | écran | notifications | `notifications/NotificationService.java:119` | YadonyBusinessException | Utilisateur introuvable |
| reste | écran | promo | `promo/PromoService.java:61` | YadonyBusinessException | Code promo introuvable |
| reste | écran | promo | `promo/PromoService.java:65` | YadonyBusinessException | Code promo introuvable :  |
| reste | écran | promo | `promo/PromoService.java:72` | YadonyBusinessException | Ce code promo n'est plus actif |
| reste | écran | promo | `promo/PromoService.java:78` | YadonyBusinessException | Ce code promo n'est pas encore valide |
| reste | écran | promo | `promo/PromoService.java:82` | YadonyBusinessException | Ce code promo a expiré |
| reste | écran | promo | `promo/PromoService.java:93` | YadonyBusinessException | Ce code promo n'est pas disponible pour votre profil |
| reste | écran | promo | `promo/PromoService.java:101` | YadonyBusinessException | Vous avez déjà utilisé ce code promo le nombre maximum de fois autorisé |
| reste | indirect | promo | `promo/PromoService.java:371` | IllegalStateException | Impossible de générer un code promo personnel unique pour  |
| reste | écran | promo | `promo/PromoService.java:377` | YadonyBusinessException | Ce code promo est personnel et réservé à un autre compte |
| reste | écran | promo | `promo/PromoService.java:385` | YadonyBusinessException | Code promo introuvable :  |
| migré | écran | referral | `referral/ReferralService.java:104` | YadonyBusinessException | Utilisateur introuvable :  |
| migré | écran | referral | `referral/ReferralService.java:184` | YadonyBusinessException | Code de parrainage introuvable / Le code  /  n'existe pas |
| migré | écran | referral | `referral/ReferralService.java:192` | YadonyBusinessException | Vous ne pouvez pas utiliser votre propre code de parrainage |
| migré | écran | referral | `referral/ReferralService.java:204` | YadonyBusinessException | Parrainage déjà effectué / Vous avez déjà utilisé un code de parrainage |
| migré | écran | referral | `referral/ReferralService.java:249` | YadonyBusinessException | Délai de régénération non écoulé / Vous ne pouvez régénérer votre code qu'une fois tous les  |
| migré | écran | referral | `referral/ReferralService.java:307` | YadonyBusinessException | Récompense invalide |
| migré | écran | referral | `referral/ReferralService.java:314` | YadonyBusinessException | Filleul introuvable / Ce filleul est introuvable |
| migré | écran | referral | `referral/ReferralService.java:319` | YadonyBusinessException | Récompense déjà choisie / La récompense de ce filleul a déjà été choisie |
| migré | écran | referral | `referral/ReferralService.java:324` | YadonyBusinessException | Récompense pas encore disponible / La récompense sera disponible après le premier envoi livré de votre filleul |
| migré | écran | referral | `referral/ReferralService.java:414` | YadonyBusinessException | Utilisateur introuvable / Aucun compte associé à cet identifiant Firebase |
| reste | écran | requests | `requests/controller/NegotiationController.java:369` | YadonyBusinessException | Un token Firebase valide est requis |
| reste | écran | requests | `requests/controller/PackageRequestController.java:260` | YadonyBusinessException | Un token Firebase valide est requis |
| reste | écran | requests | `requests/controller/PackageRequestInsightController.java:51` | YadonyBusinessException | Un token Firebase valide est requis |
| reste | écran | requests | `requests/service/NegotiationService.java:146` | YadonyBusinessException | La publication de trajets est suspendue. Contactez le support. |
| reste | écran | requests | `requests/service/NegotiationService.java:204` | YadonyBusinessException | Cet utilisateur n'accepte que les profils vérifiés |
| reste | écran | requests | `requests/service/NegotiationService.java:587` | YadonyBusinessException | L'expéditeur a accepté votre offre et procède au paiement :  / vous ne pouvez plus la retirer pendant le délai |
| reste | écran | requests | `requests/service/NegotiationService.java:2590` | YadonyBusinessException | Seule une discussion de prix terminée peut être archivée ou supprimée. |
| migré | écran | requests | `requests/service/PackageRequestModerationService.java:159` | YadonyBusinessException | Cette demande n'a pas été retirée par la modération. |
| migré | écran | requests | `requests/service/PackageRequestModerationService.java:187` | YadonyBusinessException | Demande d'envoi introuvable |
| migré | écran | requests | `requests/service/PackageRequestPhotoService.java:60` | YadonyBusinessException |  photos par colis |
| migré | écran | requests | `requests/service/PackageRequestPhotoService.java:69` | YadonyBusinessException | Clé photo invalide |
| reste | écran | requests | `requests/service/PackageRequestService.java:294` | YadonyBusinessException | La publication est suspendue. Contactez le support. |
| reste | écran | requests | `requests/service/PackageRequestService.java:313` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | search | `search/SearchParseController.java:52` | YadonyBusinessException | Date de référence hors plage / Le paramètre 'today' doit être compris entre les années %d et %d. |
| migré | écran | settings | `settings/MinPricePerKgGuard.java:98` | YadonyBusinessException | Ce voyageur n'accepte pas moins de  |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:90` | YadonyBusinessException | Ce pays n'est pas encore desservi par yadony. |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:97` | YadonyBusinessException | Impossible de changer de pays : votre compte de paiement  / est déjà activé dans ce pays et ne peut plus en ch |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:137` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:167` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:227` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | settings | `settings/UserBusinessPrefsService.java:234` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | signalements | `signalements/ReportController.java:60` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | signalements | `signalements/ReportService.java:142` | YadonyBusinessException | Le type de cible est obligatoire |
| migré | écran | signalements | `signalements/ReportService.java:146` | YadonyBusinessException | L'identifiant de la cible est obligatoire pour ce type de signalement |
| migré | écran | signalements | `signalements/ReportService.java:150` | YadonyBusinessException | Le motif est obligatoire |
| migré | écran | signalements | `signalements/ReportService.java:154` | YadonyBusinessException | Ce motif ne s'applique pas à ce type de signalement |
| migré | écran | signalements | `signalements/ReportService.java:158` | YadonyBusinessException | Vous ne pouvez pas vous signaler vous-même |
| migré | écran | signalements | `signalements/ReportService.java:168` | YadonyBusinessException |  captures d'écran par signalement |
| migré | écran | signalements | `signalements/ReportService.java:174` | YadonyBusinessException | Une des captures ne vous appartient pas |
| migré | écran | signalements | `signalements/ReportService.java:337` | YadonyBusinessException | Identifiant de message trop long |
| migré | écran | signalements | `signalements/ReportService.java:346` | YadonyBusinessException | La cible du signalement est introuvable |
| migré | écran | signalements | `signalements/ReportService.java:349` | YadonyBusinessException | Vous ne participez pas à cette conversation |
| migré | écran | signalements | `signalements/ReportService.java:387` | YadonyBusinessException | La cible du signalement est introuvable |
| migré | écran | signalements | `signalements/ReportService.java:412` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | smsotp | `smsotp/SmsOtpController.java:129` | YadonyBusinessException | Un token Firebase valide est requis |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:171` | YadonyBusinessException | Votre compte est suspendu ou banni |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:282` | YadonyBusinessException | La connexion par SMS n'est pas disponible pour ce pays, utilise ton adresse email |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:312` | YadonyBusinessException | Trop de codes demandés, réessaie dans  |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:322` | YadonyBusinessException | Trop de codes demandés aujourd'hui, réessaie dans une heure |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:338` | YadonyBusinessException | L'envoi de codes par SMS est momentanément suspendu, utilise ton adresse email |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:393` | YadonyBusinessException | Ce numéro n'est pas joignable par SMS, vérifie l'indicatif et le nombre de chiffres |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:420` | YadonyBusinessException | Erreur lors de la création du token |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:440` | YadonyBusinessException | Erreur lors de la résolution du compte |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:456` | YadonyBusinessException | Erreur lors de la résolution du compte |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:461` | YadonyBusinessException | Erreur lors de la création du compte |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:482` | YadonyBusinessException | Utilisateur introuvable |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:490` | YadonyBusinessException | Un numéro est déjà associé à ce compte et ne peut pas être remplacé |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:527` | YadonyBusinessException | Trop de tentatives échouées |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:539` | YadonyBusinessException | Code invalide ou expiré |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:545` | YadonyBusinessException | Trop de tentatives échouées |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:561` | YadonyBusinessException | Code expiré |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:569` | YadonyBusinessException | Code invalide |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:608` | YadonyBusinessException | Trop de connexions avec ce numéro, réessaie dans  |
| reste | écran | smsotp | `smsotp/SmsOtpService.java:648` | YadonyBusinessException | L'envoi du code est momentanément indisponible, réessaie dans quelques minutes |
| migré | écran | support | `support/SupportAdminToolsService.java:55` | YadonyBusinessException | Note invalide / La note doit faire entre  |
| migré | écran | support | `support/SupportAdminToolsService.java:109` | YadonyBusinessException | Reponse type invalide / Le libelle ( |
| migré | écran | support | `support/SupportAdminToolsService.java:120` | YadonyBusinessException | Ticket introuvable |
| migré | écran | support | `support/SupportAdminToolsService.java:126` | YadonyBusinessException | Reponse type introuvable |
| migré | écran | support | `support/SupportAttachmentService.java:89` | YadonyBusinessException | Un message accepte au maximum  |
| migré | écran | support | `support/SupportAttachmentService.java:95` | YadonyBusinessException | Piece jointe invalide / Une piece jointe ne vous appartient pas. |
| migré | écran | support | `support/SupportController.java:210` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | support | `support/SupportController.java:214` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | support | `support/SupportTicketService.java:241` | YadonyBusinessException | Ce ticket est resolu. Ouvrez-en un nouveau pour un autre probleme. |
| migré | écran | support | `support/SupportTicketService.java:267` | YadonyBusinessException | Ce ticket est deja assigne a un autre administrateur. |
| migré | écran | support | `support/SupportTicketService.java:382` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | support | `support/SupportTicketService.java:440` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | support | `support/SupportTicketService.java:594` | YadonyBusinessException | Ce ticket est resolu et n'accepte plus d'action. |
| migré | écran | support | `support/SupportTicketService.java:601` | YadonyBusinessException | Ticket introuvable / Ticket support introuvable |
| migré | écran | support | `support/SupportTicketService.java:668` | YadonyBusinessException | Champ invalide |
| migré | écran | travelerstatus | `travelerstatus/IndividualFrequencyAdminService.java:61` | YadonyBusinessException | Signalement introuvable |
| migré | écran | travelerstatus | `travelerstatus/PublishingEligibilityController.java:30` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | travelerstatus | `travelerstatus/PublishingEligibilityController.java:34` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | travelerstatus | `travelerstatus/PublishingLimitGate.java:45` | YadonyBusinessException | Vous avez atteint votre limite de trajets ce mois-ci.  / Indiquez si vous voyagez à titre personnel ou en tant |
| migré | écran | travelerstatus | `travelerstatus/PublishingLimitGate.java:49` | YadonyBusinessException | Vous avez atteint votre limite de  /  trajet /  ce mois-ci. Vous pourrez publier de nouveau à partir du  |
| migré | écran | travelerstatus | `travelerstatus/PublishingLimitGate.java:53` | YadonyBusinessException | Votre demande GP Pro est en cours de validation.  / Vous pourrez publier de nouveau une fois qu'elle sera vali |
| migré | écran | travelerstatus | `travelerstatus/TravelerStatusController.java:34` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | travelerstatus | `travelerstatus/TravelerStatusService.java:43` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | triptemplate | `triptemplate/TripTemplateController.java:60` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | triptemplate | `triptemplate/TripTemplateService.java:179` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | triptemplate | `triptemplate/TripTemplateService.java:214` | YadonyBusinessException | Ce moyen de paiement n'est plus proposé. |
| migré | écran | triptemplate | `triptemplate/TripTemplateService.java:219` | YadonyBusinessException | Ce moyen de paiement n'est pas disponible dans cette devise. |
| migré | écran | triptemplate | `triptemplate/TripTemplateService.java:236` | YadonyBusinessException | Un prix au kilo est requis en tarification au kilo. |
| migré | écran | triptemplate | `triptemplate/TripTemplateService.java:253` | YadonyBusinessException | Une adresse doit comporter un libellé et des coordonnées. |
| reste | indirect | videomodal | `videomodal/VideoModalJsonConverters.java:33` | IllegalStateException | Liste non sérialisable |
| reste | indirect | videomodal | `videomodal/VideoModalJsonConverters.java:92` | IllegalStateException | Seuils non sérialisables |
| reste | indirect | videomodal | `videomodal/VideoModalJsonConverters.java:117` | IllegalStateException | Action non sérialisable |
| migré | écran | videomodal | `videomodal/VideoModalService.java:272` | YadonyBusinessException | Modal vidéo introuvable :  |
| migré | écran | admin | `admin/AdminAlertController.java:83` | YadonyBusinessException | Alerte introuvable |
| migré | écran | admin | `admin/AdminAlertController.java:98` | YadonyBusinessException | Alerte introuvable |
| migré | écran | admin | `admin/AdminAlertController.java:102` | YadonyBusinessException | Cette alerte n'est pas liée à une règle de cohérence de l'argent |
| migré | écran | admin | `admin/AdminAlertController.java:109` | YadonyBusinessException | Règle de cohérence  |
| migré | écran | admin | `admin/AdminAlertController.java:122` | YadonyBusinessException | Alerte introuvable |
| migré | écran | admin | `admin/AdminAlertController.java:197` | YadonyBusinessException | Cette alerte n'est pas résolue |
| migré | écran | admin | `admin/AdminAlertController.java:201` | YadonyBusinessException | Une alerte du même type est déjà ouverte |
| migré | écran | admin | `admin/AdminAlertController.java:224` | YadonyBusinessException | Le report va de 1 à  |
| migré | écran | admin | `admin/AdminAlertController.java:229` | YadonyBusinessException | Cette alerte est déjà résolue |
| migré | écran | admin | `admin/AdminAlertController.java:253` | YadonyBusinessException | Alerte introuvable |
| migré | écran | admin | `admin/AdminAlertController.java:261` | YadonyBusinessException | La note doit faire entre  /  caractères |
| reste | indirect | admin | `admin/AdminAlertEscalator.java:70` | IllegalArgumentException | Type d'alerte trop long pour admin_alerts.type ( |
| reste | indirect | admin | `admin/AdminAlertEscalator.java:116` | IllegalArgumentException | Contexte d'alerte non sérialisable en JSON |
| migré | écran | admin | `admin/AdminBidsController.java:175` | YadonyBusinessException | Colis introuvable |
| migré | écran | admin | `admin/AdminBidsController.java:197` | YadonyBusinessException | Colis introuvable |
| migré | écran | admin | `admin/AdminBidsController.java:212` | YadonyBusinessException | Colis introuvable |
| migré | écran | admin | `admin/AdminBroadcastController.java:109` | YadonyBusinessException | Ce compte est banni, en cours de suppression ou supprimé : il ne peut pas être notifié |
| migré | écran | admin | `admin/AdminBroadcastController.java:193` | YadonyBusinessException | Export limité à  /  destinataires : affinez la règle ( /  trouvés) |
| migré | écran | admin | `admin/AdminConversationController.java:208` | YadonyBusinessException | Le motif ne peut pas dépasser  /  caractères |
| migré | écran | admin | `admin/AdminConversationController.java:239` | YadonyBusinessException | Conversation introuvable |
| migré | écran | admin | `admin/AdminConversationController.java:243` | YadonyBusinessException | Message introuvable |
| migré | écran | admin | `admin/AdminConversationController.java:245` | YadonyBusinessException | Ce message n'est pas supprimé |
| migré | écran | admin | `admin/AdminConversationToolsController.java:63` | YadonyBusinessException | Conversation introuvable |
| migré | écran | admin | `admin/AdminConversationToolsService.java:107` | YadonyBusinessException | Note invalide / La note doit faire entre  |
| migré | écran | admin | `admin/AdminDisputesController.java:204` | YadonyBusinessException | Ce partage est déjà entièrement exécuté |
| migré | écran | admin | `admin/AdminDisputesController.java:219` | YadonyBusinessException | Partager un paiement exige les droits de libération et de remboursement |
| migré | écran | admin | `admin/AdminDisputesController.java:248` | YadonyBusinessException | Indiquez à qui verser le fonds de garantie |
| migré | écran | admin | `admin/AdminDisputesController.java:253` | YadonyBusinessException | Le montant du fonds de garantie doit être positif |
| migré | écran | admin | `admin/AdminDisputesController.java:317` | YadonyBusinessException | Cette devise n'est pas prise en charge par yadony. |
| migré | écran | admin | `admin/AdminDisputesController.java:324` | YadonyBusinessException | Le fonds de garantie se verse dans la devise du colis ( |
| migré | écran | admin | `admin/AdminDisputesController.java:340` | YadonyBusinessException | Ce litige est déjà résolu |
| migré | écran | admin | `admin/AdminDisputesController.java:355` | YadonyBusinessException | Litige introuvable |
| migré | écran | admin | `admin/AdminIncidentContextController.java:37` | YadonyBusinessException | Litige introuvable |
| migré | écran | admin | `admin/AdminIncidentContextController.java:47` | YadonyBusinessException | Déclaration introuvable |
| migré | écran | admin | `admin/AdminMaintenanceController.java:81` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | admin | `admin/AdminPackageRequestQueryService.java:132` | YadonyBusinessException | Demande d'envoi introuvable |
| migré | écran | admin | `admin/AdminPackageRequestQueryService.java:275` | YadonyBusinessException | Date invalide :  |
| migré | écran | admin | `admin/AdminPaymentController.java:369` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:378` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:427` | YadonyBusinessException | ) : aucun versement n'est parti, le paiement reste en séquestre |
| migré | écran | admin | `admin/AdminPaymentController.java:443` | YadonyBusinessException | Ce versement est déjà pris en charge par un autre traitement : rechargez la fiche avant toute action |
| migré | écran | admin | `admin/AdminPaymentController.java:455` | YadonyBusinessException | Un Transfer a déjà été tenté pour ce paiement avec d'autres paramètres : le versement  / est réservé et la rep |
| migré | écran | admin | `admin/AdminPaymentController.java:461` | YadonyBusinessException | Stripe n'a pas confirmé le versement : il est réservé et sera repris automatiquement  / avec la même clé, ne r |
| migré | écran | admin | `admin/AdminPaymentController.java:466` | YadonyBusinessException | Une garde est apparue avant le versement ( / ) : rien n'est parti, le paiement reste en séquestre |
| migré | écran | admin | `admin/AdminPaymentController.java:473` | YadonyBusinessException | Impossible de libérer le paiement Stripe. Veuillez réessayer. |
| migré | écran | admin | `admin/AdminPaymentController.java:494` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:504` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:546` | YadonyBusinessException | Voyageur introuvable ou sans compte Stripe Connect — transfert impossible |
| migré | écran | admin | `admin/AdminPaymentController.java:553` | YadonyBusinessException | Voyageur introuvable pour ce paiement |
| migré | écran | admin | `admin/AdminPaymentController.java:572` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:584` | YadonyBusinessException | Paiement en litige ou voyageur gelé depuis la vérification : versement refusé sans dérogation |
| migré | écran | admin | `admin/AdminPaymentController.java:660` | YadonyBusinessException | Le colis est annulé — l'escrow doit être remboursé à l'expéditeur, pas transféré au voyageur |
| migré | écran | admin | `admin/AdminPaymentController.java:668` | YadonyBusinessException | Le colis est  /  — l'escrow revient à l'expéditeur, pas au voyageur |
| migré | écran | admin | `admin/AdminPaymentController.java:678` | YadonyBusinessException | Ce paiement a déjà été partiellement remboursé ( / ) — la libération forcée verserait le montant total |
| migré | écran | admin | `admin/AdminPaymentController.java:689` | YadonyBusinessException | Un litige ouvert par l'administration gèle ce versement : résolvez-le d'abord dans Incidents. |
| migré | écran | admin | `admin/AdminPaymentController.java:711` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPaymentController.java:786` | YadonyBusinessException | Impossible de rembourser le paiement Stripe. Veuillez réessayer. |
| migré | écran | admin | `admin/AdminPaymentController.java:978` | YadonyBusinessException | Le motif de dérogation est obligatoire : entre  /  caractères |
| migré | écran | admin | `admin/AdminPaymentController.java:990` | YadonyBusinessException | Paiement en litige (chargeback) : versement refusé sans dérogation motivée / Les versements de ce voyageur son |
| migré | écran | admin | `admin/AdminPaymentController.java:1031` | YadonyBusinessException | Le compte Stripe du voyageur est  /  : aucun Transfer possible, le paiement reste en séquestre |
| migré | écran | admin | `admin/AdminPaymentController.java:1085` | YadonyBusinessException | Paiement introuvable |
| migré | écran | admin | `admin/AdminPhotoStorageController.java:112` | YadonyBusinessException | Une purge est déjà en cours, réessayez dans quelques minutes. |
| migré | écran | admin | `admin/AdminPromoController.java:51` | YadonyBusinessException | Un code promo avec ce code existe déjà :  |
| migré | écran | admin | `admin/AdminPromoController.java:123` | YadonyBusinessException | Code promo introuvable |
| migré | écran | admin | `admin/AdminRatingContextController.java:38` | YadonyBusinessException | Avis introuvable |
| migré | écran | admin | `admin/AdminRatingModerationService.java:65` | YadonyBusinessException | Le motif de suppression ne doit pas dépasser  /  caractères |
| migré | écran | admin | `admin/AdminRatingModerationService.java:88` | YadonyBusinessException | Avis introuvable |
| migré | écran | admin | `admin/AdminRatingsController.java:143` | YadonyBusinessException | Avis introuvable |
| migré | écran | admin | `admin/AdminRatingsController.java:146` | YadonyBusinessException | Cet avis n'est pas supprimé |
| migré | écran | admin | `admin/AdminRatingsController.java:154` | YadonyBusinessException | L'auteur a déjà laissé un nouvel avis sur cette livraison |
| migré | écran | admin | `admin/AdminRecetteTesterController.java:72` | YadonyBusinessException | Le mode recette n'est pas activé dans cet environnement |
| migré | écran | admin | `admin/AdminRecetteTesterController.java:93` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | admin | `admin/AdminRecetteTesterService.java:63` | YadonyBusinessException | Le mode recette n'est pas activé dans cet environnement |
| migré | écran | admin | `admin/AdminRecetteTesterService.java:70` | YadonyBusinessException | Sélectionnez entre 1 et  |
| migré | écran | admin | `admin/AdminReleaseIntentController.java:58` | YadonyBusinessException | Aucun versement réservé en attente pour ce paiement |
| migré | écran | admin | `admin/AdminReleaseIntentController.java:60` | YadonyBusinessException | Un appel Stripe est en cours pour ce versement : réessayez dans quelques minutes |
| migré | écran | admin | `admin/AdminReleaseIntentController.java:62` | YadonyBusinessException | Capture legacy : vérifiez le PaymentIntent dans Stripe et traitez à la main |
| migré | écran | admin | `admin/AdminReleaseIntentController.java:65` | YadonyBusinessException | Recherche Stripe impossible : rien n'a été modifié, réessayez dans un instant |
| migré | écran | admin | `admin/AdminReportContextController.java:38` | YadonyBusinessException | Signalement introuvable |
| migré | écran | admin | `admin/AdminReportReplyService.java:92` | YadonyBusinessException | Signalement introuvable |
| migré | écran | admin | `admin/AdminReportReplyService.java:95` | YadonyBusinessException | Seul un signalement de l'application (scarabée) peut recevoir une réponse |
| migré | écran | admin | `admin/AdminReportReplyService.java:100` | YadonyBusinessException | Le signalant est inconnu ou son compte a été supprimé |
| migré | écran | admin | `admin/AdminReportsController.java:193` | YadonyBusinessException | L'action est obligatoire |
| migré | écran | admin | `admin/AdminReportsController.java:197` | YadonyBusinessException | Cette action ne s'applique pas à ce type de cible |
| migré | écran | admin | `admin/AdminReportsController.java:202` | YadonyBusinessException | Ce signalement est déjà traité |
| migré | écran | admin | `admin/AdminReportsController.java:212` | YadonyBusinessException | La cible de ce signalement (ou son auteur) est introuvable |
| migré | écran | admin | `admin/AdminReportsController.java:244` | YadonyBusinessException | Signalement introuvable |
| migré | écran | admin | `admin/AdminReportsController.java:276` | YadonyBusinessException | Signalement introuvable |
| migré | écran | admin | `admin/AdminReportsController.java:336` | YadonyBusinessException | Signalement introuvable |
| migré | écran | admin | `admin/AdminReportsController.java:339` | YadonyBusinessException | Ce signalement n'est pas supprimé |
| migré | écran | admin | `admin/AdminReportsController.java:367` | YadonyBusinessException |  signalements par restauration groupée |
| migré | écran | admin | `admin/AdminSettingsController.java:84` | YadonyBusinessException | La valeur du reglage est obligatoire |
| migré | écran | admin | `admin/AdminSupportController.java:177` | YadonyBusinessException | Administrateur introuvable |
| migré | écran | admin | `admin/AdminSupportController.java:181` | YadonyBusinessException | Impossible d'assigner un ticket a un compte desactive |
| migré | écran | admin | `admin/AdminSupportController.java:189` | YadonyBusinessException | Un compte administrateur est requis |
| migré | écran | admin | `admin/AdminSupportController.java:202` | YadonyBusinessException | Filtre invalide |
| migré | écran | admin | `admin/AdminSupportController.java:215` | YadonyBusinessException | Filtre invalide |
| migré | écran | admin | `admin/AdminUserController.java:339` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | admin | `admin/AdminUserDeletionService.java:47` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | admin | `admin/AdminUserWalletController.java:107` | YadonyBusinessException | L'en-tête  /  est obligatoire |
| migré | écran | admin | `admin/AdminUserWalletController.java:157` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | admin | `admin/AdminWalletRefundController.java:99` | YadonyBusinessException | Aucun statut demandé |
| migré | écran | admin | `admin/broadcast/BroadcastAudienceService.java:70` | YadonyBusinessException | Utilisateur introuvable |
| reste | indirect | admin | `admin/broadcast/BroadcastAudienceService.java:111` | IllegalArgumentException | Un ciblage par utilisateur n'a pas de règle équivalente |
| migré | écran | admin | `admin/broadcast/segment/BroadcastSegmentService.java:105` | YadonyBusinessException | Segment introuvable |
| reste | indirect | admin | `admin/broadcast/segment/BroadcastSegmentService.java:121` | IllegalStateException | Règle de segment illisible |
| migré | écran | admin | `admin/broadcast/segment/BroadcastSegmentService.java:135` | YadonyBusinessException | Un segment porte déjà ce nom |
| reste | indirect | admin | `admin/broadcast/segment/BroadcastSegmentService.java:151` | IllegalStateException | Règle de segment illisible |
| reste | écran | admin | `admin/commission/CommissionSimulator.java:164` | YadonyBusinessException | Simulation invalide |
| reste | indirect | admin | `admin/dto/AdminBroadcastResponse.java:43` | IllegalStateException | Règle d'historique illisible |
| migré | écran | admin | `admin/dto/BroadcastTargetRequest.java:44` | YadonyBusinessException | Un ciblage par règle exige soit une règle, soit un segment enregistré, pas les deux |
| reste | écran | admin | `admin/dto/CommissionGridRequest.java:74` | YadonyBusinessException | Grille de commission invalide |
| migré | écran | admin | `admin/export/AdminExportController.java:56` | YadonyBusinessException | Type d'export invalide. Valeurs acceptées : transactions, users, disputes, payouts. |
| migré | écran | admin | `admin/notifications/AdminNotificationService.java:152` | YadonyBusinessException | Identifiant de notification invalide |
| migré | écran | admin | `admin/userview/AdminUserViewController.java:102` | YadonyBusinessException | direction doit valoir RECEIVED ou SENT |
| migré | écran | admin | `admin/userview/AdminUserViewController.java:150` | YadonyBusinessException | Utilisateur introuvable |
| reste | indirect | assistant | `assistant/AssistantAnswerParser.java:71` | LlmCallException | Réponse du modèle inexploitable :  |
| migré | écran | assistant | `assistant/AssistantController.java:55` | YadonyBusinessException | L'assistant n'est pas disponible. |
| migré | écran | assistant | `assistant/AssistantController.java:67` | YadonyBusinessException | L'assistant n'est pas disponible. |
| migré | écran | assistant | `assistant/AssistantController.java:72` | YadonyBusinessException | L'assistant est réservé aux comptes utilisateur. |
| migré | écran | assistant | `assistant/AssistantQuotaGuard.java:72` | YadonyBusinessException | Vous avez posé beaucoup de questions aujourd'hui. Réessayez demain. |
| migré | écran | assistant | `assistant/formdraft/FormDraftController.java:49` | YadonyBusinessException | L'assistant n'est pas disponible. |
| migré | écran | assistant | `assistant/formdraft/FormDraftController.java:54` | YadonyBusinessException | L'assistant est réservé aux comptes utilisateur. |
| migré | écran | assistant | `assistant/formdraft/FormDraftEngine.java:68` | YadonyBusinessException | La conversation est trop longue : remplissez le formulaire. |
| migré | écran | assistant | `assistant/formdraft/FormDraftEngine.java:72` | YadonyBusinessException | Le dernier message doit venir de l'utilisateur. |
| reste | indirect | assistant | `assistant/formdraft/FormDraftParser.java:56` | LlmCallException | Réponse du modèle inexploitable :  |
| migré | écran | assistant | `assistant/formdraft/FormDraftQuotaGuard.java:43` | YadonyBusinessException | Vous avez beaucoup utilisé l'IA aujourd'hui. Réessayez demain. |
| reste | indirect | assistant | `assistant/kb/KnowledgeBaseLoader.java:221` | IllegalStateException | Fiche assistant invalide ( |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:116` | LlmCallException | Clé d'API absente |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:130` | LlmCallException | Délai dépassé |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:145` | LlmCallException | Délai dépassé / Réseau indisponible ou délai dépassé |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:148` | LlmCallException | Réponse illisible |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:204` | LlmCallException | Réponse refusée par le modèle |
| reste | indirect | assistant | `assistant/llm/AnthropicMessagesClient.java:213` | LlmCallException | Réponse sans contenu |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:110` | LlmCallException | Délai dépassé |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:116` | LlmCallException | Requête illisible |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:122` | LlmCallException | URL du fournisseur invalide |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:139` | LlmCallException | Délai dépassé |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:142` | LlmCallException | Adresse du fournisseur refusée |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:144` | LlmCallException | Réseau indisponible ou délai dépassé |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:149` | LlmCallException | Délai dépassé |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:160` | LlmCallException | Réponse coupée par la limite de tokens / Réponse sans contenu |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:227` | LlmCallException | Redirection refusée |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:234` | LlmCallException | Réponse sans contenu |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:240` | LlmCallException | Réponse trop volumineuse |
| reste | indirect | assistant | `assistant/llm/OpenAiCompatibleClient.java:247` | LlmCallException | Réponse illisible |
| reste | indirect | assistant | `assistant/llm/ProviderAddressGuard.java:54` | LlmCallException | Adresse du fournisseur refusée |
| reste | indirect | assistant | `assistant/llm/ProviderAddressGuard.java:56` | LlmCallException | Hôte du fournisseur introuvable |
| reste | indirect | assistant | `assistant/llm/ProviderAddressGuard.java:99` | LlmCallException | URL du fournisseur invalide |
| reste | indirect | assistant | `assistant/llm/ProviderAddressGuard.java:103` | LlmCallException | URL du fournisseur invalide |
| reste | écran | assistant | `assistant/provider/AssistantProviderAdminService.java:98` | YadonyBusinessException | Saisissez à nouveau la clé quand l'adresse change. / Saisissez à nouveau la clé quand l'adresse change. |
| reste | écran | assistant | `assistant/provider/AssistantProviderAdminService.java:131` | YadonyBusinessException | L'ordre doit contenir chaque fournisseur une seule fois. / L'ordre doit contenir chaque fournisseur une seule  |
| reste | écran | assistant | `assistant/provider/AssistantProviderAdminService.java:186` | YadonyBusinessException | Fournisseur introuvable. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:21` | IllegalArgumentException | L'adresse est obligatoire. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:24` | IllegalArgumentException | L'adresse doit faire de 1 à 255 caractères. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:25` | IllegalArgumentException | L'adresse ne doit pas contenir d'espace. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:30` | IllegalArgumentException | Adresse invalide. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:32` | IllegalArgumentException | L'adresse doit commencer par https://. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:34` | IllegalArgumentException | Utilisez le nom de domaine en caractères simples (punycode). |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:37` | IllegalArgumentException | L'adresse doit contenir un nom de domaine. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:38` | IllegalArgumentException | L'adresse ne doit pas contenir d'identifiants. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:40` | IllegalArgumentException | L'adresse ne doit contenir ni ? ni #. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:44` | IllegalArgumentException | Seul le port 443 est accepté. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:48` | IllegalArgumentException | Le nom de domaine ne doit pas finir par un point. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:50` | IllegalArgumentException | Utilisez un nom de domaine, pas une adresse IP. |
| reste | indirect | assistant | `assistant/provider/BaseUrlValidator.java:54` | IllegalArgumentException | Adresse interne refusée. |
| migré | écran | automation | `automation/AutomationHistoryController.java:39` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | automation | `automation/AutomationHistoryController.java:42` | YadonyBusinessException | Historique réservé aux voyageurs PRO. |
| migré | écran | automation | `automation/AutomationHistoryController.java:57` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | automation | `automation/AutomationHistoryController.java:60` | YadonyBusinessException | Historique réservé aux voyageurs PRO. |
| migré | écran | automation | `automation/AutomationRuleController.java:107` | YadonyBusinessException | L'identifiant fourni n'est pas un UUID valide. |
| migré | écran | automation | `automation/AutomationRuleController.java:119` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | automation | `automation/AutomationRuleController.java:122` | YadonyBusinessException | Automatisations réservées aux voyageurs PRO. |
| migré | écran | automation | `automation/AutomationRuleService.java:153` | YadonyBusinessException | Règle introuvable. |
| reste | indirect | dac7 | `dac7/Dac7AdminReadModel.java:157` | IllegalStateException | Le verrou d'export DAC7 se prend dans une transaction. |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:127` | YadonyBusinessException | La collecte DAC7 est éteinte : aucune demande n'est envoyée aux voyageurs. |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:133` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:135` | YadonyBusinessException | Un message a déjà été envoyé à ce voyageur il y a moins de 7 jours. |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:137` | YadonyBusinessException | La demande et les deux rappels ont déjà été envoyés : la suite est une  / décision de l'équipe. |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:140` | YadonyBusinessException | Ce voyageur n'est pas à solliciter (déjà fourni, hors périmètre ou jamais payé). |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:148` | YadonyBusinessException | La note doit compter de 1 à  /  caractères. |
| migré | écran | dac7 | `dac7/Dac7AdminService.java:157` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | dac7 | `dac7/Dac7DeclarationService.java:143` | YadonyBusinessException | L'année doit être comprise entre 2023 et  |
| migré | écran | dac7 | `dac7/Dac7DeclarationService.java:156` | YadonyBusinessException | L'année  /  n'est pas terminée : les récapitulatifs ne peuvent  / être envoyés qu'à partir du 1er janvier  |
| migré | écran | dac7 | `dac7/Dac7DueDiligenceService.java:40` | YadonyBusinessException | La note ne doit pas dépasser  /  caractères. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:140` | YadonyBusinessException | Le numéro d'ordre doit être compris entre 0 et 999. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:149` | YadonyBusinessException | Aucune clé publique de la DGFiP n'est configurée et l'export en  / clair n'est pas autorisé. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:186` | YadonyBusinessException | Le numéro d'ordre  /  a déjà servi pour  |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:192` | YadonyBusinessException | Les 1 000 numéros d'ordre de  /  sont épuisés. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:259` | YadonyBusinessException | Le fichier généré n'est pas conforme au schéma de la DGFiP. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:294` | YadonyBusinessException | Fichier DAC7 introuvable. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:299` | YadonyBusinessException | Ce fichier a été généré avant la fin de l'année  / il ne peut pas être confirmé déposé. Générez le fichier à p |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:320` | YadonyBusinessException | Un export DAC7  /  est déjà en cours : réessayez dans quelques instants. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:328` | YadonyBusinessException |  voyageur(s) figurent déjà dans un fichier  /  déposé : les reprendre demanderait une déclaration rectificativ |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:352` | YadonyBusinessException | La clé publique de chiffrement de la DGFiP configurée est introuvable, illisible ou sans  / sous-clé de chiffr |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:467` | YadonyBusinessException | Le mode doit être FULL ou PARTIAL. |
| migré | écran | dac7 | `dac7/Dac7ExportService.java:477` | YadonyBusinessException | L'export DAC7 est bloqué :  |
| reste | indirect | dac7 | `dac7/Dac7Properties.java:38` | IllegalArgumentException |  années civiles quand DAC7 est allumé (valeur reçue :  |
| reste | indirect | dac7 | `dac7/Dac7ReminderProperties.java:30` | IllegalArgumentException | yadony.dac7.reminders : délais positifs et croissants attendus |
| reste | indirect | dac7 | `dac7/Dac7ReminderProperties.java:33` | IllegalArgumentException | yadony.dac7.reminders : fenêtre horaire invalide |
| migré | écran | dac7 | `dac7/Dac7SellerStatementService.java:71` | YadonyBusinessException | La collecte DAC7 est éteinte : aucun récapitulatif n'est envoyé aux voyageurs. |
| migré | écran | dac7 | `dac7/TravelerTaxInfoController.java:51` | YadonyBusinessException | Les informations fiscales ne sont pas disponibles. |
| migré | écran | dac7 | `dac7/TravelerTaxInfoController.java:58` | YadonyBusinessException | Un token Firebase valide est requis |
| migré | écran | dac7 | `dac7/TravelerTaxInfoService.java:200` | YadonyBusinessException | Utilisateur introuvable |
| migré | écran | dac7 | `dac7/TravelerTaxInfoService.java:279` | YadonyBusinessException | Ces informations ne sont demandées qu'aux particuliers qui habitent dans l'Union européenne. |
| reste | indirect | dac7 | `dac7/export/Dac7FileEncryptor.java:94` | KeyUnavailableException | Clé publique DAC7 introuvable ou illisible |
| reste | indirect | dac7 | `dac7/export/Dac7FileEncryptor.java:100` | KeyUnavailableException | Clé publique DAC7 illisible :  |
| reste | indirect | dac7 | `dac7/export/Dac7FileEncryptor.java:126` | KeyUnavailableException | Aucune clé de chiffrement valide (sous-clé marquée chiffrement, non  / révoquée et non expirée) dans le fichie |
| reste | indirect | dac7 | `dac7/export/Dac7FileNaming.java:28` | IllegalArgumentException | Millésime invalide |
| reste | indirect | dac7 | `dac7/export/Dac7FileNaming.java:31` | IllegalArgumentException | Identifiant du déclarant invalide (SIREN ou IDSP sur 9 caractères) |
| reste | indirect | dac7 | `dac7/export/Dac7FileNaming.java:34` | IllegalArgumentException | Numéro d'ordre invalide (0 à 999) |
| reste | indirect | dac7 | `dac7/export/Dac7XmlWriter.java:53` | IllegalStateException | Écriture du XML DAC7 impossible :  |
| reste | indirect | dac7 | `dac7/export/Dac7XsdValidator.java:106` | IllegalStateException | Schéma DAC7 introuvable :  |
| reste | indirect | dac7 | `dac7/export/Dac7XsdValidator.java:118` | IllegalStateException | Ressource de schéma non autorisée |
| migré | écran | toolkit | `toolkit/ToolsCompletionController.java:35` | YadonyBusinessException | Utilisateur introuvable |


## Laissé tel quel (paquet 5)

- Textes déjà en anglais neutre ou gardes techniques : `common/StorageService.java` (13, contrôle de fichier et destination d'envoi), `address/GoogleAddressService.java` (« Google API timeout », 3), `messaging/MessagingNotifyController.java` (« Invalid internal secret », appel interne), `YadonyNotFoundException("User not found" / "Alert not found")` d'`alerts` et `flights/FlightUsers`, `ResponseStatusException` anglaises de `messaging`, `requests` (`user/not-found`, `request/not-found`…). Ces fichiers sont dans `FRENCH_FREE_ONLY` de `ServerMessagesGuardTest` : aucun français en dur n'y est toléré, mais `new YadonyBusinessException(` y reste permis.
- `smsotp/SmsOtpService.java` : refus dont le détail est déjà résolu dans la langue de la requête (`messagesResolver.forRequest().get("sms.otp.*")`).
- `IllegalStateException` / `IllegalArgumentException` internes (jamais rendues) : `common/EncryptionSupport`, `Msisdn`, `commission/CommissionCalculator` et `CommissionGrid`, `promo/PromoService` (génération de code), `flights/FlightDate`, `billing/ProBillingStripeWebhookHandler`, `config/CallingCodeList`, `videomodal/VideoModalJsonConverters`. `CommissionCalculator` et `PromoService` sont dans `INDIRECT_ILLEGAL_STATE_ALLOWED`.
- `common/GlobalExceptionHandler` : « Paramètres de requête invalides » (le handler lui-même).
- Valeurs de la map `violations` de `CommissionGridService` (note trop longue, etc.) : champs de validation de l'administration, restent en français.
- Administration vidéo : `videomodal/VideoModalAdminService.invalid(…)` et `VideoModalImageService.invalid(…)`.
- Le garde-fou : 63 sources ajoutées à `MIGRATED`, 15 à `FRENCH_FREE_ONLY`.


## Laissé tel quel (paquet 6)

- `admin/account/AdminAccountService.java` (8) : refus déjà rédigés en anglais (réinitialisation de mot de passe, auto-désactivation, compte introuvable) et détails techniques Firebase (`e.getMessage()`) ; l'administration web les affiche tels quels.
- Règles de ciblage de diffusion : `admin/broadcast/rule/AudienceField`, `AudienceRule`, `AudienceSqlCompiler` et `admin/broadcast/BroadcastTarget` lèvent `broadcast-rule-invalid` / `broadcast-target-invalid` avec un détail assemblé champ par champ (dizaines de variantes techniques, saisies uniquement dans le constructeur de règles de l'administration, en français). Hors liste du garde-fou.
- Validations avec map `violations` par champ (`CommissionGridRequest`, `CommissionSimulator`, `AssistantProviderAdminService`, valeurs de réglages `PlatformSettingsService` / `PlatformSettingKey`) : le détail récapitulatif et les messages de champ restent en français, ils s'affichent dans les formulaires de l'administration web.
- `assistant/provider/BaseUrlValidator` (13 `IllegalArgumentException`) : messages de validation d'adresse de fournisseur, recopiés dans la map `violations` de l'administration.
- `assistant/llm/*` (`AnthropicMessagesClient`, `OpenAiCompatibleClient`, `ProviderAddressGuard`, `AssistantAnswerParser`, `FormDraftParser`) : `LlmCallException`, raisons techniques journalisées (jamais rendues : l'assistant répond par un repli). Les prompts et instructions envoyés au modèle ne sont pas touchés.
- `assistant/kb/KnowledgeBaseLoader`, `admin/AdminAlertEscalator`, `admin/broadcast/BroadcastAudienceService`, `BroadcastSegmentService`, `admin/dto/AdminBroadcastResponse` : `IllegalStateException` / `IllegalArgumentException` internes (démarrage, sérialisation), journal seulement ; les deux services de diffusion sont dans `INDIRECT_ILLEGAL_STATE_ALLOWED`.
- `dac7/export/*` (`Dac7FileNaming`, `Dac7FileEncryptor`, `Dac7XmlWriter`, `Dac7XsdValidator`), `Dac7AdminReadModel`, `Dac7Properties`, `Dac7ReminderProperties` : garde technique du fichier XML et de la configuration de démarrage, jamais rendue telle quelle. `Dac7AdminService` (note d'équipe jugée sensible, `NOTE_SENSITIVE_MESSAGE`) reste dans `FRENCH_FREE_ONLY` (texte d'une note interne vue par l'équipe).
- `videomodal/VideoModalAdminService.invalid(…)` (une dizaine de messages de période, de fréquence et de ciblage) et `VideoModalImageService.invalid(…)` : validation du formulaire d'administration (français), `VideoModalJsonConverters` (sérialisation, journal).
- `config/CallingCodeList` : validation de configuration au démarrage.
- `AdminConversationController` : `ResponseStatusException("Conversation not found")` déjà en anglais neutre (dans `FRENCH_FREE_ONLY`).

## BILAN FINAL du lot 5 (défaut n° 9 : messages serveur dans la langue de l'app)

**Mécanisme** : `YadonyBusinessException.localized(…)` / `LocalizedResponseStatusException` / `YadonyNotFoundException.localized(…)` portent des clés `problem.*` (`messages_fr.properties` et `messages_en.properties`, mêmes clés, parité vérifiée par `MessagesBundleTest`) ; `GlobalExceptionHandler` les résout selon `Accept-Language` (français par défaut, `application/problem+json`, codes et statuts HTTP inchangés). `ServerMessagesGuardTest` interdit tout retour du texte français en dur dans les sources migrées.

**Migré** (paquets 1 à 6) : `matching`, `auth`, `cancellation`, `kyc`, `ratings`, `payments`, `tracking`, la trentaine de petits packages de priorité 2 (stockage, grille de commission, GP Pro, codes SMS et e-mail, adresses, messagerie, support, demandes, signalements, appels, parrainage, vols, statut voyageur, litiges, abonnement PRO, réglages, notifications, modèles de trajet…), puis au paquet 6 : `admin` (alertes, colis, diffusion, conversations, litiges, paiements, promo, avis, recette, signalements, support, utilisateurs, portefeuille), `assistant` (disponibilité, compte réservé, quotas), `dac7` (collecte, relances, année, export, informations fiscales du voyageur dont les 14 refus de saisie fiscale visibles dans l'app), `automation` et `toolkit`.

| Paquet | Sites de lancer migrés (ordre de grandeur) |
|---|---|
| 1 `matching` | 78 |
| 2 `cancellation`, `kyc`, `ratings` | 116 |
| 3 reste de `matching`, `auth` | 218 |
| 4a `payments` côté utilisateur | environ 190 |
| 4b reste de `payments`, `tracking` | environ 220 |
| 5 petits packages | environ 290 |
| 6 `admin`, `assistant`, `dac7`, `automation`, `toolkit` | environ 205 (143 nouvelles clés à titre + détail, une dizaine de clés existantes réutilisées) |

**Chiffres de l'inventaire** : 1324 occurrences repérées ; **1068 lignes marquées « migré »** (dont 193 cochées au paquet 6 en même temps que les paquets 4b et 5 dont les lignes n'avaient pas été cochées) et **256 « reste »**, toutes volontairement laissées en français (voir les trois sections « Laissé tel quel »). Les sites que l'heuristique ne voyait pas (aides privées, concaténations, refus sans accent) sont migrés en plus et ne figurent pas dans l'inventaire.

**Volontairement laissé en français ou en anglais, et pourquoi** : (1) `IllegalState` / `IllegalArgument` / `LlmCallException` / `KeyUnavailableException` internes, jamais rendus (réponse 500 générique ou repli de l'assistant) ; (2) validations de configuration au démarrage ; (3) textes déjà en anglais neutre (stockage, Google, secret interne, comptes administrateurs) ; (4) détails déjà résolus dans la langue de la requête (SMS) ; (5) validations d'administration avec map `violations` ou assemblage champ par champ (règles de ciblage, grilles, vidéo, réglages), vues par l'équipe dans l'administration web en français ; (6) prompts et instructions du LLM de l'assistant ; (7) textes de rapport ou de fichier DAC7.

**Non vérifié** : la suite complète du dépôt n'a pas été relancée en local (tests ciblés seulement) ; les messages d'administration ne sont pas relus visuellement dans `dony-admin` (le français est identique mot pour mot) ; l'anglais des nouvelles clés n'a pas été relu par un locuteur natif.

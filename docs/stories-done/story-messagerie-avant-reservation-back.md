# Messagerie avant réservation : conversations de questions INQUIRY (Backend)

**Date:** 2026-11-13
**Status:** ✅ Complète côté back (PR jumelle app à venir ; plan 1 « functions » déjà livré)

## Résumé
Un utilisateur dont l'identité est vérifiée peut écrire au voyageur depuis le détail d'un trajet, avant toute réservation. La conversation (type `INQUIRY`) naît sans réservation (`bid_id` nul), est limitée à 10 messages texte tant qu'aucune demande n'est faite, devient une conversation normale quand une demande est créée (les messages restent), et est supprimée après 30 jours d'inactivité si aucune demande n'a été faite.

## Fichiers créés
- `db/migration/V387__conversations_inquiry.sql` : `bid_id` nullable, colonnes `announcement_id`, `asker_id`, `quota_lifted_at`, `inquiry_first_message_at`, `last_activity_at`, contraintes (`chk_conversations_kind`, `chk_conversations_inquiry_refs`, `chk_conversations_bid_unless_inquiry`), index unique `conversations_inquiry_active_idx`.
- `messaging/InquiryPolicy.java` : constantes `MESSAGE_LIMIT = 10` et `INACTIVITY_DAYS = 30`.
- `messaging/InquiryCapability.java` : lit l'en-tête `X-Dony-Capabilities` (valeur `inquiry`) de la requête courante.
- `messaging/InquiryPromotionListener.java` : écoute les événements de demande pour promouvoir ou re-verrouiller la conversation.
- `messaging/InquiryInactivityScheduler.java` : purge quotidienne (03h20 UTC, `yadony.messaging.inquiry-purge-cron`) des conversations inactives.
- `messaging/dto/InquiryRequest.java` : corps de `POST /conversations/inquiry`.
- Tests : `ConversationControllerInquiryTest`, `ConversationInquiryControllerIntegrationTest`, `ConversationInquiryRaceIT`, `ConversationInquiryRepositoryTest`, `ConversationInquirySchemaIT`, `ConversationServiceInquiryTest`, `ConversationStaleInquiryRepositoryTest`, `InquiryCapabilityTest`, `InquiryInactivitySchedulerTest`, `InquiryPromotionIntegrationTest`, `InquiryPromotionTest`, `InquiryRelockTest`, `InquiryTestFixtures` (effet en mémoire des UPDATE gardés), `MessagingNotifyControllerTest`.
- Fiches assistant : `assistant/kb/fr/poser-une-question.md` et `assistant/kb/en/poser-une-question.md` (même `id`).

## Fichiers modifiés
- `messaging/ConversationEntity.java`, `ConversationKind.java` : type `INQUIRY`, fabrique `forInquiry`, nouveaux champs.
- `messaging/ConversationRepository.java` : recherches par (demandeur, annonce), UPDATE gardés de promotion, de re-verrouillage, de réservation et d'activité, requêtes de la purge.
- `messaging/ConversationService.java` : `getOrCreateInquiry`, `promoteInquiryToBid`, `relockInquiry`, mapping de réponse avec limite et annonce.
- `messaging/ConversationController.java` : `POST /conversations/inquiry` et filtrage par capacité sur la liste, les archives, le compteur et les routes par id.
- `messaging/MessagingNotifyController.java` : met à jour `inquiry_first_message_at` et `last_activity_at` à la notification d'un message.
- `messaging/FirestoreService.java` : mise à jour de champs du parent (`updateConversation` : promotion, reverrouillage, rattrapage), lecture du parent (`readConversation`), date du dernier message (`lastMessageInstant`, la plus récente du parent et du dernier message), purge d'une conversation INQUIRY avec son compteur `inquiryQuota/{convId}`. Le back ne crée jamais ce compteur.
- `messaging/ConversationMediaPolicy.java` : photos refusées en INQUIRY (`INQUIRY_TEXT_ONLY`).
- `messaging/dto/ConversationResponse.java` : `bidId` nullable, `announcementId`, `messageLimit`, `quotaLifted`.
- `calls/CallEligibilityService.java` : pas de 500 quand la conversation n'a pas de bid.
- `matching/BidService.java`, `BidTimeoutScheduler.java`, `events/BidRejectedEvent.java` : `previousStatus` (additif) pour distinguer une demande jamais acceptée.
- `i18n/messages_{fr,en}.properties` : 4 erreurs `problem.inquiry-*`.
- Fiche assistant `messagerie` (fr, en) : la conversation apparaît dès la première question, plus seulement après l'acceptation.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'app appelle `POST /api/v1/conversations/inquiry` avec `announcementId`.
2. `getOrCreateInquiry` vérifie : messagerie non suspendue, identité vérifiée (KYC), annonce visible (sinon 404 silencieux pour un brouillon), pas son propre trajet, annonce `ACTIVE`, pas de blocage entre les deux parties. Si une conversation existe déjà pour le couple (demandeur, annonce), elle est renvoyée (idempotent).
3. Sinon une ligne `conversations` (`kind = INQUIRY`) est créée. Convention de colonnes : `sender_id` = le demandeur, `traveler_id` = le propriétaire de l'annonce. Le document Firestore parent est créé (`kind = INQUIRY`, `quotaLifted = false`, aucun aperçu de message : l'app affiche sa note d'introduction). Le compteur `inquiryQuota/{convId}` n'est PAS créé par le back : c'est le client qui le crée à son premier message (`sent = 1`).
   Quand le fil existe déjà, il est renvoyé et son parent Firestore est rattrapé sur la base (`reconcileInquiryFirestore`).
4. Les 10 messages sont plafonnés par les règles Firestore (compteur), pas par le back. À chaque message, la Cloud Function notifie `MessagingNotifyController`, qui renseigne `inquiry_first_message_at` (une fois) et `last_activity_at`.
5. À la création d'une demande (`BidCreatedEvent`, `CashBidCreatedEvent`, première proposition d'une négociation, `BidMaterializedEvent`), `InquiryPromotionListener` appelle `promoteInquiryToBid` : `bid_id` rempli, `kind` = `SENDER_TRAVELER`, `quota_lifted_at` posé, quota Firestore levé. Les messages restent.
6. Si la demande se termine sans avoir jamais été acceptée (annulation par un participant en carte, refus avec `previousStatus`, expiration de négociation, expiration au départ, autorisation carte expirée, paiement abandonné), `relockInquiry` remet la conversation en INQUIRY et le plafond de 10 s'applique de nouveau.
7. Chaque nuit, `InquiryInactivityScheduler` supprime (soft delete) les INQUIRY sans activité depuis 30 jours, avec leurs messages Firestore et le compteur.

### Points d'entrée API
- `POST /api/v1/conversations/inquiry` : utilisateur authentifié et identité vérifiée. Réponses : toujours 200 `ConversationResponse` (création comme fil existant) ; 403 `kyc-required` ; 403 `inquiry-own-announcement` ; 403 `messaging-muted` ; 404 annonce introuvable ou brouillon ; 422 `inquiry-announcement-closed`.
- `GET /api/v1/conversations`, archives, compteur, routes par id : les conversations INQUIRY ne sont exposées qu'aux requêtes portant `X-Dony-Capabilities: inquiry` ; sans l'en-tête elles sont absentes des listes et les routes par id répondent 403 (comme un fil inconnu).
- Envoi de photos dans une INQUIRY : refusé (`INQUIRY_TEXT_ONLY`).

### Entités JPA impliquées
- `ConversationEntity` → table `conversations` : en INQUIRY `bid_id` est nul, `asker_id` et `announcement_id` sont obligatoires (CHECK). L'index unique partiel `conversations_inquiry_active_idx` limite à une conversation ouverte par couple (demandeur, annonce), tous types confondus, pour que le re-verrouillage ne puisse jamais entrer en collision.
- Firestore : `conversations/{convId}` et `inquiryQuota/{convId}` (compteur).

### Logique métier critique
- La limite de 10 vit dans les règles Firestore, levée par le back via le compteur : le back ne compte pas les messages.
- Promotion et re-verrouillage passent par des UPDATE gardés (pas de `save()` d'une entité possiblement périmée), pour éviter de ressusciter une ligne purgée ou d'annuler une promotion.
- La purge réserve d'abord la ligne par UPDATE gardé, vérifie de nouveau les blocages de conservation (`MessagingMediaRetentionHold`) et l'activité Firestore, puis supprime ; en cas d'échec Firestore elle annule la réservation. Une date Firestore illisible fait garder la conversation.
- Le compteur `inquiryQuota` n'est jamais réinitialisé tant que des messages INQUIRY existent : un demandeur à 10 messages ne regagne pas de quota par un cycle demande/annulation.
- Rattrapage base -> Firestore (`reconcileInquiryFirestore`) : création, promotion et reverrouillage n'écrivent Firestore qu'au mieux-effort. Le parent est relu au retour d'un fil existant (`POST /inquiry`), à l'acceptation d'un fil promu et à chaque message (notify) ; seuls `kind`, `quotaLifted` et `bidId` qui diffèrent de la base sont réécrits, un parent absent est recréé. Sans écart, aucune écriture.
- Ligne supprimée (`deleted_at`, posé seulement par la purge) : l'entité n'a pas de `@Where` (l'annulation de réservation doit la retrouver), donc chaque requête participant teste `deleted_at IS NULL`. Le notify traite une ligne supprimée comme fermée (200, ni réparation ni push) ; `findByFirestoreConversationId` reste sans filtre pour cette raison et pour le choix d'un identifiant destinataire libre.
- Un fil supprimé par l'un des participants n'est jamais promu (UPDATE gardé) : un fil neuf s'ouvre à l'acceptation. La promotion pose `inquiry_first_message_at` s'il manque, pour qu'un reverrouillage ne retire pas le fil de la liste du créateur.
- Aucun message système à la création (il créditerait un non-lu fantôme) : l'app affiche sa propre note d'introduction.

### Events Spring publiés / écoutés
- Écoutés par `InquiryPromotionListener` (AFTER_COMMIT) : `BidCreatedEvent`, `CashBidCreatedEvent`, `BidNegotiationMessagePostedEvent` (PROPOSAL, tour 1), `BidMaterializedEvent` (promotion) ; `BidCancelledByParticipantEvent` (carte), `BidRejectedEvent` (avec `previousStatus`), `BidNegotiationExpiredEvent`, `BidExpiredOnDepartureEvent`, `BidCardAuthorizationExpiredEvent`, `BidAwaitingPaymentAbandonedEvent` (re-verrouillage).
- `BidRejectedEvent` gagne un `previousStatus` nullable (additif).

### Pièges et points d'attention
- Compatibilité : une app publiée qui lit `bidId as String` plante sur une conversation sans bid. D'où le filtrage par `X-Dony-Capabilities`.
- Ne pas attraper `DataIntegrityViolationException` dans la transaction de `getOrCreateInquiry` (transaction PostgreSQL abandonnée) : la course du premier appel concurrent est gérée dans le contrôleur par une nouvelle tentative.
- Écrire dans Firestore avant le commit (promotion, re-verrouillage) peut diverger rarement de la base.
- `AWAITING_PAYMENT` est après acceptation en mobile money : ne jamais re-verrouiller sur cet état hors carte.
- La Cloud Function des non-lus et les règles Firestore ne connaissent que `senderId` / `travelerId` : ne pas renommer ces colonnes pour INQUIRY.
- `hideInquiryUnlessEnabled` (contrôleur) lève une `ResponseStatusException` 403 brute, traduite en `ProblemDetail` par `GlobalExceptionHandler`, pour répondre exactement comme le 403 existant d'un fil inconnu : choix assumé (un code métier dédié trahirait l'existence du fil).
- dony-admin : `AdminConversationResponse` peut porter un `bidId` nul (fil INQUIRY). Vérifier côté dony-admin qu'aucun lien `/bids/null` n'est construit.

## Déploiement et retour arrière
- **Numéro de migration** : `V387__conversations_inquiry.sql`, renumérotée au rebase du 14/11 (V383 à V386 étaient pris par `origin/main`). Revérifier l'absence de collision juste avant la fusion.
- **Verrous de la migration** : les `ALTER TABLE conversations` (DROP NOT NULL, ADD COLUMN avec clés étrangères vers `announcements` et `users`, DROP/ADD des CHECK qui relisent toute la table) prennent un verrou ACCESS EXCLUSIVE sur `conversations`, et l'index unique est créé sans `CONCURRENTLY` (écritures bloquées pendant sa construction). Déployer hors pic ; la table bloque lectures et écritures le temps de la migration.
- **Avant le déploiement** : compter `SELECT count(*) FROM conversations WHERE deleted_at IS NOT NULL;` en prod. Aucun code n'écrivait cette colonne avant cette fonctionnalité ; d'éventuelles lignes héritées disparaîtraient désormais des listes et des routes par id.
- **Retour arrière du back une fois des lignes INQUIRY créées** : un back antérieur ne connaît pas `kind = 'INQUIRY'` (énumération inconnue) et ne filtre pas `deleted_at` : ses listes de conversations tomberaient en 500 pour les participants concernés. Avant un retour arrière, faire disparaître les lignes `kind = 'INQUIRY'` (purge Firestore puis suppression des lignes, exception à la suppression logique à décider par le propriétaire). Les fils déjà promus (`SENDER_TRAVELER`, avec bid) restent lisibles par un back antérieur. La migration reste appliquée (Flyway ne revient pas en arrière) : `bid_id` nullable et les nouvelles colonnes sont ignorés par l'ancien code.

## Critères d'acceptation couverts
- [x] Identité vérifiée obligatoire : 403 `kyc-required`.
- [x] 10 messages du demandeur tant qu'aucune demande : plafond tenu par les règles Firestore et le compteur `inquiryQuota/{convId}`, créé par le client à son premier message (jamais par le back).
- [x] Limite levée à la demande, messages conservés : promotion sur les événements de demande.
- [x] Texte seul : photos refusées.
- [x] Suppression après 30 jours sans activité : `InquiryInactivityScheduler`.
- [x] Compatibilité des apps publiées : filtrage par `X-Dony-Capabilities`.

## Tests
- Vague de correction finale (revue de branche) : ligne purgée invisible (C1), rattrapage Firestore (I1), promotion gardée (I2, M6), code mort retiré (I5), aperçu et date d'activité (M1, M4), fiches et présente fiche. Les chiffres ci-dessous datent de la tâche 7, avant cette vague.
- `./mvnw test` : 17 012 tests, 0 échec, 0 erreur, 7 ignorés (ignorés déjà présents sur main), BUILD SUCCESS.
- Couverture JaCoCo mesurée (`target/site/jacoco/jacoco.csv`) : lignes 96,6 % (65 543 couvertes / 2 331 manquées), branches 87,0 %, instructions 96,1 %.
- Classes de la feature (lignes / branches) : `InquiryInactivityScheduler` 100 % / 94,1 % ; `InquiryCapability` 100 % / 100 % ; `InquiryPromotionListener` 100 % / 95,8 % ; `MessagingNotifyController` 100 % / 85,3 % ; `ConversationService` 93,9 % / 91,4 % ; `ConversationController` 86,7 % / 85,0 % ; `ConversationEntity` 87,3 % / 78,6 % ; `FirestoreService` 59,4 % / 58,7 % (classe entière, très largement antérieure à la feature et appelant le vrai SDK Firestore ; les nouvelles méthodes ne sont pas isolées dans le rapport).
- Tests ajoutés ou modifiés : les classes listées dans « Fichiers créés », plus `ConversationControllerTest`, `ConversationMediaPolicyTest`, `CallEligibilityServiceTest`, `BidServiceTest`, `BidTimeoutSchedulerTest`, `FirestoreServiceTest`, et les tests de la base de connaissances (fiches fr/en valides au chargement).

## Décisions techniques
- `bid_id` nullable plutôt qu'une table séparée : réutilise liste, archives, appels et Firestore ; contrainte CHECK pour garder le bid obligatoire hors INQUIRY.
- Index unique sur tous les types (et pas seulement INQUIRY) : évite qu'un re-verrouillage entre en collision avec une seconde INQUIRY ; coût : un second fil pour le même couple est impossible (aucun autre type ne renseigne ces colonnes).
- Pas de message système à la création : évite un non-lu fantôme.
- Seule une annonce `ACTIVE` accepte une question (brouillon : 404 silencieux ; autre statut : 422). Une question sur un trajet complet est donc refusée.
- Un fil existant est renvoyé tel quel même si l'identité ou la messagerie a changé depuis, mais le contrôle de blocage s'applique toujours.
- Fil lisible par son créateur avant le premier message (seul le filtre de liste le masque).
- Promotion et re-verrouillage par UPDATE gardés ; purge par réservation préalable ; ces choix privilégient le côté sûr (au pire une purge reportée d'un jour ou une promotion perdue).
- Le re-verrouillage est limité aux fins de demande dont on sait avec certitude qu'elle n'a jamais été acceptée. Non traités : refus sans statut (suppression de trajet, retraits admin), annulation de trajet, no-show voyageur, annulation avant paiement, délai de remise dépassé.

## Points à trancher par le propriétaire
1. Push FCM `NEW_MESSAGE` et crédit de non-lu Firestore d'une INQUIRY atteignent aussi les anciennes versions de l'app, qui ne peuvent pas lister la conversation : le toucher ouvre un écran « introuvable ». Supprimer le push vers les créateurs tant que l'app n'est pas adoptée, ou accepter ?
2. Une action gratuite (offre en espèces, proposition de négociation) lève le plafond de 10 messages. Le re-verrouillage n'atténue que si la demande finit sans acceptation : accepter ce risque ?
3. Annulation admin d'une demande jamais acceptée et suppression de trajet laissent le fil promu sans re-verrouillage : faut-il les re-verrouiller ?
4. Limitation de débit dédiée sur `POST /conversations/inquiry` (suggestion : 30/h dans Nginx) : l'ajouter ?
5. Re-verrouillage lui-même (spec : confirmable par le propriétaire) : à confirmer.
6. Les routes batch d'archivage/désarchivage/suppression ne sont pas filtrées par l'en-tête de capacité (oracle d'existence très faible, limité aux participants).

# Assistant IA : les colis de l'utilisateur (Backend)

**Date:** 2026-10-17
**Status:** ✅ Complète

## Résumé
L'assistant in-app peut parler des colis de l'utilisateur : retrouver celui dont il écrit le numéro DON-…, lui proposer ses colis quand il demande « où est mon colis ? », et répondre selon l'état réel du colis (étape, annulation, remboursement, code de retrait, retour, absence, litige). L'IA ne lit jamais la base : le back retrouve le colis, contrôle l'accès, calcule des faits et écrit lui-même tout ce qui dépend des données (numéro incorrect, aucun colis, choix, étape).

## Fichiers créés
- `common/assistant/` : `ParcelDirectory` (port, colis accessibles), `ParcelFactsContributor` (port, faits d'un package), `ParcelRef`, `ParcelRole`, `ParcelFacts`, `ParcelLink`. Même motif que `CallAvailability` : l'assistant ne dépend d'aucun service métier.
- `matching/AssistantParcelDirectory` : accès aux colis (expéditeur, voyageur via le trajet, destinataire au lien `CONFIRMED`), colis en cours puis clos depuis 14 jours.
- `matching/ParcelProgressFacts` (ordre 10) : rôle, trajet, étape.
- `tracking/PickupCodeFacts` (20) : état du code de retrait, régénérations restantes. Jamais sa valeur.
- `cancellation/CancellationParcelFacts` (30) : annulation possible ou non (`CancellationGuard`), retour en cours (`ParcelReturn`), absences déclarées (`NoShowReasons`).
- `payments/PaymentParcelFacts` (40) : paiement, remboursement carte ou mobile money, versement du voyageur, commission en espèces.
- `disputes/DisputeParcelFacts` (50) : litige ouvert ou tranché, gel des remboursements.
- `assistant/parcel/` : `AssistantParcelFlow` (retrouver, réunir, traduire les repères en boutons), `TrackingNumbers` (repérer, masquer, retirer le numéro), `AssistantLookupGuard` (10 numéros faux par jour), `ParcelTexts` (textes FR/EN écrits par le back).
- `assistant/dto/AssistantParcelChoice`.

## Fichiers modifiés
- `AssistantService` : parcours colis greffé avant la recherche de fiches (voir le flux).
- `AssistantPromptBuilder` : bloc `<parcel>`, règles 7 (faits vérifiés, jamais de code) et 8 (`about_my_parcel`).
- `AssistantAnswerParser` / `ParsedAnswer` : `about_my_parcel` facultatif (un fournisseur qui l'oublie ne fait pas basculer).
- `AssistantAskRequest` : `parcelId` facultatif. `AssistantAskResponse` : `parcelId` et `parcels`. Constructeurs de compatibilité gardés.
- `BidRepository` : `findAssistantParcelsOfSender` / `findAssistantParcelsOfTraveler`.
- Visibilité paquet (sans changement de valeur) : `BidService.TRACKING_NUMBER_VISIBLE_TO_TRAVELER_STATUSES`, `TrackingService.MAX_CODE_ATTEMPTS` / `MAX_CODE_REFRESHES_PER_DAY`, `CancellationService.MAX_RETURN_CODE_ATTEMPTS`.
- Fiches FR et EN : `suivi-colis`, `assistant-ia`, `annuler-envoi` (annulation verrouillée en route ou après le départ), `remboursement` (commission rendue en espèces, plus rien après versement).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Quota consommé comme avant.
2. `AssistantParcelFlow.lookup` : un numéro DON-… dans la question est cherché par `ParcelDirectory.byTrackingNumber`. S'il ne mène à aucun colis de l'utilisateur, la réponse est « numéro incorrect, ou colis pas lié à votre compte », la même que le numéro existe ou non. Sans numéro, le `parcelId` envoyé par l'app est revérifié par `byId` ; inaccessible, il est ignoré.
3. Recherche de fiches sur la question sans le numéro (relances et autre langue comme avant). Colis retrouvé mais aucune fiche (numéro seul) : réponse écrite par le back avec l'étape et le bouton du colis, sans fournisseur.
4. Colis connu : chaque `ParcelFactsContributor` donne ses faits, l'assistant les met dans `<parcel>` avec des repères (`parcel:open`, `parcel:new-code`, `/disputes`, `/support`) au lieu de chemins. Numéros masqués dans la question et l'historique.
5. Le modèle répond. Sans colis et avec `about_my_parcel` : le back remplace la réponse par « aucun colis en cours » ou par la liste des colis à choisir (`parcels`, 5 au plus, en cours d'abord).
6. Les repères deviennent les vrais chemins (`/bids/{id}`, `/receptions/{id}`, `/bids/{id}?action=new-code`), construits par le back ; les autres routes restent filtrées par la liste blanche des fiches. `parcelId` revient dans la réponse : l'app le renvoie avec les questions suivantes.

### Points d'entrée API
- `POST /api/v1/assistant/ask` (inchangé, utilisateur authentifié non invité) : `parcelId` facultatif en entrée ; `parcelId` et `parcels` en sortie.

### Entités JPA impliquées
Lecture seule : `bids`, `announcements`, `bid_recipient_links`, `payments`, `pawapay_operations`, `cancellations`, `disputes`. Aucune écriture, aucune migration.

### Logique métier critique
- Accès : expéditeur (envoi non retiré de sa liste), voyageur du trajet (non retiré), destinataire seulement au lien `CONFIRMED`. Une discussion de prix n'est jamais un colis.
- Le voyageur ne voit pas le numéro avant la remise (même règle que `BidService`).
- Rien de personnel ni de secret vers le fournisseur : ni code de retrait, ni code retour, ni nom, ni téléphone, ni numéro DON-, ni identifiant. Les villes passent par le nettoyage des balises.
- Un fournisseur de faits en panne est sauté (journal WARN), la réponse part avec les autres.
- Fournisseur d'IA indisponible et colis connu : réponse avec l'étape plutôt que le mode dégradé.

### Events Spring publiés / écoutés
Aucun.

### Pièges et points d'attention
- Ajouter un fait : un nouveau `ParcelFactsContributor` dans le package concerné, avec `@Order`. Toujours en phrases anglaises courtes, des conclusions et jamais une valeur secrète.
- Les faits reprennent des règles d'autres classes (limites de code, verrou d'annulation) : un changement de règle là-bas doit se refléter ici, les tests de faits le rappellent.
- Garde des numéros faux et quota sont en mémoire (Caffeine), par instance.

## Critères d'acceptation couverts
- [x] « Où est mon colis ? » sans colis : réponse immédiate « aucun colis en cours » (`aucunColisLeDitDesLeDebut`).
- [x] Avec des colis : ils sont proposés directement (`questionSurSonColisProposeSesColis`).
- [x] Numéro d'un colis de l'utilisateur : étape et bouton vers sa page (`numeroSeulDonneLEtapeEtLaFicheSansFournisseur`).
- [x] Numéro d'un autre ou inexistant : « numéro incorrect », rien révélé (`unAutreNeVoitRienNiParNumeroNiParIdentifiant`, `numeroInconnuOuDUnAutreRepondIncorrectSansFournisseur`).
- [x] Aide selon l'état : remboursement, code de retrait, code retour, annulation, litige, absence (tests des cinq fournisseurs de faits).

## Tests
- Ajoutés : `AssistantParcelDirectoryTest` (base), `ParcelProgressFactsTest`, `PickupCodeFactsTest`, `CancellationParcelFactsTest`, `PaymentParcelFactsTest`, `DisputeParcelFactsTest`, `AssistantParcelFlowTest`, `TrackingNumbersTest`, `AssistantLookupGuardTest` ; cas colis dans `AssistantServiceTest`, `AssistantPromptBuilderTest`, `AssistantAnswerParserTest`, `AssistantControllerIntegrationTest`.
- Local : 493 tests de l'assistant et des faits verts. Suite complète et couverture : CI de la PR (la suite locale a été interrompue par manque de mémoire, sans échec).

## Décisions techniques
- Ports dans `common/` plutôt qu'injection de services d'autres packages (règle du projet).
- Textes dépendant des données écrits par le back : aucune reformulation ne peut révéler ni inventer.
- Repères de routes plutôt qu'UUID dans le prompt : le modèle ne recopie jamais un identifiant.
- Choix des colis même pour un seul colis (décision du propriétaire : proposer directement).

# Story — Page publique du QR code colis (Backend)

**Date:** 2026-10-15
**Status:** ✅ Complète

## Résumé
Scanner le QR collé sur un colis avec l'appareil photo d'un téléphone renvoyait un 401 :
l'URL encodée (`/api/v1/tracking/{bidId}/scan`) n'était ni publique ni servie. Elle rend
maintenant une page HTML publique qui dit ce que contient le colis, d'où il part, où il va,
qui l'envoie, qui le transporte et qui le récupère (prénom + initiale), avec le contact
Yadony. Un endpoint authentifié donne aussi à l'app le rôle de l'utilisateur sur un colis.

## Fichiers créés
- `common/PublicNameFormatter.java` — « Prénom N. » depuis un nom libre ; ne garde que les mots faits de lettres (un numéro ou un e-mail tapé dans le nom ne sort jamais).
- `tracking/ParcelSteps.java` — libellés d'étapes et étape courante, partagés par la page de suivi et la page du QR.
- `tracking/ParcelScanController.java` — `GET /tracking/{bidId}/scan`, page publique.
- `tracking/ParcelScanView.java` — données affichées d'un colis actif (aucun champ sensible).
- `templates/parcel/scan.html` — gabarit Thymeleaf, mobile d'abord, `noindex`.
- `matching/reception/dto/ParcelRoleResponse.java` — `SENDER | TRAVELER | RECIPIENT | NONE`.

## Fichiers modifiés
- `tracking/RecipientController.java` — utilise `ParcelSteps` ; « Colis remis au voyageur — en route » devient « Remis au voyageur, en route » (plus de tiret cadratin).
- `config/SecurityConfig.java` — `/tracking/*/scan` en `permitAll` (un seul segment : `/tracking/{id}/qr-code` reste authentifié).
- `matching/reception/ReceptionService.java`, `ReceptionController.java` — `GET /receptions/role/{bidId}`.
- `application.yml` — `yadony.support.whatsapp-number` (`SUPPORT_WHATSAPP_NUMBER`), `yadony.support.email` (`SUPPORT_EMAIL`).
- `.env.prod.template`, `docker-compose.staging.yml` — les deux variables, sans valeur réelle.
- `CLAUDE.md` — nouvel endpoint public.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Le QR (généré par `TrackingService#generateQrCode`) contient `{APP_BASE_URL}/api/v1/tracking/{bidId}/scan`.
2. Scanné hors app, le navigateur appelle cette URL sans jeton ; la règle `permitAll` la laisse passer.
3. `ParcelScanController` charge bid, annonce, événements, expéditeur et voyageur.
4. Statut dans `ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED, COMPLETED` → page complète. Autre statut ou annonce introuvable → page « Ce colis n'est plus actif » (200, contact seulement). UUID inconnu ou invalide → même page en 404.
5. Le bouton « Ouvrir dans Yadony » mène à `yadony://colis/{bidId}` ; l'app appelle `GET /receptions/role/{bidId}` et ouvre la bonne fiche.

### Points d'entrée API
- `GET /api/v1/tracking/{bidId}/scan` — public, HTML.
- `GET /api/v1/receptions/role/{bidId}` — authentifié (non invité), JSON `{"role": ...}` ; `NONE` pour un colis inconnu, sans dire s'il existe.

### Entités JPA impliquées
- `BidEntity` (lecture) : `trackingNumber`, `status`, `description`, `contentCategory` (libellés français joints par virgule, pas des codes), `weightKg`, `recipientName`, `senderId`.
- `AnnouncementEntity` (lecture) : villes, dates, `travelerId`. `arrivalInstructions` n'est **jamais** lu par la page.
- `BidRecipientLinkEntity` (lecture) : lien destinataire, `DECLINED` = pas de rôle.

### Logique métier critique
- La clé est le `bidId`, pas le `trackingToken` : ce dernier change quand le destinataire change, le QR collé sur le colis mourrait en route.
- Expéditeur et voyageur : `UserEntity.publicDisplayName()` (prénom + initiale ; repli sur un `username` généré, jamais saisi). Destinataire : `PublicNameFormatter.shortName`.
- Jamais affichés : téléphones, noms complets, valeur déclarée, prix, code de remise (même public), adresse de retrait, lieu de scan (`gpsLabel` commence par la rue, souvent celle de l'expéditeur).
- La description passe par `PublicTextSanitizer.maskContacts` : numéros (≥ 6 chiffres) et e-mails remplacés par « ••• ».
- Un colis livré redevient « plus actif » 14 jours après la livraison (dernier scan ARRIVEE, sinon `updatedAt`).
- `<meta name="referrer" content="no-referrer">` : WhatsApp et les stores ne reçoivent pas l'URL de la page.
- Bouton WhatsApp masqué tant que `SUPPORT_WHATSAPP_NUMBER` est vide ; liens stores masqués si `STORE_URL_IOS` / `STORE_URL_ANDROID` sont vides.
- `role` déclenche `ReceptionLinker.catchUp` avant de chercher le lien destinataire : un destinataire qui n'a jamais ouvert Réceptions est reconnu dès son premier scan.

### Events Spring publiés / écoutés
Aucun. Compteur Micrometer `yadony.parcel.public_scan{outcome=shown|inactive|not_found}`.

### Pièges et points d'attention
- Ne pas élargir la règle à `/tracking/**` : `qr-code`, `confirmation-code`, `events` doivent rester authentifiés (test `qrCode_resteProtege`).
- La description passe en `th:text` ; ne jamais passer en `th:utext` (test `descriptionHtml_echappee`).
- L'ancien destinataire, s'il a vu le QR sur l'ancienne page de suivi, garde l'accès à la page publique par `bidId` (même niveau d'information que tout détenteur du QR).

## Critères d'acceptation couverts
- [x] Scan hors app → page lisible sans compte (`colisEnCours_pagePubliqueSansJeton`).
- [x] Contenu, trajet, expéditeur, destinataire, voyageur affichés en prénom + initiale.
- [x] Aucun numéro de téléphone (`jamaisDeDonneesSensibles`, `PublicNameFormatterTest`).
- [x] Contact Yadony WhatsApp + e-mail.
- [x] Bouton vers l'app + endpoint de rôle (`role_*`).

## Tests
- `./mvnw test jacoco:report` → 9979 tests, 0 échec, 7 ignorés. Couverture globale 94,6 % (instructions) ; `ParcelScanController` 94,1 %, `ParcelSteps` 100 %, `PublicNameFormatter` 100 %, `ReceptionService` 98,6 %.
- Tests ajoutés : `PublicNameFormatterTest`, `ParcelStepsTest`, `ParcelScanControllerIntegrationTest`, `ParcelScanControllerSansWhatsappIntegrationTest`, tests `role_*` dans `ReceptionControllerIntegrationTest` ; `RecipientControllerIntegrationTest` mis à jour (libellé « en route »).

## Décisions techniques
- Page servie sur l'URL déjà encodée plutôt qu'une belle URL `yadony.com/colis/{id}` : les QR imprimés marchent sans réimpression et les anciennes versions de l'app continuent de scanner (leur parseur exige `tracking/<uuid>`).
- Endpoint de rôle dans `matching/reception/` et non `tracking/` : il appelle `ReceptionLinker`, service du package `matching`.
- Pas d'entrée `audit_log` par scan (page publique en lecture, volume imprévisible) : compteur Micrometer à la place.

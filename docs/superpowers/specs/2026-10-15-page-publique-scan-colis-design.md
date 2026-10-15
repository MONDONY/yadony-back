# Page publique du QR code colis — design

**Date :** 2026-10-15
**Statut :** validé en discussion, spec à relire
**Dépôts :** yadony-back (PR 1), dony_app (PR 2, jumelle, fusionnée après la PR 1)

## Problème

Le QR code d'un colis encode `https://api.yadony.com/api/v1/tracking/{bidId}/scan`
(`TrackingService.java:142`, `RecipientController.java:206`). Aucun contrôleur ne
répond à `/scan`, et la route n'est pas dans le `permitAll` de `SecurityConfig` :
scanné par l'appareil photo d'un téléphone, le QR renvoie un 401 JSON
(« Authentication required »), et renverrait un 404 même avec un jeton.
Dans l'app, le scan marche seulement parce que `qr_camera_view.dart` extrait
l'UUID de l'URL sans l'ouvrir.

## Objectif

N'importe qui scannant le QR collé sur un colis (agent d'aéroport, douanier,
personne qui trouve le colis, destinataire) voit, sans application ni compte,
une page qui dit en quelques secondes :

- ce que contient le colis ;
- à qui il appartient, d'où il part, où il va ;
- qui doit le récupérer, qui le transporte ;
- qui contacter : Yadony (WhatsApp + e-mail), jamais le numéro d'un particulier.

Si la personne a l'application, un bouton l'emmène sur le colis dans l'app.

## Décisions prises

| Sujet | Décision | Raison |
|---|---|---|
| Adresse | Garder l'URL actuelle du QR, rendre `/tracking/{bidId}/scan` publique et y servir la page HTML | Les QR déjà imprimés marchent tout de suite ; les anciennes versions de l'app continuent de scanner (leur parseur exige `tracking/<uuid>`) ; aucun changement nginx |
| Clé | `bidId` (UUID v4), pas le `trackingToken` | Le `trackingToken` change quand l'expéditeur modifie le destinataire : le QR collé sur le colis mourrait en route |
| Identités | Prénom + initiale du nom (« Awa D. ») pour expéditeur, destinataire, voyageur | Le QR est visible de tous : on identifie le colis sans livrer un nom complet à un inconnu |
| Contenu | Catégories (libellés français) + description + poids, affichés | Demandé explicitement ; utile au contrôle |
| Photos | Celles des scans (dépôt, transit, arrivée), URL signées 1 h | Déjà exposées par la page de suivi actuelle |
| Contact | Bouton WhatsApp Yadony (message pré-rempli avec le numéro de suivi) + e-mail `support@yadony.com` | Choix C ; aucun numéro de support n'existe encore, il vient de la configuration |
| Ouverture de l'app | Bouton « Ouvrir dans Yadony » → `yadony://colis/{bidId}`, visible par tous ; l'app décide de l'écran selon le rôle | La page web ne sait pas qui scanne ; les liens universels https sont un autre chantier |

## Jamais affiché

Numéro de téléphone (expéditeur, destinataire, voyageur), nom complet, valeur
déclarée, prix, code de remise (même le code public), adresse/instructions de
retrait, identifiants internes autres que le `bidId` déjà dans l'URL.

## PR 1 — backend (yadony-back)

### Route

`GET /api/v1/tracking/{bidId}/scan` → HTML (Thymeleaf), servie par un nouveau
contrôleur `ParcelScanController` dans `tracking/`, à côté de
`RecipientController` (la page de suivi lit déjà `BidRepository` et
`AnnouncementRepository` depuis ce package).

- `SecurityConfig` : ajouter `/tracking/*/scan` au `permitAll`, GET seulement.
  Ne pas élargir à `/tracking/*/**`.
- `bidId` non UUID → page « QR code non reconnu » (HTTP 404), pas de 400 JSON.
- Rate limit : la limite nginx générale (120 req/min, burst 60) suffit ; l'UUID
  aléatoire rend l'énumération impossible.

### Données de la page (record `ParcelScanView`)

| Champ | Source | Règle |
|---|---|---|
| `trackingNumber` | `bid.trackingNumber` | |
| `statusLabel` | même logique que `RecipientController.resolveCurrentStep` + `COMPLETED` → « Livré » | extraire la logique dans une méthode partagée du package `tracking/`, pas de copie |
| `departureCity`, `arrivalCity`, `departureDate`, `arrivalDate` | `AnnouncementEntity` | |
| `contentCategories` | `bid.contentCategory` (liste séparée par des virgules) | traduite en libellés français ; code inconnu → affiché tel quel |
| `description` | `bid.description` | échappée par Thymeleaf (`th:text`), jamais `th:utext` |
| `weightKg` | `bid.weightKg` | |
| `senderName` | `UserEntity` de `bid.senderId` | « Prénom N. » |
| `recipientName` | `bid.recipientName` (texte libre) | « Prénom N. » : premier mot + initiale du dernier mot ; un seul mot → tel quel |
| `travelerName` | `UserEntity` de `announcement.travelerId` | « Prénom N. » |
| `events` | `TrackingEventRepository.findByBidIdOrderByScannedAtAsc` | libellé, date, `gpsLabel`, photo signée 1 h |
| `appLink` | `yadony://colis/{bidId}` | |
| `whatsappUrl` | `https://wa.me/{numéro}?text=…` | absent si `yadony.support.whatsapp-number` est vide → bouton masqué |
| `supportEmail` | `yadony.support.email` (défaut `support@yadony.com`) | `mailto:` avec sujet « Colis {trackingNumber} » |

Le formatage « Prénom N. » vit dans une petite classe dédiée (`PublicNameFormatter`
dans `common/`, réutilisable), testée à part : espaces multiples, accents,
nom composé (« Mamadou Lamine Diop » → « Mamadou D. »), chaîne vide ou nulle → « — ».

### Colis non affichable

Bid introuvable, supprimé (soft delete), ou statut hors
{ACCEPTED et suivants : en cours, arrivé, COMPLETED} (c.-à-d. en négociation,
refusé, annulé, expiré) → page « Ce colis n'est plus actif » avec seulement le
contact Yadony. Aucun nom, aucune ville. La liste exacte des statuts acceptés
est fixée à l'implémentation en lisant `BidStatus` ; règle : on affiche dès
qu'un QR a pu être généré (`qrToken` non nul) et tant que le bid n'est ni
annulé ni supprimé.

### Configuration

```yaml
yadony:
  support:
    whatsapp-number: ${SUPPORT_WHATSAPP_NUMBER:}   # format international sans +, ex. 33612345678
    email: ${SUPPORT_EMAIL:support@yadony.com}
```

À ajouter dans `.env.prod.template` et `docker-compose.staging.yml`.

### Gabarit

`templates/parcel/scan.html`, mobile d'abord, même feuille de style que
`recipient/tracking.html`. Ordre : statut + numéro de suivi, trajet, contenu,
personnes, étapes avec photos, contact Yadony, bouton « Ouvrir dans Yadony »
(+ liens stores en dessous, petits). Couleurs unies, pas de dégradé ; pas de
tiret cadratin dans les textes. Balise `<meta name="robots" content="noindex">`.

Le bouton « Ouvrir dans Yadony » : lien `yadony://colis/{bidId}` ; sous lui,
« Pas encore l'application ? » avec les liens App Store / Play Store
(URL en configuration, masqués si vides).

### Audit

Pas d'entrée `audit_log` par scan (page publique en lecture, volume
imprévisible). Un compteur Micrometer `parcel.public_scan` (tag `outcome` :
`shown`, `inactive`, `not_found`) suffit à suivre l'usage dans Grafana.

### Endpoint de rôle pour l'app

`GET /api/v1/receptions/role/{bidId}` (authentifié, non invité ; appelé
« my-role » ci-dessous) →
`{ "role": "SENDER" | "TRAVELER" | "RECIPIENT" | "NONE" }`.

- `SENDER` : `bid.senderId` = utilisateur courant.
- `TRAVELER` : `announcement.travelerId` = utilisateur courant.
- `RECIPIENT` : lien `bid_recipient_links` PENDING ou CONFIRMED pour cet
  utilisateur. Le rattachement par numéro (`ReceptionLinker.catchUp`) est
  déclenché avant la vérification pour qu'un destinataire qui n'a jamais ouvert
  Réceptions soit reconnu. `ReceptionLinker` est dans `matching/` : l'endpoint
  vit donc dans `matching/reception/` (`ReceptionController`), pas dans
  `tracking/`, pour ne pas injecter de service d'un autre package.
- sinon `NONE` (200, pas 403 : l'app affiche un message, pas une erreur).

### Tests (PR 1)

- `ParcelScanControllerIntegrationTest` (MockMvc, sans jeton) : 200 HTML avec
  les champs attendus ; aucun téléphone, aucune valeur déclarée, aucun nom
  complet, aucun code de remise dans le HTML ; colis annulé → page inactive ;
  UUID inconnu → 404 HTML ; chaîne non UUID → 404 HTML ; WhatsApp masqué si
  numéro vide ; description contenant `<script>` échappée.
- `PublicNameFormatterTest`.
- `SecurityConfig` : `/tracking/{id}/qr-code` reste 401 sans jeton (pas
  d'ouverture trop large).
- Endpoint de rôle : quatre rôles + rattachement par numéro déclenché.
- Couverture ≥ 90 % sur le code ajouté.

## PR 2 — app (dony_app), jumelle, après la PR 1

- Résolveur paramétré `resolveParcelDeepLink` pour `yadony://colis/{uuid}`
  (UUID strict), ajouté à `_parameterizedResolvers` dans `app.dart`.
- Android : intent-filter `<data android:scheme="yadony" android:host="colis"/>`
  dans `AndroidManifest.xml`. iOS : rien (le schéma accepte tous les hôtes).
- Écran de transition `/colis/:bidId` : appelle `my-role`, puis `go` vers
  `/receptions/:bidId` (RECIPIENT), `/bids/:bidId` (SENDER, TRAVELER), ou affiche
  « Ce colis n'est pas lié à votre compte » + bouton Contacter Yadony (NONE).
  Utilisateur non connecté → connexion, puis reprise du lien (mécanisme
  `_deepLinkGate` existant).
- Tolérance de l'ancien contrat : endpoint `my-role` absent (404 avant
  déploiement du back) → message « Mettez à jour… » neutre, pas de crash.
- Corriger le commentaire de `qr_camera_view.dart` (le QR contient
  `/api/v1/tracking/<id>/scan`).
- Tests : BLoC de l'écran de transition (4 rôles, erreur réseau, 404),
  test du résolveur (UUID valide, invalide, autre hôte).

## Hors périmètre

- Liens universels / App Links https (ouverture de l'app sans passer par le navigateur).
- Belle URL `yadony.com/colis/{id}`.
- Signalement « j'ai trouvé ce colis » avec formulaire.
- Traduction anglaise de la page (la page de suivi actuelle est en français seulement).

## Déploiement

PR 1 fusionnée et déployée sur staging, `SUPPORT_WHATSAPP_NUMBER` posé
(numéro à fournir par le propriétaire), puis PR 2. Prod au propriétaire.

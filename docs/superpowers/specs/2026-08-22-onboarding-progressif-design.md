# Onboarding progressif jusqu'à Stripe Connect v2

**Date :** 2026-08-22
**Statut :** conception validée, non implémentée
**Périmètre :** `dony-back` (V230, endpoint adresse, préremplissage) + `dony_app` (résolveur, jauge, 2 écrans)
**Prérequis :** back #217 et front #271 fusionnés (Accounts v2 + `connectAvailableInCountry`)

---

## 1. Le problème

Le voyageur saisit son identité **jusqu'à trois fois** avant de pouvoir être payé.

| Donnée | Profil (déclaré) | Stripe Identity (vérifié) | Stripe Connect (exigé) |
|---|---|---|---|
| Prénom / Nom | ✅ | ✅ | ✅ |
| Date de naissance | ✅ | ✅ | ✅ |
| Adresse complète | ❌ *ville seule* | ✅ | ✅ |
| E-mail / Téléphone | ✅ *Firebase* | ✅ | ✅ |
| Pays | ✅ *étape 2* | — | ✅ |
| IBAN | ❌ | ❌ | ✅ |

Rien ne circule entre ces trois collectes. `KycVerificationEntity` ne garde que
`status`, `rejectionReason`, `rejectionCode` — jamais les données vérifiées.

L'onboarding actuel (`resolvePostSignupRoute`) enchaîne trois écrans en dur —
consentement, pays, parrainage — pilotés par des drapeaux Hive **locaux**.

## 2. Le principe retenu — l'état déduit

**Aucune progression n'est stockée.** Chaque étape est « faite » si le fait serveur
correspondant existe déjà.

| # | Étape | Route | Fait qui la valide |
|---|---|---|---|
| 1 | Consentement | `/auth/analytics-consent` | consentement analytics backend |
| 2 | Pays | `/auth/country-selection` | `user.country != null` |
| 3 | Identité | `/kyc/verify` | `user.kycStatus == VERIFIED` |
| 4 | Adresse | `/auth/residence-address` **(nouveau)** | `user.residenceStreet != null` |
| 5 | Paiements | `/payments/onboarding` | `stripeAccountStatus == ONBOARDING_COMPLETE` |
| — | Parrainage | `/auth/referral-code` | hors décompte, facultatif |

**Étape 5 conditionnelle :** absente si `connectAvailableInCountry == false`. La jauge
passe alors à quatre segments et l'utilisateur atteint réellement 4/4.

### Pourquoi ce choix plutôt qu'une table de progression

Quatre situations réelles où une progression stockée se désynchronise, et où celle-ci
reste juste **sans une ligne de code de synchronisation** :

- vérification d'identité faite depuis *Profil › Documents d'identité* ;
- onboarding Connect terminé depuis le **lien Stripe reçu par e-mail**, hors de l'app
  (le webhook `account.updated` met `stripeAccountStatus` à jour) ;
- réinstallation ou changement d'appareil ;
- passage d'un pays couvert à un pays non couvert — la jauge perd un segment toute seule.

Le projet a déjà payé ce prix avec le consentement analytics : le correctif fut d'en
faire une donnée serveur. `resolvePostSignupRoute` le documente noir sur blanc.

## 3. Backend

### 3.1 Migration V230

La dernière migration appliquée est **V229** (`wallet_refund_eligibility`).

```sql
ALTER TABLE users
  ADD COLUMN residence_street      VARCHAR(255),
  ADD COLUMN residence_line2       VARCHAR(100),
  ADD COLUMN residence_postal_code VARCHAR(20),
  ADD COLUMN onboarding_seen_at    TIMESTAMPTZ;
```

`city` et `country` existent déjà et servent aux deux usages — aucune duplication.

**Toutes nullables.** Aucune donnée existante n'est invalidée, et c'est précisément ce
qui rend l'étape passable. Toute colonne `NOT NULL` casserait `V89MigrationTest`, dont
l'`INSERT` ne connaît pas ces champs.

### 3.2 Adresse de résidence

`PUT /users/me/residence-address` — corps : `street`, `line2?`, `postalCode`, `city`.
Le pays n'est pas dans le corps : il vient de `user.country`, figé à l'étape 2.

Erreurs en RFC 7807 via `GlobalExceptionHandler`, jamais de `Map` ni de `String` brut.
Écriture tracée dans `audit_log`.

`GET /users/me` expose les quatre champs pour que le résolveur Flutter les lise.

### 3.3 `onboarding_seen_at`

Un seul champ suffit, et il remplace le drapeau Hive `kCountryOnboardingSeen`.

- `null` → le parcours s'impose après l'inscription ;
- renseigné → il ne s'impose **plus jamais** ; on n'y entre que par la carte de reprise.

Posé quand l'utilisateur atteint l'accueil depuis le parcours, qu'il ait tout fait ou
tout passé. Inutile de mémoriser *quelle* étape a été sautée : l'étape incomplète se
recalcule.

`PUT /users/me/onboarding-seen` (idempotent, ne réécrit pas si déjà posé).

### 3.4 Préremplissage Connect

`StripeV2AccountProvisioner` enrichit `AccountCreateParams` :

| Champ Connect v2 | Origine |
|---|---|
| `identity.individual.givenName` | Identity `verified_outputs.firstName` |
| `identity.individual.surname` | Identity `verified_outputs.lastName` |
| `identity.individual.dateOfBirth` | Identity `verified_outputs.dob` |
| `identity.individual.address` | **`user.residence*` — étape 4, pas le document** |
| `identity.individual.email` / `phone` | Identity, repli sur `UserEntity` |
| `identity.country`, `entityType` | déjà transmis aujourd'hui |

**L'adresse ne vient jamais du document.** `verified_outputs.address` est l'adresse
figurant sur la pièce d'identité, potentiellement périmée ; Connect demande la
résidence. On utilise la nôtre.

**Lecture à la demande, aucun stockage :**
`VerificationSession.retrieve(sessionId, expand=verified_outputs)`.
`stripeVerificationSessionId` est déjà en base → aucune donnée d'identité
supplémentaire au repos, donc aucune obligation RGPD nouvelle (export, suppression).

**Accès inter-package :** `payments/` injecte `KycRepository`. La règle « Spring Events
only » vise les *services* ; l'injection de **repository** est la convention réelle du
dépôt — `export/UserDataExportService` et `auth/AccountFinalizationService` le font déjà.

**Jamais bloquant.** Session absente, non vérifiée, appel Stripe en échec, champ
manquant : le compte se crée **sans** préremplissage, avec un `log.warn`. C'est un
confort, pas une condition. Aucune exception ne remonte.

**Ne pas tenter de réutiliser la vérification Identity comme document Connect :**
`documents.primaryVerification` attend des **identifiants de fichiers**, pas une
référence de session. Et l'onboarding `recipient` n'a demandé **aucun document** lors
de la vérification sandbox du 2026-08-22.

## 4. Flutter

### 4.1 Le résolveur

`resolvePostSignupRoute` — l'enchaînement de `if` actuel — devient :

```dart
OnboardingStep? nextStep(UserModel user, StripeAccountState stripe, bool analyticsAnswered)
```

Fonction **pure**, sans réseau ni widget : un test par combinaison d'états.

Deux appelants seulement — la redirection post-inscription et la carte de reprise — donc
ils ne peuvent pas se contredire.

L'étape 5 est retirée de la liste quand `stripe.connectAvailableInCountry` est faux
(extension `StripeAccountAvailability`, livrée avec front #271 : elle vaut `true` tant
que le statut n'est pas chargé, donc la jauge ne perd jamais un segment par accident
réseau).

### 4.2 La jauge

Composant `DonyOnboardingGauge` dans `lib/core/design/widgets/`, puisqu'il est utilisé
par trois surfaces (parcours, récapitulatif, carte d'accueil).

**Segmentée, pas continue** : un segment par étape, pour montrer *lesquelles* sont
faites et pas seulement combien. Segment en cours à demi rempli. Une étape **passée
reste vide** — passer n'est pas terminer.

Le **parrainage n'entre pas dans le décompte** : il n'apporte rien au compte et reste
facultatif ; l'y compter ferait stagner un compteur que l'utilisateur croit devoir remplir.

Contraintes du design system :
- couleurs par `cs.*` / `DonyColors`, jamais de `Color(0xFF…)` ;
- compteur en **chiffres tabulaires** — `fontFeatures: const [FontFeature.tabularFigures()]`,
  comme `dony_price_tag.dart:91` ; il n'existe pas d'helper dédié dans les tokens ;
- animation de remplissage `flutter_animate`, entrée 250–300 ms `easeOutCubic` ;
- `Semantics(value: 'Étape 3 sur 5')` — l'information ne passe pas que par la couleur.

### 4.3 Écran à créer : adresse de résidence

`lib/features/auth/presentation/screens/residence_address_screen.dart`

- coque `DonyPageScaffold` — jamais de `Scaffold` + `DonyAppBar` recopiés à la main ;
- champs `DonyTextField` (`DonyRadius.md`), pays **verrouillé** et repris de l'étape 2 ;
- bouton `DonyButton` pleine largeur, hauteur 52, `DonyRadius.lg` ;
- réutilise le formulaire et l'autocomplétion de `pickup_addresses` ;
- bouton « Reprendre une adresse enregistrée » qui **propose** sans jamais reprendre
  automatiquement : un point de retrait n'est pas un domicile légal ;
- état via BLoC, jamais `setState` ; navigation par GoRouter, jamais `Navigator.push`.

### 4.4 Écran récapitulatif

`/account/setup` — liste les étapes avec leur état, laisse choisir l'ordre.

La route suit la convention des états de compte hors shell (`/account/disabled`,
`/account/rejected`). **Ne pas la placer sous `/onboarding`** : cette route existe déjà
et porte l'écran d'introduction marketing (`router.dart:294`), sans rapport.

L'ordre n'est pas imposé : la seule dépendance réelle (identité avant paiements) est
**souple**, puisque Connect fonctionne sans préremplissage.

Disparaît quand `nextStep` rend `null` — aucun état à nettoyer.

### 4.5 Carte de reprise

Intégrée à `EvergreenGuidanceCarousel` (accueil), qui porte déjà la logique de fermeture
— plutôt qu'une surface de plus. Titre, jauge, prochaine étape, CTA.

Ni modale ni bloquante : l'app reste pleinement utilisable — rechercher, envoyer un
colis, discuter.

### 4.6 Publication sans KYC

**La règle ne change pas.** `assertCanPublish` refuse toujours avec un 403
`kyc-not-verified`.

Ce qui change est la réponse de l'app : au lieu d'un message d'erreur, elle affiche
**l'étape 3** avec sa raison d'être, là où elle devient concrète, puis ramène l'utilisateur
à sa publication. Le refus devient un détour, plus une impasse.

## 5. Boutons vivants et responsive — critères vérifiables

Exigence explicite : **tout bouton doit fonctionner, et tout écran tenir sur le
téléphone de test**. Traduite en critères mesurables, pas en intentions.

### 5.1 Aucun bouton mort

- tout `DonyButton` a un `onPressed` non nul, ou est explicitement désactivé pendant un
  chargement (`onPressed: null`, opacité 0.4) ;
- tout `DonyButton` d'un bottom sheet est dans `stickyBottom`, **jamais** dans le `child`
  scrollable (règle absolue du `CLAUDE.md`) ;
- chaque écran a un test widget qui **tape** chaque bouton et vérifie l'effet attendu
  (navigation, event BLoC émis) — pas seulement sa présence.

### 5.2 Responsive

Le harnais existe déjà : `pumpAt200` (`test/a11y/large_text_smoke_test.dart:131`) simule
**1080×2400 en densité 3, soit 360×800 logiques, à 200 % de taille de texte** — la classe
exacte du téléphone de test (720×1640).

Les deux nouveaux écrans **entrent dans cette suite** :

```dart
testWidgets('adresse de résidence', (tester) async {
  await pumpAt200(tester, _wrapResidenceAddress());
  expect(tester.takeException(), isNull);
});
```

Un débordement lève une exception que `takeException()` capture — le test échoue.

S'ajoutent les suites déjà en place, à compléter pour les nouveaux écrans :
`tap_targets_test.dart` (cibles ≥ 44 pt), `screen_reader_test.dart` (semantics),
`theme_contrast_audit_test.dart` (contrastes AA), et le mode sombre via les tokens
`cs.*` — jamais de couleur sémantique light-only dans un `build()`.

Largeurs adaptatives par `DonyLayout.hPadding` et `DonyLayout.constrained`, qui portent
déjà le padding responsive et la largeur maximale sur tablette.

### 5.3 Vérification sur device

Avant de marquer le lot terminé : parcours complet sur le téléphone connecté, dans les
deux thèmes, à taille de texte normale **et** à 200 %.

## 6. Analytics

Obligatoire (`CLAUDE.md`) : nouveaux noms dans `AnalyticsEvents`, émis **dans le BLoC**,
`unawaited`, aucune PII.

| Event | Déclencheur | Propriétés |
|---|---|---|
| `onboarding_step_viewed` | entrée sur une étape | `step`, `index`, `total` |
| `onboarding_step_completed` | étape validée | `step` |
| `onboarding_step_skipped` | « Passer pour l'instant » | `step` |
| `onboarding_exited` | croix → accueil | `step`, `completed_count` |
| `onboarding_resumed` | carte ou récapitulatif | `source` (`card`/`checklist`), `step` |
| `onboarding_completed` | dernière étape franchie | `steps_total` |
| `residence_address_saved` | adresse enregistrée | `from_saved` (booléen) |

`step` est une **énumération fermée** (`consent`, `country`, `identity`, `address`,
`payouts`, `referral`) — jamais de texte libre. Aucune adresse, aucun nom, aucun numéro.

Tout nouveau BLoC reçoit `AnalyticsService` en paramètre et est enregistré dans
`injection.dart`. Table des events du `CLAUDE.md` à mettre à jour.

## 7. Découpage

| Lot | Contenu | Dépend de |
|---|---|---|
| **1** | V230 + `PUT /users/me/residence-address` + écran Flutter + tests a11y | — |
| **2** | `nextStep` + `onboarding_seen_at` + branchement post-inscription + jauge | 1 |
| **3** | Préremplissage Connect depuis Identity | 1, 2 |
| **4** | Récapitulatif + carte de reprise + renvoi depuis le refus de publication | 2 |

Chaque lot est livrable seul et laisse l'app cohérente. Deux dépôts, donc deux branches
et deux PR par lot quand il touche les deux côtés.

## 8. Tests

Backend : unitaires (résolveur d'adresse, préremplissage avec et sans session Identity,
échec Stripe non bloquant) + intégration MockMvc sur les deux endpoints. Couverture ≥ 90 %.

Flutter : `nextStep` couvert état par état (fonction pure, donc exhaustif), widget tests
tapant chaque bouton, et les quatre suites d'accessibilité. Couverture ≥ 90 %.

**Piège connu, rencontré quatre fois sur le lot précédent :** faire lire un bloc
fourni à l'échelle de l'app par un widget existant fait tomber en
`ProviderNotFoundException` **tout harnais qui montait ce widget sans ce provider**. Les
tests ciblés du fichier touché passent ; seule la suite complète le révèle — et une
atteinte indirecte (routage d'un code d'erreur, par exemple) échappe même au `grep`.
Après toute modification de ce genre : suite complète, pas de conclusion sur les tests ciblés.

Doublure partagée disponible : `test/helpers/stripe_account_test_doubles.dart`.

## 9. Décisions ouvertes

| Décision | Retenu ici | Alternative |
|---|---|---|
| Stockage de l'adresse | colonnes en clair sur `users`, précédent `pickup_addresses` (V68) | `kyc_schema` chiffré AES-256 — plus prudent, déchiffrement à la lecture |
| Position du parrainage | étape 6, hors jauge | étape 3 — un code se saisit mieux tant que la motivation est haute |
| Expéditeur qui ne voyagera jamais | l'étape Paiements lui est proposée | sortie explicite « je n'ai pas besoin d'être payé » |

Aucune ne bloque le lot 1.

## 10. Références

- Maquettes : artifact publié le 2026-08-22 (jauge, six écrans, récapitulatif, quatre cas limites)
- Migration précédente : `V229__wallet_refund_eligibility.sql`
- Design system : `dony_app/lib/core/design/AGENTS.md`
- Accounts v2 : `2026-08-22-stripe-accounts-v2-design.md`, PR back #217 / front #271

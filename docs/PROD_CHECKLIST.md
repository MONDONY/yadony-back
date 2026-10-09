# Checklist Passage en Production — yadony Backend

> **Dernière mise à jour : 08/10/2026.**
> **Mise en prod prévue le samedi 10/10/2026 : lire d'abord la [section 9](#9-mise-en-prod-du-samedi-10102026--ce-qui-change-depuis-le-0710)**
> (tag d'image à reprendre, migrations jusqu'à **V299**, pool de connexions, contrôles de tenue en charge),
> puis dérouler la section 8 dans l'ordre.
> Pour la mise en production du lot de corrections des 05–07/10/2026, suivre la
> **[section 8](#8-lot-de-corrections-du-05-au-07102026--mise-en-production)** (ordre de déploiement,
> PR incluses, migrations V291–V294, Stripe, Stream, Sentry, contrôles de données, recette).
> Les sections 1 à 7 datent du 06/05/2026. Les parties périmées sont marquées **(historique)**
> et conservées telles quelles.

> Ce document couvre uniquement les **variables d'application et flags métier** à configurer/activer avant de passer en prod.
> Pour l'infrastructure (VPS, Docker, Nginx, SSL, CI/CD), voir `DEPLOYMENT_GUIDE.md`.

---

## 1. Flags métier à activer

Ces flags sont désactivés en dev et en test pour ne pas bloquer le développement. Ils doivent être `true` en prod.

| Flag | Fichier à éditer | Valeur dev | Valeur prod | Effet |
|---|---|---|---|---|
| `yadony.kyc.enforce` | `application.yml` ou variable d'env | `false` | `true` | Bloque la création de bid et d'annonce sans KYC vérifié |
| `yadony.stripe.enforce` | `application.yml` ou variable d'env | `false` | `true` | Bloque la création d'annonce sans compte bancaire Stripe configuré |

**Comment faire :**

Option A — dans le fichier `application.yml` (profil prod) :
```yaml
yadony:
  kyc:
    enforce: true
  stripe:
    enforce: true
```

Option B — via variable d'environnement dans le `.env` du serveur (recommandé) :
```bash
YADONY_KYC_ENFORCE=true
YADONY_STRIPE_ENFORCE=true
```

---

## 2. Secrets à générer et configurer

### `INTERNAL_SHARED_SECRET`

Utilisé pour sécuriser l'endpoint `/internal/messaging/notify` appelé par les Firebase Functions.

- En dev : valeur par défaut `local-dev-secret-change-me` (non sécurisé)
- En prod : **obligatoirement une valeur aléatoire forte**

```bash
# Générer la valeur
openssl rand -hex 32
# Exemple: a3f9d2e1b4c7...

# L'ajouter dans le .env du serveur
INTERNAL_SHARED_SECRET=<valeur générée>
```

La **même valeur** doit être configurée dans tes Firebase Functions (variable d'environnement `INTERNAL_SHARED_SECRET`) pour que les appels soient autorisés.

### `STRIPE_SECRET_KEY`

- Dev : clé test Stripe (`sk_test_...`)
- Prod : clé live Stripe (`sk_live_...`) — Stripe Dashboard → Developers → API Keys

### `STRIPE_WEBHOOK_SECRET` (historique)

> **(historique)** Le workflow prod écrit aujourd'hui trois secrets distincts : `STRIPE_WEBHOOK_PAYMENTS_SECRET`,
> `STRIPE_WEBHOOK_KYC_SECRET`, `STRIPE_WEBHOOK_BILLING_SECRET` (voir section 8.D).

- Dev : secret du webhook de test
- Prod : secret du webhook live — Stripe Dashboard → Webhooks → ton endpoint → Signing secret

### `STRIPE_WEBHOOK_SECRET` pour le KYC (Stripe Identity)

Stripe Identity utilise un webhook séparé. Vérifier que `stripe.webhook-secret` dans ta config correspond au secret du webhook Identity en prod.

---

## 3. Webhooks Stripe à déclarer en prod (historique — liste incomplète)

> **(historique)** Cette liste ne compte que 4 événements paiements. La liste réelle traitée par le code
> (21 événements paiements, 3 KYC, 5 abonnement PRO) est en [section 8.D](#8d-stripe-live).

Aller dans Stripe Dashboard → Developers → Webhooks → **Add endpoint**.

### Webhook paiements

- **URL :** `https://api.tondomaine.com/api/v1/payments/webhook`
- **Événements à écouter :**
  - `payment_intent.succeeded`
  - `payment_intent.payment_failed`
  - `account.updated`
  - `charge.updated`

### Webhook KYC (Stripe Identity)

- **URL :** `https://api.tondomaine.com/api/v1/kyc/webhook`
- **Événements à écouter :**
  - `identity.verification_session.verified`
  - `identity.verification_session.requires_input`
  - `identity.verification_session.canceled`

---

## 4. Migrations Flyway à appliquer (historique)

> **(historique)** V46–V49 sont appliquées depuis longtemps. Pour les migrations du lot actuel
> (V291–V294, et V263–V290 si la prod ne les a pas encore), voir [section 8.C](#8c-migrations-flyway).

Ces migrations sont sur la branche `security/fix-and-idempotency-review` et doivent être appliquées avant le premier démarrage en prod.

| Migration | Ce qu'elle crée/modifie | Risque |
|---|---|---|
| `V46__kyc_cleanup.sql` | Aligne les statuts KYC, supprime colonnes mortes | Faible — données de dev uniquement |
| `V47__add_kyc_status_not_started.sql` | Nouveau statut `NOT_STARTED`, reclassifie les PENDING sans session | Faible — lecture seule sur les utilisateurs existants |
| `V48__idempotency_constraints.sql` | Contraintes UNIQUE, table `processed_stripe_events`, colonne `payments.captured_at` | Moyen — `ADD CONSTRAINT` peut échouer si doublons existants en prod |
| `V49__add_user_version.sql` | Colonne `version` sur `users` | Faible — `ADD COLUMN` avec valeur par défaut |

**Point d'attention sur V48 :** Avant de déployer, vérifier qu'il n'y a pas de doublons sur `users.stripe_account_id` en prod :
```sql
SELECT stripe_account_id, COUNT(*)
FROM users
WHERE stripe_account_id IS NOT NULL
GROUP BY stripe_account_id
HAVING COUNT(*) > 1;
```
Si des doublons existent, les nettoyer avant le déploiement.

---

## 5. Variables Firebase

| Variable | Valeur prod |
|---|---|
| `firebase.service-account-path` | Chemin vers le JSON service account Firebase prod |
| Firebase Project ID | Projet Firebase prod (pas le projet de dev) |

Vérifier que le projet Firebase en prod a bien **Phone Authentication** activé dans Authentication → Sign-in method.

---

## 6. Vérifications sécurité avant mise en ligne

- [ ] `yadony.kyc.enforce=true` — aucun bid/annonce sans KYC
- [ ] `yadony.stripe.enforce=true` — aucune annonce sans compte bancaire configuré
- [ ] `INTERNAL_SHARED_SECRET` généré avec `openssl rand -hex 32` et configuré dans les Firebase Functions
- [ ] Clés Stripe live (`sk_live_...`) en place — pas de clés test en prod
- [ ] Webhooks Stripe (paiements + KYC) déclarés sur l'URL de prod avec les bons events
- [ ] *(historique — voir 8.D pour les trois secrets actuels)* Signature des webhooks validée (variable `STRIPE_WEBHOOK_SECRET` correcte)
- [ ] Firebase service account du projet prod (pas du projet dev)
- [ ] *(historique)* Migrations V46 → V49 appliquées sans erreur
- [ ] *(historique)* Pas de doublons sur `users.stripe_account_id` (vérification V48)
- [ ] Sentry DSN prod configuré (`sentry.dsn`)
- [ ] `./mvnw test` → 0 rouge avant de déployer
- [ ] **Vérifier les capacités Stripe Connect des premiers voyageurs** — dans Stripe Dashboard → Comptes connectés → compte du voyageur → section "Capacités", confirmer que `card_payments` ET `transfers` sont bien `Actif`. Si `card_payments` est inactif, le paiement échoue avec l'erreur "on_behalf_of sans card_payments". En mode live, l'onboarding complet (vrai RIB + identité) active ces capacités automatiquement — mais vérifier pour les premiers comptes.

---

## 7. Test de fumée après déploiement

Vérifier ces endpoints dans l'ordre après le premier démarrage en prod :

```bash
BASE=https://api.tondomaine.com/api/v1

# 1. Santé de l'application
curl $BASE/actuator/health

# 2. Créer une annonce sans KYC → doit retourner 403 kyc-not-verified
curl -X POST $BASE/announcements \
  -H "Authorization: Bearer <token-sans-kyc>" \
  -H "Content-Type: application/json" \
  -d '{...}'

# 3. Créer une annonce avec KYC mais sans Stripe → doit retourner 403 stripe-onboarding-incomplete
curl -X POST $BASE/announcements \
  -H "Authorization: Bearer <token-kyc-ok-stripe-nok>" \
  -H "Content-Type: application/json" \
  -d '{...}'

# 4. Rejouer un webhook Stripe (depuis le Dashboard → Send test event) → doit être ignoré silencieusement
```

---

**Mise à jour des sections 1 à 7 (historique) :** 2026-05-06
**Branche de référence (historique) :** `security/fix-and-idempotency-review`


---

## 8. Lot de corrections du 05 au 07/10/2026 — mise en production

> Rédigée le 07/10/2026 pour le propriétaire. Chaque point a été vérifié dans le code de
> `origin/main` (back, app, dony-functions) le 07/10/2026. Ce qui n'a pas pu l'être est écrit
> **« à vérifier »**. Les identifiants sont tronqués à 8 caractères. Aucun secret ici : seulement
> des noms de variables.

### 8.0 Ce qu'il faut savoir avant de commencer

- Le dernier déploiement prod réussi date du **20/09/2026 à 01:37 UTC** (workflow « Deploy Production »,
  run lancé quand `main` était au commit `dda67f58`, PR back #316). Le workflow promeut une image
  par son tag (par défaut `staging`) : le contenu exact de l'image alors déployée est **à vérifier**.
- Conséquence : la prod n'a sans doute pas seulement à recevoir les PR #400 à #423. Elle reçoit
  **tout ce qui a été fusionné depuis #317** (107 PR back) et les migrations **V263 à V294**.
  Le premier contrôle ci-dessous (8.C, étape 1) dit où en est vraiment la base prod.
- La staging tourne déjà avec tout le lot : sa base est en **V294** depuis le 07/10/2026.

### 8.A Ordre de mise en production (impératif)

Ordre : **1) règles Firestore → 2) back → 3) app iOS et Android.** Ne pas inverser.

#### Étape 1 — Règles Firestore (dony-functions #6, et #4 si pas encore déployée)

Ce que fait #6 : une liste blanche des clés qu'un message client peut contenir
(`senderId`, `body`, `imageUrl`, `type`, `sentAt`, `readAt`, `latitude`, `longitude`, `replyToId`)
et la validation de `replyToId` (id du message cité, 1 à 40 caractères alphanumériques).
Avant #6, un client pouvait ajouter n'importe quel champ à un message et contourner l'anti-contournement.
#4 (fusionnée le 01/10) ajoute la sourdine de messagerie et le verrouillage des conversations.
Un déploiement de règles envoie le fichier entier : déployer depuis `origin/main` envoie #4 et #6 ensemble.

- [ ] **Savoir quel projet Firebase sert l'app de prod.** Le dépôt dit `yadony-f1f0f` partout
      (`.firebaserc` : alias `default` et `staging` = `yadony-f1f0f`, aucun alias `prod`). Le fichier
      `.env.yadony-f1f0f` indique que ce projet sert toutes les variantes de l'app.
      **À vérifier** : il existe aussi, sur le poste, un fichier non suivi `dony-functions/.env.yadony-prod`
      et un `.firebaserc` modifié localement. Ils peuvent désigner un autre projet. Contrôle sûr, côté app :
      la valeur `FIREBASE_PROJECT_ID` de `dony_app/env.prod.json` (l'app lit son projet Firebase dans
      cette variable, `lib/core/firebase/firebase_options.dart`). Lire uniquement cette clé :
      ```bash
      cd ~/Desktop/dony/dony_app && jq -r .FIREBASE_PROJECT_ID env.prod.json
      ```
- [ ] Mettre `dony-functions` à jour. **Attention** : la copie locale de `main` est en retard
      (commit `5612b7c`, avant #4, #5 et #6). Déployer depuis elle remettrait d'anciennes règles en prod.
      ```bash
      cd ~/Desktop/dony/dony-functions
      git status                      # .firebaserc modifié et .env.yadony-prod non suivi : ne pas les committer
      git switch main && git pull --ff-only
      git log --oneline -1            # doit afficher 9219150 … (#6)
      ```
- [ ] (Conseillé) Lancer les 30 tests des règles (émulateur Firestore, Java requis) :
      ```bash
      npm ci && npm run test:rules
      ```
- [ ] Déployer les règles sur le projet de prod (remplacer `<projet>` par la valeur trouvée plus haut) :
      ```bash
      npx firebase deploy --only firestore:rules --project yadony-prod   # mis à jour le 09/10, voir 9.11
      ```
- [ ] Vérifier dans la console Firebase → Firestore → Règles que la version publiée contient
      `hasOnlyClientMessageKeys` et `isValidReplyTo`.
- [ ] **À vérifier** : la fonction `onNewMessage` modifiée par #5 (badge de non-lus des deux participants,
      fusionnée le 26/09) a-t-elle été déployée sur le projet de prod ? Sinon :
      `npx firebase deploy --only functions:onNewMessage` (après accord, même projet).

#### Étape 2 — Back (workflow `deploy-prod.yml`)

Le workflow ne se lance qu'à la main (`workflow_dispatch`). Il ne construit rien : il promeut une image
déjà sur ghcr.io (`ghcr.io/mondony/yadony-back:<tag>`), réécrit le `.env` du serveur depuis les secrets
et variables GitHub de l'environnement `production`, redémarre, puis attend `actuator/health = UP` (60 s max).

- [ ] Prendre le tag exact de l'image validée en staging, pas `staging` qui bouge à chaque déploiement.
      Le dernier déploiement staging (07/10/2026, 12:49 UTC) a construit `19ed17e4` = `main` avec
      #421, #422 et #423 : tag attendu **`sha-19ed17e`**, à promouvoir une fois la recette 8.H validée.
      **Périmé au 08/10 : `main` a reçu #425 à #439. Prendre le tag indiqué en [9.2](#92-tag-dimage-à-promouvoir).**
      Si `main` a bougé entre-temps, redéployer la staging et recetter avant. Contrôle :
      ```bash
      gh run list -R MONDONY/yadony-back --workflow deploy-staging.yml --limit 3
      # tag = sha-<7 premiers caractères du commit déployé>
      ```
- [ ] Faire une sauvegarde de la base prod juste avant (les sauvegardes quotidiennes du conteneur
      `yadony_db_backup` sont dans `~/yadony/backups/` ; en faire une à la main en plus) :
      ```bash
      ssh <utilisateur>@<hôte-prod> 'docker exec yadony_db_prod sh -lc "pg_dump -U \$POSTGRES_USER -d \$POSTGRES_DB -Fc" > ~/yadony/backups/avant-lot-0710.dump'
      ```
- [ ] Lancer le déploiement :
      ```bash
      gh workflow run deploy-prod.yml -R MONDONY/yadony-back -f image_tag=sha-<court>
      gh run watch -R MONDONY/yadony-back $(gh run list -R MONDONY/yadony-back --workflow deploy-prod.yml --limit 1 --json databaseId -q '.[0].databaseId')
      ```
      (Équivalent : GitHub → Actions → « Deploy Production » → Run workflow → `image_tag`.)
- [ ] Après le déploiement : relancer les contrôles de données (8.G) et vérifier la version Flyway (8.C).

#### Étape 3 — App iOS et Android

- [ ] Build prod (`--dart-define-from-file=env.prod.json`) depuis `dony_app` `main` à jour,
      soumission App Store et Play Store **après** que le back soit en prod et vérifié.
      La version dans `pubspec.yaml` sur `main` est `1.0.0+83` : **à vérifier** (numéro à monter selon le dernier build publié).

#### Pourquoi cet ordre

- **Règles avant app.** Le README de dony-functions pose la règle : toute nouvelle clé écrite par l'app
  doit être dans la liste blanche et déployée **avant** la version de l'app qui l'écrit, sinon chaque
  envoi est refusé (`permission-denied`). L'app du lot (FLUTTER-86, réponse citée) écrit `replyToId`.
  La liste blanche couvre toutes les versions publiées de l'app : la déployer d'abord ne casse rien.
  À noter : les règles actuellement en prod (sans liste blanche) acceptent n'importe quelle clé, donc
  l'ordre inverse ne bloquerait pas les messages, mais il laisserait le trou ouvert plus longtemps.
- **Back avant app.** La nouvelle app lit des champs que seul le nouveau back envoie :
  `contactWindowOpen` (bouton téléphone, DK), `recipientDeclined` (E8), `notificationsMuted` (CM),
  `archived` (EJ), l'erreur `trip-not-departed` (CB). Vérifié dans le code de l'app : ces champs sont
  optionnels ou ont une valeur par défaut (`bool?`, `?? false`, `== true`), donc la nouvelle app
  **tolère l'ancien back** sans planter. Mais les nouvelles actions (sourdine, archivage d'une
  discussion de prix, demande de remplacement du destinataire) appellent des routes qui n'existent
  pas sur l'ancien back : elles échoueraient. D'où back d'abord.

### 8.B Ce qui part en production

#### PR back (MONDONY/yadony-back) — lot du 05 au 07/10

| PR | Titre (raccourci) | Impact |
|---|---|---|
| #400 | 3 trous de cohérence de l'argent + libération forcée bornée | Corrige des écarts d'argent ; la libération forcée admin est encadrée. |
| #401 | Sonde de cohérence de l'argent (17 règles, toutes les 15 min) | Nouvelles alertes admin (INV-xx) en prod : s'attendre à des alertes sur l'historique. |
| #402 | Commission espèces dans la devise choisie + email Stripe relu hors cache | Commission prélevée dans la bonne devise. |
| #403 | Le destinataire confirmé note le voyageur (FLUTTER-CA) | Nouvelle note possible après livraison. |
| #404 | Coupure de messagerie exposée dans `/auth/me` (FLUTTER-CT/CV) | L'app affiche « Messagerie suspendue ». |
| #405 | Trajets ouverts à la carte après onboarding Stripe, compte mobile money payeur | Moyens de paiement d'un trajet mis à jour après onboarding. |
| #406 | Recherche de villes par nom de pays | Recherche plus large. |
| #407 | Alertes admin : phrase de l'incident, vraie sévérité | Back-office plus lisible (V290). |
| #408 | Séquestre du paiement de négociation dès le checkout (INV-08) | Paiement de négociation carte passé en séquestre sans attendre le webhook ; alerte si livré sans séquestre. |
| #409 | Transactions admin : contexte, recherche, totaux, export CSV, chronologie | Back-office. |
| #410 | CI : ignore CVE-2026-47884 | Voir 8.I. |
| #411 | Journalise le refus Connect « pays non pris en charge » (FLUTTER-DM) | Diagnostic ; voir 8.D. |
| #412 | Destinataire qui refuse : masqué pour le voyageur, demande de remplacement (FLUTTER-E8) | Nouveau parcours. |
| #413 | Code promo utilisé en espèces enregistré, limites revérifiées sous verrou | Fin des remises illimitées en espèces. |
| #414 | Expose `contactWindowOpen` (FLUTTER-DK) | Bouton téléphone de la fiche jusqu'à J+3 après livraison. |
| #415 | Le cron no-show ne déclare plus le voyageur absent si l'expéditeur a été signalé absent | Évite une pénalité et un remboursement à tort. |
| #416 | Poids max 32 kg en base (V291), compteur d'offres, alertes admin dédoublonnées | Fin des 500 entre 30 et 32 kg ; une seule issue Sentry par alerte. |
| #417 | Grille tarifaire convertie dans la devise de l'annonce (FLUTTER-ER) ; « Report accepté » (EK) | Fin des grilles EUR recopiées telles quelles en F CFA (10 € → 10 F CFA). |
| #418 | Rembourse les bids issus d'une négociation | Remboursement carte / mobile money et commission espèces rendue sur un envoi négocié terminé sans livraison. |
| #419 | Refuse la confirmation de livraison avant le départ du trajet (FLUTTER-CB) | 422 `trip-not-departed` ; plus de séquestre libéré avant le transport. |
| #420 | Flag dédié pour le SMS de repli des notifications critiques | Nouveau réglage `CRITICAL_SMS_FALLBACK_ENABLED`, **coupé par défaut** (back-office). Les SMS OTP ne changent pas. |
| #421 | Rendre le code promo quand l'envoi se termine sans livraison (V292) | Le client récupère son code. |
| #422 | Mettre une conversation en sourdine (FLUTTER-CM, V294) | Nouvelle route. |
| #423 | Archiver ou supprimer une discussion de prix terminée (FLUTTER-EJ, V293) | Nouvelle route. |

**Aussi inclus, si la prod est bien restée au 20/09 :** les PR back **#317 à #399** (lots destinataire,
appels Stream, reports de trajet, modération admin, activation après KYC, etc.). Liste complète :
`gh pr list -R MONDONY/yadony-back --state merged --limit 120 --json number,title,mergedAt`.
Elles ont été recettées en staging au fil de l'eau, mais jamais vues en prod.

#### PR app (MONDONY/dony_app) — depuis #513

| PR | Titre (raccourci) |
|---|---|
| #513 | Retours testeurs (B0/B1, B6, B7, BA/BB, BD) |
| #514 | Le QR ou le numéro suffit à identifier le colis (BC) ; demande acceptée à jour (B9) |
| #515 | Trajets déjà sollicités ou complets grisés mais cliquables (BG) |
| #516 | Retours testeurs (BR, BQ, BN/BF, BJ, BK) |
| #517 | Moins d'appels API au retour au premier plan et aux pushs |
| #518 | Carte « Lieux » de remise et de récupération |
| #519 | Retours testeurs (CH, BX, CK/CJ, CG) |
| #520 | Grille : corps `{orderedIds}` au réordonnancement (STAGING-H) |
| #521 | Suggestions testeurs (CF, BY, C9, CA) |
| #522 | L'appareil photo n'attend plus la localisation (D1) |
| #523 | Bandeau « Messagerie suspendue » (CT/CV) |
| #524 | Carte de trajet débloquée et **Link coupé** dans les feuilles Stripe |
| #525 | Trajet en espèces après activation Stripe, numéro mobile money payeur, « Proposer un prix » |
| #526 | Réduire l'appel, barre d'appel en cours, sonnerie bornée |
| #527 | Clavier de recherche, portefeuille, récupération, remise directe |
| #528 | Identifiant de lecteur unique pour la tonalité d'appel (iPhone) |
| #529 | Bouton « S'abonner » sur tout profil qui n'est pas le mien (DB) |
| #530 | Prévient le voyageur quand le colis exige la carte (E9) |
| #531 | Suggestions testeurs (DX, DC, D8, DQ, DD) |
| #532 | Destinataire qui refuse : demande de remplacement (E8) |
| #533 | Couper le haut-parleur rend le son au casque ou au combiné sur iOS (E1) |
| #534 | Notification quittée, compteur d'offres, fenêtre de date, texte d'absence (EC/ED, EA, E7, E3) |
| #535 | Bouton téléphone selon la fenêtre de contact (DK) |
| #536 | Envoi au kilo sur grille mixte, profil expéditeur, alerte rechargée (ET, EB, EP, ES) |
| #537 | Aperçu de grille converti dans la devise du trajet (ER) |
| #538 | Confirmation de livraison verrouillée avant le départ (CB) |
| #539 | Répondre à un message précis (citation, FLUTTER-86) — écrit `replyToId` |
| #540 | Petites améliorations de recette (EV, CD, EG, CY, EQ, CW) |
| #541 | Mettre une conversation en sourdine (CM) |
| #542 | Archiver ou supprimer une discussion de prix terminée (EJ) |

La dernière version de l'app publiée en prod, et donc le point de départ exact, est **à vérifier**.

#### dony-functions

| PR | Fusion | Contenu | Déploiement |
|---|---|---|---|
| #4 | 01/10 | Règles : sourdine de messagerie, verrouillage des conversations | Règles (étape 1) |
| #5 | 26/09 | `onNewMessage` : badge de non-lus des deux participants | Fonction — **à vérifier** si déjà en prod |
| #6 | 07/10 | Règles : liste blanche des clés + `replyToId` (30 tests) | Règles (étape 1) |

### 8.C Migrations Flyway

Flyway applique les migrations tout seul au démarrage du back. Une migration qui échoue empêche
le démarrage : le health check du workflow échoue alors après 60 s.

- [ ] **Étape 1 — savoir où en est la prod** (avant le déploiement) :
      ```sql
      BEGIN READ ONLY;
      SELECT version, description, installed_on, success
      FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
      ROLLBACK;
      ```
      Commande pour l'exécuter sur le serveur prod :
      ```bash
      ssh <utilisateur>@<hôte-prod> 'docker exec -i yadony_db_prod sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB"' <<'SQL'
      BEGIN READ ONLY;
      SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
      ROLLBACK;
      SQL
      ```
      Attendu si la prod est restée au 20/09 : dernière version **262**. Toute ligne `success = f` : s'arrêter.
- [ ] **Étape 2 — après le déploiement** : même requête, dernière version attendue **294**, toutes `success = t`.
      **Au 08/10 : dernière version attendue 299** (V295, V298, V299 en plus, voir [9.3](#93-migrations-flyway-v295-à-v299)).

#### Les quatre migrations du lot

| Migration | Ce qu'elle fait | Risque | Contrôle préalable |
|---|---|---|---|
| `V291__package_requests_weight_max_32` | Remplace la contrainte `chk_pkg_req_weight` : poids d'une demande d'envoi entre 0,5 et **32** kg (au lieu de 30). Aligne la base sur l'API et l'app. | Très faible. La contrainte s'élargit : aucune ligne existante ne peut devenir invalide. Relecture de la table pendant l'ALTER (petite table). | Aucun nécessaire. |
| `V292__promo_redemptions_release` | Ajoute `released_at` et `release_reason` à `promo_redemptions` + 2 index partiels. Un rachat n'est jamais supprimé : il est marqué « libéré ». | Faible. Colonnes nullables ; index créés sur une petite table. | Aucun. |
| `V293__negotiations_user_archive_hide` | Ajoute 4 colonnes nullables (archivé / retiré, par partie) sur `negotiation_threads` et 4 sur `bids` (`negotiation_*_archived_at`, `negotiation_*_hidden_at`). | Faible. Colonnes nullables sans défaut : instantané en PostgreSQL 16. | Aucun. |
| `V294__conversation_notifications_mute` | Ajoute `sender_notifications_muted_at` et `traveler_notifications_muted_at` à `conversations`. | Faible. Colonnes nullables, pas d'index. | Aucun. |

Toutes les quatre sont passées sans erreur en staging le 07/10/2026.

#### Si la prod est encore en V262 : V263 à V290 partent aussi

28 migrations de plus, toutes passées en staging. Celles qui **modifient des données existantes** ou
**posent une contrainte** méritent un œil sur les logs de démarrage :
V263, V264, V265 (FAQ anglaise), V266 (types wallet `ADMIN_CREDIT`/`ADMIN_DEBIT`), V267, V268,
V269, V270 (gel des versements), V273, V274, V276, V277, V278 (reports de trajet), V282, V283,
V284, V285 (appels, `bids.delivered_at`), V287 (activation après KYC), V288 (accents FAQ),
V290 (alertes admin). Le contenu de chaque fichier est commenté en tête
(`src/main/resources/db/migration/`). La sauvegarde de l'étape 2 (8.A) est le filet.

### 8.D Stripe (live)

Le code traite trois webhooks Stripe distincts (liste relevée dans le code, 07/10/2026).

- [ ] **Paiements & Connect** — `https://api.yadony.com/api/v1/payments/webhook`, type
      **« Compte + Événements Connect »**, secret → `STRIPE_WEBHOOK_PAYMENTS_SECRET`.
      21 événements (`PaymentStripeWebhookHandler`) :
      `account.updated`, `account.application.deauthorized`, `capability.updated`,
      `charge.dispute.created`, `charge.dispute.closed`, `charge.dispute.funds_withdrawn`,
      `charge.dispute.funds_reinstated`, `charge.refunded`, `charge.refund.updated`,
      `payment_intent.amount_capturable_updated`, `payment_intent.canceled`,
      `payment_intent.payment_failed`, `payment_intent.succeeded`, `payment_method.detached`,
      `payout.failed`, `payout.paid`, `radar.early_fraud_warning.created`, `setup_intent.succeeded`,
      `transfer.created`, `transfer.reversed`, `transfer.updated`.
      **Point clé** : `payment_intent.amount_capturable_updated` fait passer un paiement carte en
      séquestre. D'après #408, la staging ne l'a pas reçu pendant des semaines (cause de INV-08) : vérifier qu'il est bien coché en live.
      **Mis à jour le 09/10, voir 9.13** : un endpoint ne peut pas recevoir à la fois les événements du compte et ceux des comptes
      connectés. Cocher les 16 événements du compte listés en 9.13 ; les 5 événements Connect attendent un changement back.
- [ ] **KYC Stripe Identity** — `https://api.yadony.com/api/v1/kyc/webhook`, type « Compte »,
      secret → `STRIPE_WEBHOOK_KYC_SECRET`. 3 événements : `identity.verification_session.verified`,
      `identity.verification_session.requires_input`, `identity.verification_session.canceled`.
- [ ] **Abonnement PRO** — `https://api.yadony.com/api/v1/billing/webhook`, secret →
      `STRIPE_WEBHOOK_BILLING_SECRET`. 5 événements (`ProBillingStripeWebhookHandler`) :
      `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`,
      `customer.subscription.updated`, `customer.subscription.deleted`.
- [ ] Après déploiement, envoyer un événement de test depuis le dashboard et vérifier qu'il arrive :
      `SELECT event_type, status, received_at FROM stripe_event_inbox ORDER BY received_at DESC LIMIT 5;`
      (requête reprise de `docs/stripe-production-checklist.md`, qui détaille aussi le reste de la config live).
- [ ] **Link désactivé dans le dashboard live** (FLUTTER-D5/CR) : Paramètres → Moyens de paiement →
      Link → désactivé. L'app #524 coupe Link dans ses feuilles de paiement ; le dashboard doit suivre.
      Faire la même chose sur la configuration de moyens de paiement des comptes connectés si elle existe.
- [ ] **Pays Connect** (FLUTTER-DM) : #411 journalise le refus Stripe « pays non pris en charge » à la
      création d'un compte connecté. Vérifier dans le dashboard live (Connect → Paramètres → pays)
      que les pays de résidence des voyageurs visés sont ouverts. La liste à autoriser est **à décider**
      (l'ancienne checklist Stripe cite FR, SN, CI, ML, CM, GN, BJ, TG, NE : non vérifié dans le code).

### 8.E Appels audio (Stream Video)

- [ ] **Bloquant si on veut les appels en prod.** Le workflow `deploy-prod.yml` **n'écrit pas**
      `CALLS_ENABLED`, `STREAM_API_KEY` ni `STREAM_API_SECRET` dans le `.env` du serveur (le workflow
      staging, lui, les écrit). Comme le `.env` prod est réécrit à chaque déploiement, une valeur posée à
      la main sur le serveur serait effacée. Résultat : appels **coupés** en prod (défaut `CALLS_ENABLED=false`).
      Pour les ouvrir : une PR qui ajoute ces trois lignes au workflow prod, puis dans GitHub →
      Settings → Environments → `production` : variable `STREAM_API_KEY`, secret `STREAM_API_SECRET`,
      variable `CALLS_ENABLED=true`. Utiliser une application Stream **de production**, pas celle de staging.
- [ ] Webhook Stream → `https://api.yadony.com/api/v1/calls/webhook` dans le dashboard Stream de prod
      (contrôle de signature avec la clé de l'application).
- [ ] Type d'appel `audio_call` (valeur `yadony.calls.call-type` du back) : dans le dashboard Stream,
      réglages audio du type → sortie par défaut **combiné** (`default_device: earpiece`) et
      **haut-parleur coupé** (`speaker_default_on: false`). Ce réglage vit chez Stream, pas dans le code :
      **à vérifier** dans le dashboard (et reproduire celui de staging).
- [ ] Fenêtre d'appel après livraison : `yadony.calls.delivery-grace-days: 3` (dans `application.yml`,
      pas de variable d'environnement) : le bouton téléphone reste disponible jusqu'à J+3 après la livraison.

Le lot ajoute aussi en prod (si la prod est restée au 20/09) les variables Grafana Cloud
(`GRAFANA_*`) et `SENTRY_WEBHOOK_SECRET`, écrites par le workflow staging mais **absentes du workflow prod**.

### 8.F Sentry

- [ ] Après déploiement, déclencher ou attendre une alerte admin. Vérifier dans Sentry qu'elle donne
      **une seule issue par code** (empreinte `["admin-alert", <code>]`, `AdminAlertService`) et plus de
      doublon venant du log `ERROR` (filtré par `AdminAlertSentryFilter`, #416).
- [ ] Webhook Sentry → back-office (`https://api.yadony.com/api/v1/admin/sentry-webhook`) :
      une issue dont le titre commence par `[ADMIN ALERT]` ne doit **pas** revenir en écho dans les alertes admin.
      **Attention** : le workflow prod n'écrit pas `SENTRY_WEBHOOK_SECRET`. Sans secret, la route refuse tout
      (401). En prod ce webhook est donc **inactif** tant qu'on ne l'ajoute pas au workflow (même remarque qu'en 8.E).

### 8.G Contrôles de données en prod (lecture seule)

À lancer **avant** le déploiement (état des lieux) et **après** (les mêmes anomalies ne doivent plus
apparaître pour les nouveaux envois). Toutes les requêtes sont en lecture seule : elles commencent par
`BEGIN READ ONLY;` et finissent par `ROLLBACK;`. **Aucune correction en SQL** : chaque anomalie se
traite depuis le back-office ou le dashboard Stripe.

Commande type (coller une requête entre les deux `SQL`) :
```bash
ssh <utilisateur>@<hôte-prod> 'docker exec -i yadony_db_prod sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -A -F \" | \""' <<'SQL'
BEGIN READ ONLY;
-- requête ici
ROLLBACK;
SQL
```

Les sept requêtes ont été exécutées sans erreur sur la base staging (V294) le 07/10/2026.

#### G1 — Commission espèces d'un envoi négocié annulé, prélevée et jamais rendue

Cherche : bid espèces issu d'une négociation, terminé sans livraison (annulé, refusé, expiré), dont la
commission (portée par le fil, `negotiation_threads.commission_status = 'CHARGED'`) n'a jamais été
recréditée au voyageur. Corrigé pour l'avenir par #418, **sans rattrapage du passé**.

```sql
BEGIN READ ONLY;
SELECT left(b.id::text, 8) AS bid, left(t.id::text, 8) AS fil, b.status AS statut_bid,
       t.commission_charged_via AS preleve_via,
       (SELECT -SUM(w.amount) FROM wallet_transactions w
         WHERE w.type = 'COMMISSION_DEDUCTED' AND w.payment_ref = t.id::text) AS commission_debitee,
       (SELECT MIN(w.currency) FROM wallet_transactions w
         WHERE w.type = 'COMMISSION_DEDUCTED' AND w.payment_ref = t.id::text) AS devise_portefeuille,
       b.updated_at::date AS maj
FROM bids b
JOIN negotiation_threads t ON t.id = b.linked_negotiation_thread_id
WHERE b.deleted_at IS NULL
  AND b.payment_method = 'CASH'
  AND b.status IN ('CANCELLED', 'REJECTED', 'EXPIRED')
  AND t.commission_status = 'CHARGED'
  AND NOT EXISTS (SELECT 1 FROM wallet_transactions r
                   WHERE r.type IN ('REFUND', 'ADMIN_CREDIT')
                     AND (r.idempotency_key = 'wallet-refund-cancel-' || b.id
                          OR r.bid_id = b.id))
ORDER BY b.updated_at;
ROLLBACK;
```

Si des lignes sortent : back-office → utilisateur (le voyageur de l'annonce) → portefeuille →
**ajustement (crédit)** du montant `commission_debitee` dans `devise_portefeuille`, motif
« commission négociation non rendue, bid xxxxxxxx ». Prélèvement par carte (`preleve_via = CARD`) :
rembourser le PaymentIntent de commission depuis le dashboard Stripe. Un crédit d'ajustement n'est pas
rattaché au bid : la ligne restera listée, noter les bids traités.

Staging : **5 lignes** — `89e8281c`, `1829689d`, `f6e8a9ec`, `98b69704`, `63f547c3` (tous `CANCELLED`, via `WALLET`).

#### G2 — Paiement d'un fil de négociation resté en attente ou en séquestre, envoi terminé sans livraison

Cherche : paiement rattaché au fil (`payments.bid_id` nul) encore `PENDING` ou `ESCROW` alors que le bid
est annulé, refusé, expiré ou en no-show. L'expéditeur n'a pas été remboursé (corrigé pour l'avenir par #418).

```sql
BEGIN READ ONLY;
SELECT left(p.id::text, 8) AS paiement, left(b.id::text, 8) AS bid, b.status AS statut_bid,
       p.status AS statut_paiement, p.rail, p.amount, p.currency, p.created_at::date AS cree
FROM payments p
JOIN bids b ON b.linked_negotiation_thread_id = p.negotiation_thread_id
WHERE p.bid_id IS NULL
  AND p.deleted_at IS NULL
  AND p.status IN ('PENDING', 'ESCROW')
  AND b.status IN ('CANCELLED', 'REJECTED', 'EXPIRED', 'NO_SHOW')
ORDER BY p.created_at;
ROLLBACK;
```

Si des lignes sortent : back-office → Transactions → paiement. `ESCROW` : **Rembourser**.
`PENDING` : ouvrir le PaymentIntent dans le dashboard Stripe ; s'il est encore « à capturer », l'annuler ;
s'il a expiré, rien n'a été débité. Mobile money (`rail = PAWAPAY`) : remboursement depuis la fiche paiement.

Staging : **1 ligne** — paiement `248d38b6`, bid `8b862f47` (`NO_SHOW`, `PENDING`, carte).

#### G3 — Annonce en F CFA dont la grille n'a pas été convertie

Cherche : annonce XOF/XAF dont un prix de grille est sous 100 (signe d'un prix EUR recopié tel quel,
FLUTTER-ER, corrigé par #417 pour les nouvelles annonces), puis les offres prises dessus.

```sql
BEGIN READ ONLY;
SELECT left(a.id::text, 8) AS annonce, a.currency, a.status, a.departure_date,
       (a.deleted_at IS NOT NULL) AS supprimee,
       MIN(g.unit_price_net) AS prix_min_grille, MAX(g.unit_price_net) AS prix_max_grille
FROM announcements a
JOIN announcement_price_grid_items g ON g.announcement_id = a.id
WHERE a.currency IN ('XOF', 'XAF')
GROUP BY a.id, a.currency, a.status, a.departure_date, a.deleted_at
HAVING MIN(g.unit_price_net) < 100
ORDER BY a.departure_date;
ROLLBACK;
```

```sql
BEGIN READ ONLY;
SELECT left(b.id::text, 8) AS bid, left(b.announcement_id::text, 8) AS annonce, b.status,
       b.payment_method, b.currency, gi.label_snapshot, gi.unit_price_net_snapshot, gi.quantity
FROM bids b
JOIN announcements a ON a.id = b.announcement_id
JOIN bid_grid_items gi ON gi.bid_id = b.id
WHERE b.deleted_at IS NULL
  AND a.currency IN ('XOF', 'XAF')
  AND gi.unit_price_net_snapshot < 100
ORDER BY b.created_at;
ROLLBACK;
```

Si des lignes sortent : annonce encore active → prévenir le voyageur qu'il doit corriger sa grille
(ou retirer l'annonce depuis la modération du back-office). Offre en cours au mauvais prix → contacter
les deux parties ; annuler si besoin (remboursement automatique). Annonce supprimée ou terminée : rien à faire.

Staging : **2 annonces** — `7128b339` (`CANCELLED`) et `ce2102c6` (`ACTIVE` mais supprimée), grille 8 à 13 XOF ;
**1 offre** — `7f9e0b4a` sur `7128b339` (`CANCELLED`, mobile money, 2 lignes de grille).

#### G4 — Code promo utilisé en espèces jamais enregistré

Cherche : bid espèces avec un `promo_code` saisi mais ni `promo_code_id` ni ligne dans `promo_redemptions`
(bug corrigé par #413 : remise illimitée, limites jamais comptées).

```sql
BEGIN READ ONLY;
SELECT left(b.id::text, 8) AS bid, b.promo_code, b.status, b.commission_status,
       b.commission_rate, b.created_at::date AS cree
FROM bids b
WHERE b.deleted_at IS NULL
  AND b.payment_method = 'CASH'
  AND b.promo_code IS NOT NULL
  AND b.promo_code_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM promo_redemptions pr WHERE pr.bid_id = b.id)
ORDER BY b.created_at;
ROLLBACK;
```

Si des lignes sortent : pas d'argent à rendre (la remise a profité au voyageur). Vérifier dans
back-office → Codes promo si un même utilisateur a dépassé la limite par utilisateur ; au besoin,
désactiver ou réduire le code. Les lignes `CANCELLED` sans commission sont sans effet.

Staging : **15 lignes** — `7735d95b` (WELCOME10), puis WELCOME05 : `91f06af8`, `9346be7a`, `6478f383`,
`4b138106`, `830e7729`, `42a742eb`, `20471aa9`, `22345cf0`, `2d2d5631`, `24f3bd1c`, `783a4018`,
`386d5974`, `97d10519`, `80e7ebd8`. Dont 4 avec commission prélevée (`7735d95b`, `42a742eb`, `24f3bd1c`, `386d5974`).

#### G5 — Livraison confirmée avant le départ du trajet (FLUTTER-CB)

Cherche : confirmation de livraison (journal `audit_log`, action `DELIVERY_CONFIRMED`) enregistrée avant
l'heure de départ du trajet (date + heure dans le fuseau du trajet, sans heure : le lendemain 00:00,
même règle que `DepartureRules`). Pour une carte, le séquestre a alors été libéré au voyageur avant le transport.

```sql
BEGIN READ ONLY;
SELECT left(b.id::text, 8) AS bid, b.status, b.payment_method,
       (b.linked_negotiation_thread_id IS NOT NULL) AS negocie,
       al.created_at AS livre_le,
       (a.departure_date + COALESCE(a.departure_time, TIME '00:00')
          + CASE WHEN a.departure_time IS NULL THEN INTERVAL '1 day' ELSE INTERVAL '0' END)
         AT TIME ZONE COALESCE(NULLIF(a.timezone, ''), 'Europe/Paris') AS depart_le
FROM audit_log al
JOIN bids b ON b.id::text = al.payload->>'bidId'
JOIN announcements a ON a.id = b.announcement_id
WHERE al.action = 'DELIVERY_CONFIRMED'
  AND al.created_at < (a.departure_date + COALESCE(a.departure_time, TIME '00:00')
          + CASE WHEN a.departure_time IS NULL THEN INTERVAL '1 day' ELSE INTERVAL '0' END)
         AT TIME ZONE COALESCE(NULLIF(a.timezone, ''), 'Europe/Paris')
ORDER BY al.created_at;
ROLLBACK;
```

Si des lignes sortent : regarder d'abord `payment_method` = `STRIPE` ou `MOBILE_MONEY` (argent versé au
voyageur). Vérifier avec l'expéditeur que le colis est bien arrivé. Sinon : litige depuis le back-office.
Après le déploiement, aucune nouvelle ligne ne doit apparaître (le back répond 422 `trip-not-departed`).

Staging : **64 lignes** (données de recette : les testeurs confirment souvent avant la date) — 33 espèces,
24 carte, 7 mobile money. Exemples : `7735d95b`, `d1781a1e`, `4d13b44b`, `f420cdb9`.

#### G6 — Voyageur déclaré absent par le cron alors que l'expéditeur avait été signalé absent

Cherche : bid passé en `NO_SHOW` (voyageur absent, posé par le cron) alors qu'une annulation
`SENDER_NO_SHOW` (absence de l'expéditeur signalée par le voyageur, à la remise) existe sur ce bid (#415).

```sql
BEGIN READ ONLY;
SELECT left(b.id::text, 8) AS bid, b.status AS statut_bid, b.no_show_at,
       left(c.id::text, 8) AS annulation, c.no_show_status, c.created_at AS signale_le
FROM bids b
JOIN cancellations c ON c.bid_id = b.id
WHERE c.deleted_at IS NULL
  AND c.reason = 'SENDER_NO_SHOW'
  AND c.scope = 'HANDOVER'
  AND b.status = 'NO_SHOW'
ORDER BY c.created_at;
ROLLBACK;
```

Si des lignes sortent : le voyageur a été pénalisé (compteur de no-show +1) et l'expéditeur remboursé
à tort. Back-office → Incidents → No-shows : statuer sur la déclaration ; corriger la réputation du
voyageur et, si besoin, ouvrir un litige pour l'argent.

Staging : **0 ligne** (aucune annulation `SENDER_NO_SHOW` en staging).

#### G7 — Paiement de négociation resté PENDING alors que le colis est livré (INV-08, #408)

Cherche : paiement de fil encore `PENDING` alors que le bid est `COMPLETED` ou a une confirmation de
livraison. Le voyageur n'a pas été payé et l'autorisation carte expire à J+7.

```sql
BEGIN READ ONLY;
SELECT left(p.id::text, 8) AS paiement, left(b.id::text, 8) AS bid, b.status AS statut_bid,
       p.status AS statut_paiement, p.rail, p.amount, p.currency, p.created_at::date AS cree
FROM payments p
JOIN bids b ON b.linked_negotiation_thread_id = p.negotiation_thread_id
WHERE p.bid_id IS NULL
  AND p.deleted_at IS NULL
  AND p.status = 'PENDING'
  AND (b.status = 'COMPLETED'
       OR EXISTS (SELECT 1 FROM audit_log al
                   WHERE al.action = 'DELIVERY_CONFIRMED'
                     AND al.payload->>'bidId' = b.id::text))
ORDER BY p.created_at;
ROLLBACK;
```

Si des lignes sortent : **urgent** (avant J+7 de `cree`). Ouvrir le PaymentIntent dans Stripe.
Encore « à capturer » : demander à un développeur de passer le paiement en séquestre puis de faire
la libération (que la libération forcée admin accepte un paiement `PENDING` est **à vérifier**). Expiré : le voyageur n'a rien
reçu, régularisation manuelle à décider.

Staging : **3 lignes** — paiement `3d2d4746` / bid `d1781a1e` (14,70 USD), `d9f1fa40` / `4d13b44b` (50,00 USD),
`b8fe0fb9` / `f420cdb9` (20,47 USD), tous carte, `COMPLETED`.

#### Toutes les requêtes en une fois

Le fichier complet (G1 à G7 + version Flyway) peut être enregistré sur le poste sous
`controles-lot-0710.sql` puis envoyé d'un coup :
```bash
ssh <utilisateur>@<hôte-prod> 'docker exec -i yadony_db_prod sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -A -F \" | \""' < controles-lot-0710.sql
```

<details>
<summary>Contenu de <code>controles-lot-0710.sql</code></summary>

```sql
BEGIN READ ONLY;
\echo '== Q1'
SELECT left(b.id::text, 8) AS bid, left(t.id::text, 8) AS fil, b.status AS statut_bid,
       t.commission_charged_via AS preleve_via,
       (SELECT -SUM(w.amount) FROM wallet_transactions w
         WHERE w.type = 'COMMISSION_DEDUCTED' AND w.payment_ref = t.id::text) AS commission_debitee,
       (SELECT MIN(w.currency) FROM wallet_transactions w
         WHERE w.type = 'COMMISSION_DEDUCTED' AND w.payment_ref = t.id::text) AS devise_portefeuille,
       b.updated_at::date AS maj
FROM bids b
JOIN negotiation_threads t ON t.id = b.linked_negotiation_thread_id
WHERE b.deleted_at IS NULL
  AND b.payment_method = 'CASH'
  AND b.status IN ('CANCELLED', 'REJECTED', 'EXPIRED')
  AND t.commission_status = 'CHARGED'
  AND NOT EXISTS (SELECT 1 FROM wallet_transactions r
                   WHERE r.type IN ('REFUND', 'ADMIN_CREDIT')
                     AND (r.idempotency_key = 'wallet-refund-cancel-' || b.id
                          OR r.bid_id = b.id))
ORDER BY b.updated_at;
\echo '== Q2'
SELECT left(p.id::text, 8) AS paiement, left(b.id::text, 8) AS bid, b.status AS statut_bid,
       p.status AS statut_paiement, p.rail, p.amount, p.currency, p.created_at::date AS cree
FROM payments p
JOIN bids b ON b.linked_negotiation_thread_id = p.negotiation_thread_id
WHERE p.bid_id IS NULL
  AND p.deleted_at IS NULL
  AND p.status IN ('PENDING', 'ESCROW')
  AND b.status IN ('CANCELLED', 'REJECTED', 'EXPIRED', 'NO_SHOW')
ORDER BY p.created_at;
\echo '== Q3a annonces'
SELECT left(a.id::text, 8) AS annonce, a.currency, a.status, a.departure_date,
       (a.deleted_at IS NOT NULL) AS supprimee,
       MIN(g.unit_price_net) AS prix_min_grille, MAX(g.unit_price_net) AS prix_max_grille
FROM announcements a
JOIN announcement_price_grid_items g ON g.announcement_id = a.id
WHERE a.currency IN ('XOF', 'XAF')
GROUP BY a.id, a.currency, a.status, a.departure_date, a.deleted_at
HAVING MIN(g.unit_price_net) < 100
ORDER BY a.departure_date;
\echo '== Q3b offres'
SELECT left(b.id::text, 8) AS bid, left(b.announcement_id::text, 8) AS annonce, b.status,
       b.payment_method, b.currency, gi.label_snapshot, gi.unit_price_net_snapshot, gi.quantity
FROM bids b
JOIN announcements a ON a.id = b.announcement_id
JOIN bid_grid_items gi ON gi.bid_id = b.id
WHERE b.deleted_at IS NULL
  AND a.currency IN ('XOF', 'XAF')
  AND gi.unit_price_net_snapshot < 100
ORDER BY b.created_at;
\echo '== Q4'
SELECT left(b.id::text, 8) AS bid, b.promo_code, b.status, b.commission_status,
       b.commission_rate, b.created_at::date AS cree
FROM bids b
WHERE b.deleted_at IS NULL
  AND b.payment_method = 'CASH'
  AND b.promo_code IS NOT NULL
  AND b.promo_code_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM promo_redemptions pr WHERE pr.bid_id = b.id)
ORDER BY b.created_at;
\echo '== Q5'
SELECT left(b.id::text, 8) AS bid, b.status, b.payment_method,
       (b.linked_negotiation_thread_id IS NOT NULL) AS negocie,
       al.created_at AS livre_le,
       (a.departure_date + COALESCE(a.departure_time, TIME '00:00')
          + CASE WHEN a.departure_time IS NULL THEN INTERVAL '1 day' ELSE INTERVAL '0' END)
         AT TIME ZONE COALESCE(NULLIF(a.timezone, ''), 'Europe/Paris') AS depart_le
FROM audit_log al
JOIN bids b ON b.id::text = al.payload->>'bidId'
JOIN announcements a ON a.id = b.announcement_id
WHERE al.action = 'DELIVERY_CONFIRMED'
  AND al.created_at < (a.departure_date + COALESCE(a.departure_time, TIME '00:00')
          + CASE WHEN a.departure_time IS NULL THEN INTERVAL '1 day' ELSE INTERVAL '0' END)
         AT TIME ZONE COALESCE(NULLIF(a.timezone, ''), 'Europe/Paris')
ORDER BY al.created_at;
\echo '== Q6'
SELECT left(b.id::text, 8) AS bid, b.status AS statut_bid, b.no_show_at,
       left(c.id::text, 8) AS annulation, c.no_show_status, c.created_at AS signale_le
FROM bids b
JOIN cancellations c ON c.bid_id = b.id
WHERE c.deleted_at IS NULL
  AND c.reason = 'SENDER_NO_SHOW'
  AND c.scope = 'HANDOVER'
  AND b.status = 'NO_SHOW'
ORDER BY c.created_at;
\echo '== Q7'
SELECT left(p.id::text, 8) AS paiement, left(b.id::text, 8) AS bid, b.status AS statut_bid,
       p.status AS statut_paiement, p.rail, p.amount, p.currency, p.created_at::date AS cree
FROM payments p
JOIN bids b ON b.linked_negotiation_thread_id = p.negotiation_thread_id
WHERE p.bid_id IS NULL
  AND p.deleted_at IS NULL
  AND p.status = 'PENDING'
  AND (b.status = 'COMPLETED'
       OR EXISTS (SELECT 1 FROM audit_log al
                   WHERE al.action = 'DELIVERY_CONFIRMED'
                     AND al.payload->>'bidId' = b.id::text))
ORDER BY p.created_at;
\echo '== Q0'
SELECT version, description, installed_on::date, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
ROLLBACK;
```
</details>

### 8.H Recette staging à valider avant la prod

Préalable : staging au commit `19ed17e4` (#421 à #423 inclus, base V294 : c'est le cas depuis le 07/10/2026 12:49 UTC) et app de recette du lot installée.

- [ ] **E1** — Appel sur iPhone avec écouteurs : couper le haut-parleur rend le son aux écouteurs (ou au combiné sans écouteurs).
- [ ] **86** — Messagerie : répondre à un message par balayage et par appui long ; la citation s'affiche chez l'autre.
- [ ] **CB** — Saisir le code de livraison avant le départ du trajet → refus « trajet pas encore parti » ; après le départ → accepté.
- [ ] **ER** — Trajet en F CFA créé depuis une grille en euros → prix convertis (≈ 656 XOF pour 1 €), pas 10 F CFA.
- [ ] **CM** — Mettre une conversation en sourdine → plus de push pour ses messages, non-lus toujours comptés ; l'autre ne voit rien.
- [ ] **EJ** — Archiver puis retirer une discussion de prix terminée ; l'autre partie la voit toujours.
- [ ] **DK** — Bouton téléphone de la fiche : visible jusqu'à J+3 après livraison, absent ensuite.
- [ ] **E8** — Le destinataire refuse : le voyageur ne le voit plus, l'expéditeur reçoit la demande de remplacement.
- [ ] Envoi **négocié** puis annulé, payé par **carte** → remboursement automatique de l'expéditeur.
- [ ] Envoi **négocié** puis annulé, payé en **mobile money** → remboursement du dépôt.
- [ ] Envoi **négocié** en **espèces** annulé → commission recréditée au portefeuille du voyageur.
- [ ] **Code promo** utilisé puis envoi annulé → le code est à nouveau utilisable.
- [ ] Demande d'envoi de **32 kg** → acceptée (avant : erreur 500 au-delà de 30 kg).
- [ ] Compteur **« offre en attente »** : un fil conclu n'est plus compté (EA).
- [ ] Après la recette, relancer G1 à G7 sur la staging : aucune **nouvelle** ligne datée d'après le 07/10/2026 12:49 UTC.

### 8.I Points techniques en suspens (non bloquants sauf mention)

- [ ] `.trivyignore` : **CVE-2026-47884** (spring-webmvc, XsltView non utilisé) ignorée depuis le 06/10.
      À retirer dès qu'un Spring Boot 3.5.x embarque un spring-webmvc corrigé (actuel : 3.5.16).
- [ ] Règles Firestore : la mise à jour de `readAt` (marquage lu) n'est **pas bornée** en valeur
      (seul le champ modifié est contrôlé). Correctif à prévoir dans dony-functions.
- [ ] dony-functions n'a **aucune CI** (pas de dossier `.github/`). Proposé : un job GitHub Actions qui
      lance `npm ci && npm run build && npm run test:rules` sur chaque PR.
- [ ] **Garde manuelle no-show** : #415 protège le cron, mais une déclaration manuelle d'absence du
      voyageur pendant les 24 h de contestation de l'expéditeur n'est **pas** encore bloquée. Non corrigé.
- [ ] Règles produit **E0 à E6** : à décider par le propriétaire. Non bloquant pour cette mise en prod.
- [ ] Workflow prod à compléter (8.E et 8.F) : `CALLS_ENABLED`, `STREAM_API_KEY`, `STREAM_API_SECRET`,
      `SENTRY_WEBHOOK_SECRET`, `GRAFANA_*`. **Bloquant** seulement si on veut les appels et le webhook Sentry en prod.
- [ ] SMS de repli des notifications critiques (#420) : coupé par défaut (`CRITICAL_SMS_FALLBACK_ENABLED`).
      Décider en back-office s'il faut l'activer (coût SMS).

---

## 9. Mise en prod du samedi 10/10/2026 — ce qui change depuis le 07/10

> Rédigée le 08/10/2026, **mise à jour le 09/10/2026** pour `main` = `d5a99f55` (PR jusqu'à #457,
> migrations jusqu'à **V307**). La section 8 reste le déroulé de référence (Firestore → back → app,
> sauvegarde, Stripe, Stream, Sentry, contrôles G1 à G7, recette). Cette section ajoute ce qui a été
> fusionné sur `main` depuis `19ed17e4` et ce que le test de charge du 07-08/10 a appris sur l'API.
> Aucun secret ici.

### 9.1 Ce qu'il faut savoir avant de commencer

- `main` a reçu **#425 à #439** depuis le tag `sha-19ed17e` recommandé en 8.A. Ce tag ne contient
  donc ni les corrections de concurrence, ni le nouveau pool, ni les photos de messagerie.
- **Aucune nouvelle variable d'environnement** ni modification de `deploy-prod.yml`, `docker-compose.prod.yml`
  ou `nginx/` depuis `19ed17e4` : les secrets et variables listés en 8.E/8.F/8.I suffisent.
- Trois migrations de plus : **V295, V298, V299** (V296 et V297 n'existent pas, ce trou est sans effet pour Flyway).
- Le pool de connexions prod passe de **10 à 20** avec un délai d'attente de **5 s** (cette PR, voir 9.4).
- **Depuis le 08/10**, `main` a reçu **#440 à #457** (voir [9.10](#910-ajouts-du-08-au-0910--pr-440-à-457))
  et **six migrations de plus : V301 à V305 et V307** (V300 et V306 n'existent pas, trous sans effet).
  Dernière version Flyway attendue après la mise en prod : **308** (V308 ajoutée le 09/10, voir [9.11](#911-ajouts-du-09110--pr-459-à-463-app-556-à-574-admin-56-règles-7)).
- Toujours **aucune nouvelle variable d'environnement obligatoire** ni modification de `deploy-prod.yml` :
  #454 ajoute seulement `keepalive-time` et `max-lifetime` du pool dans `application.yml` (tous profils).
- La prod tourne encore `dda67f58` (20/09) : la mise en prod emporte **tout** de #317 à #457 et les
  migrations **V263 à V307** (8.C). La sauvegarde de 8.A n'est pas facultative.

### 9.2 Tag d'image à promouvoir

- [ ] Redéployer la staging depuis `main` (dernier déploiement staging connu : `658aa3be`, #463, le 09/10, Flyway 308 ; avant lui `f7347216`, #451, le 08/10 ;
      `main` a reçu #452 à #457 depuis, dont V305 et V307) :
      ```bash
      gh workflow run deploy-staging.yml -R MONDONY/yadony-back -f ref=main
      gh run list -R MONDONY/yadony-back --workflow deploy-staging.yml --limit 1
      ```
- [ ] Noter le commit déployé : le tag à promouvoir est **`sha-<7 premiers caractères>`** de ce commit.
- [ ] Faire la recette 8.H **et** 9.6 sur cette staging, puis lancer `deploy-prod.yml` avec ce tag (`image_tag=sha-xxxxxxx`).

### 9.3 Migrations Flyway V295 à V299

Après le déploiement, la requête de 8.C (étape 2) doit montrer **308** comme dernière version (V295 à V299 ici,
V301 à V307 en [9.10](#910-ajouts-du-08-au-0910--pr-440-à-457)), toutes `success = t`.

| Migration | Ce qu'elle fait | Risque | Contrôle préalable |
|---|---|---|---|
| `V295__messaging_images` | Crée `messaging_images` (photos de la messagerie, #429) + un index partiel. | Faible. Table neuve. | Aucun. |
| `V298__users_sender_cancellation_count` | Ajoute `users.sender_cancellation_count` (défaut 0) et le remplit depuis `audit_log` (lecture seule). | Faible. `ADD COLUMN ... DEFAULT 0` est instantané en Postgres 16 ; le rattrapage lit `audit_log` une fois. | Aucun. |
| `V299__trips_country_codes_backfill` | Remplit les codes pays NULL des trajets et modèles depuis la ville, corrige les codes intervertis (FLUTTER-EH). | Moyen-faible : `UPDATE` sur `announcements` et `trip_templates`. Ne touche que les codes NULL ou exactement intervertis. | `cities` doit être peuplée en prod (sinon la migration ne fait rien, sans erreur) : `SELECT count(*) FROM cities;` > 0. |

### 9.4 Configuration : pool de connexions (cette PR)

`application-prod.yml` est aligné sur la staging (#434) :

```yaml
hikari:
  maximum-pool-size: 20      # avant : 10
  minimum-idle: 2
  connection-timeout: 5000   # avant : 30000 (défaut Hikari)
```

Pourquoi : au test de charge staging (k6, jusqu'à 200 utilisateurs simultanés sur les favoris), le pool de 10
saturait (187 requêtes en attente) et les requêtes finissaient en **500 après 30 s**. Avec 20 connexions et
5 s : **0 erreur 5xx, p95 446 ms, max 2,5 s**. Si le pool est malgré tout épuisé, l'API répond désormais
**503 `service-busy`** avec `Retry-After: 2` au lieu de bloquer 30 s.

- [ ] **À vérifier sur le VPS prod** : Postgres tourne avec `max_connections` par défaut (100) ; 20 connexions
      d'API + sauvegarde + supervision restent largement en dessous. Contrôle :
      ```bash
      ssh <utilisateur>@<hôte-prod> 'docker exec yadony_db_prod psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc "SHOW max_connections"'
      ```
- [ ] **À vérifier** : mémoire libre du VPS prod (`free -m`). 10 connexions de plus coûtent quelques dizaines de Mo
      côté Postgres ; sans marge, rester à 15.

### 9.5 Ce qui part en plus (PR #425 à #439)

| PR | Sujet | À savoir |
|---|---|---|
| #425 | Alertes : un trajet dédié non ouvert n'apparaît plus chez les tiers (FLUTTER-EW) | — |
| #426 | Admin : auteur d'un message signalé résolu par son UID Firebase | — |
| #427, #435 | Trajets : codes pays conservés à la création/modification, fuseau déduit de la ville de départ (FLUTTER-EH) | Rattrapage par V299. |
| #428, #438 | Annulation : commission espèces et compteurs de fiabilité voyageur/expéditeur (FLUTTER-E4/E0/E6) | V298. #438 : la commission espèces est toujours rendue au voyageur, même s'il annule. |
| #429 | Photos dans la messagerie (FLUTTER-B4) | V295. Stockage R2 existant, pas de nouveau secret. |
| #430 | La devise active se change même avec des soldes non nuls | — |
| #432 | Code de connexion et admin au vouvoiement (FLUTTER-CD) | Textes seulement. |
| #433 | **Concurrence** : favoris, abonnements, drapeaux pays et portefeuilles créés par `INSERT ... ON CONFLICT DO NOTHING` ; solde de portefeuille lu sans écriture ; génération des récurrences de trajet **après** le commit ; création de compte invité concurrente en 409 au lieu de 500 | Supprime les 500 vus sous charge (double clic, deux appareils). |
| #434 | Pool staging 20 + 5 s ; pool épuisé = **503 `service-busy`** + `Retry-After` (sans alerte Sentry, simple `WARN` dans les logs) | Le 503 est attendu en cas de pic : ce n'est pas un bug. |
| #437 | Suppression de favori idempotente sous concurrence (DELETE natif) | 40 utilisateurs pendant 3 min : 18 732 requêtes, 0 échec. |
| #439 | Message « pays verrouillé » : ne cite plus que le compte de paiement | Texte seulement. |

### 9.6 Recette staging en plus de 8.H

- [ ] Messagerie : envoyer une **photo**, elle s'affiche chez l'autre (vignette puis plein écran).
- [ ] Ajouter/retirer un **favori** en tapant vite plusieurs fois : pas d'erreur, l'état final est juste.
- [ ] Créer un **trajet récurrent** : les occurrences apparaissent (générées juste après l'enregistrement).
- [ ] Trajet créé depuis un **modèle** : drapeaux de départ et d'arrivée présents, pays corrects.
- [ ] Expéditeur qui annule un colis **accepté** : son compteur d'annulations augmente sur le profil public.
- [ ] Changer de **devise active** avec un solde non nul : accepté.

### 9.7 Contrôles juste après la mise en prod (15 à 30 min)

- [ ] `actuator/health` = `UP` (le workflow l'attend déjà 60 s).
- [ ] Logs : aucune `SQLTransientConnectionException` / `Connection is not available` :
      ```bash
      ssh <utilisateur>@<hôte-prod> 'docker logs --since 30m yadony_api 2>&1 | grep -cE "Connection is not available|service-busy"'
      ```
      Quelques `service-busy` lors d'un pic sont tolérables ; un flux continu = pool trop petit ou requête lente.
- [ ] Grafana prod (`admin.yadony.com/grafana/`) : `hikaricp_connections_active` sous 20 au repos,
      `hikaricp_connections_pending` à 0 la plupart du temps, `hikaricp_connections_timeout_total` stable.
- [ ] Sentry prod : pas de nouvelle issue `DataIntegrityViolationException`, `TransactionRequiredException`
      ou `UnexpectedRollbackException` (les familles corrigées par #433/#437).
- [ ] Flyway à **308** (9.3, 9.10 et 9.11) et contrôles G1 à G7 de 8.G.
- [ ] Grafana prod : `hikaricp_connections_usage_seconds_max` sous quelques secondes (connexion tenue anormalement
      longue, cf. 9.9) et `/wallet/balance` sans `service-busy` en continu (#451).

### 9.8 Si ça se passe mal

- Retour arrière de l'image : relancer `deploy-prod.yml` avec le tag précédent. **Attention** : les migrations
  V295–V307 restent appliquées ; elles sont compatibles avec l'ancien code (colonnes nullables ou à défaut, tables
  neuves, contraintes posées seulement sur des colonnes neuves que l'ancien code n'écrit jamais, index, données
  seulement complétées). Vérifié fichier par fichier pour V301 à V307 le 09/10. L'ancienne image redémarre sans erreur Flyway : la configuration garde le défaut
  `ignore-migration-patterns: "*:future"`, qui ignore les versions appliquées plus récentes que le code.
- Retour arrière du pool seul : remettre `maximum-pool-size: 10` dans `application-prod.yml` exige un nouveau build ;
  plus simple, surcharger par variable d'environnement `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10` dans le `.env`
  du serveur (relu au redémarrage, mais **réécrit** par `deploy-prod.yml` au déploiement suivant).

### 9.9 Points ouverts du test de charge (non bloquants)

- Une connexion tenue **346 s** sur la staging le 08/10 à 00:09 UTC (`hikaricp_connections_usage_seconds_max`) :
  **expliquée** depuis, gel mémoire du VPS staging (swap, k6 lancé sur la même machine), pas un défaut de l'API.
  Surveiller quand même la même métrique en prod.
- Mesures k6 staging du 08/10 (parcours `load-test/scenarios/journey.js`, 50 → 200 utilisateurs simultanés, un seul
  compte) : p95 global **5,1 s → 3,2 s** après #441/#442/#446 ; `/conversations` 15 s → 4,5 s ; notes reçues
  7,5 s → 2,0 s ; `/wallet/balance` **8,0 s → 4,2 s** après #451. À 200 utilisateurs, le CPU du VPS staging
  (2 vCPU) est saturé et le pool à 20/20 : 0,2 % de `503 service-busy`. Le plafond est la machine, plus le code.
- Une seule instance d'API en prod : la montée en charge horizontale (plusieurs conteneurs derrière nginx)
  reste à faire si le trafic dépasse ce que 20 connexions absorbent.

### 9.10 Ajouts du 08 au 09/10 : PR #440 à #457

| PR | Sujet | À savoir |
|---|---|---|
| #431 | Destinataire absent : procédure encadrée et partage admin du séquestre (FLUTTER-E2) | **V301**. |
| #436 | Voyage à plusieurs étapes, publié en une transaction (FLUTTER-4D) | **V302**. |
| #440 | Pool prod à 20 + 5 s, cette checklist | Voir 9.4. |
| #441 | Listes sans N+1 : conversations, notes reçues, Mes trajets | Perf, contrat inchangé. |
| #442 | Portefeuille : plus de connexion tenue pendant les appels Stripe | Perf. |
| #443 | Scripts k6 (`load-test/`) | Outillage, rien en prod. |
| #444 | Une seule clé de chiffrement dans la suite de tests | Tests seulement. |
| #445 | Pop-up d'évaluation hors délai, cache trajets actifs, retrait d'offre acceptée | — |
| #446 | `GET /bids/me` paginé et filtrable | Sans `page`, l'ancien contrat (tableau complet) reste servi aux apps installées. |
| #447 | Annulation de trajet : retour des colis déjà remis, contact pendant le retour | — |
| #448 | Messagerie : statut brut du colis et retour en cours dans la liste | — |
| #449 | Mode recette des comptes testeurs (FLUTTER-FA/FB) | **V303**. Inactif en prod : `RecetteMode` l'ignore sous le profil prod. |
| #450 | Annulation du trajet bloquée pendant un paiement | — |
| #451 | `GET /wallet/balance` en une seule transaction de lecture | **V304** (index). |
| #452 | Notifications : tous les types de push classés, trois nouveaux réglages (FLUTTER-GB) | **V305**, défaut `TRUE` : personne ne perd une notification qu'il recevait. |
| #453 | Code de retrait bloqué notifié, favoris invités relus, audit favoris | — |
| #454 | Pool : `keepalive-time` 60 s et `max-lifetime` 15 min, tous profils | Connexions mortes remplacées au lieu d'échouer à l'emprunt. |
| #455 | Date limite de dépôt, offres envoyées, contrepartie du fil (FLUTTER-GA/GC/G8) | Nouveau cron `YADONY_MATCHING_HANDOVER_DEADLINE_EXPIRE_CRON`, défaut toutes les 5 min : rien à poser. |
| #456 | Trajets : escales, filtre par moyen de paiement, carte facultative (FLUTTER-GE/GD/G0/FT) | **V307**. |
| #457 | No-show voyageur calculé dans le fuseau du trajet | — |

| Migration | Ce qu'elle fait | Risque |
|---|---|---|
| `V301__destinataire_absent_procedure_et_partage` | Colonnes nullables sur `bids`, `conversations`, `cancellations`, `disputes` ; table neuve `payment_splits` ; 2 index. | Faible. Contraintes uniquement sur les colonnes neuves. |
| `V302__announcements_trip_group` | `announcements.trip_group_id` / `trip_leg_index` nullables + contrainte + index partiel. | Faible. La contrainte relit `announcements` une fois (toutes les lignes sont à NULL). |
| `V303__users_recette_tester` | `users.recette_tester BOOLEAN NOT NULL DEFAULT FALSE`. | Très faible : instantané en PostgreSQL 16. |
| `V304__wallet_refund_items_par_transaction` | Index sur `wallet_refund_request_items (wallet_transaction_id)`. | Très faible : petite table. |
| `V305__notification_prefs_familles_completes` | 3 colonnes `BOOLEAN NOT NULL DEFAULT TRUE` sur `user_notification_preferences`. | Très faible. |
| `V307__trajets_escales_et_refus_carte` | `stops_count` nullable + contrainte sur `announcements` et `trip_recurrences` ; `announcements.card_declined` à défaut `FALSE`. | Faible. Contraintes relues une fois, toutes les lignes à NULL. |

- [ ] **Prochaine migration : V310** (V308 prise le 09/10, voir 9.11 ; V309 prise par #469, voir 9.13). Ne jamais créer V300 ni V306 après coup : une version inférieure à la
      dernière appliquée est refusée par Flyway au démarrage (pas d'`out-of-order` configuré).
- [ ] Recette staging en plus de 8.H et 9.6 : voyage à **plusieurs étapes** publié puis visible étape par étape ;
      trajet en avion avec **escales**, filtre « Direct uniquement » ; publication **sans la carte** ;
      écran **portefeuille** et **Mes colis** qui s'ouvrent normalement ; réglages de notifications avec les
      trois nouveaux interrupteurs.

### 9.11 Ajouts du 09/10 : PR #459 à #463, app #556 à #574, admin #56, règles #7

> Ajoutée le 09/10/2026. `main` back = `658aa3be` (déployé en staging le 09/10, Flyway **308**, API healthy).
> Builds de recette staging : IPA **108** et AAB **109** depuis `dony_app` `main` = `bea5310e`. Aucun secret ici.

#### Correction importante : le projet Firebase de prod est `yadony-prod`

- `dony_app/env.prod.json` → `FIREBASE_PROJECT_ID` = **`yadony-prod`** ; `env.staging.json` → `yadony-f1f0f`.
  Le `.firebaserc` local de `dony-functions` contient l'alias `prod` = `yadony-prod` (modification locale non
  committée). **`yadony-f1f0f` sert uniquement la staging.**
- Le 09/10, les règles Firestore de `main` (`7dbf47d`, #4 + #6 + #7) ont été déployées sur **`yadony-f1f0f`
  seulement**. La prod n'a encore **aucune** de ces règles.
- [ ] Étape 1 de 8.A, en prod :
      ```bash
      cd ~/Desktop/dony/dony-functions
      git checkout -- firebase-debug.log   # fichier de log supprimé localement, bloque la mise à jour
      git fetch origin && git merge --ff-only origin/main   # `git pull` refuse à cause de .firebaserc modifié (pull en rebase)
      git log --oneline -1                 # doit afficher 7dbf47d … (#7)
      npx firebase deploy --only firestore:rules --project yadony-prod
      ```
      La sortie doit dire « uploading rules firestore.rules ». « skipping upload » = mauvais fichier (copie en retard).
- [ ] Console Firebase `yadony-prod` → Firestore → Règles : la version publiée contient `hasOnlyClientMessageKeys`,
      `isValidReplyTo` et le refus des messages `IMAGE` écrits par un client.
- À savoir (#7) : les photos de messagerie ne s'écrivent plus que par le serveur (#429). Sur les anciennes versions
  de l'app encore installées, l'envoi de photo échoue jusqu'à la mise à jour : attendu, rien d'autre ne casse.
- [ ] **À vérifier** : `onNewMessage` (#3/#5) déployée sur `yadony-prod` ? Sinon
      `npx firebase deploy --only functions:onNewMessage --project yadony-prod` (après accord).

#### Back : ce qui part en plus

| PR | Sujet | À savoir |
|---|---|---|
| #459 | Retrait après report d'un colis déjà remis : même procédure de retour (code, 3 jours, remboursement intégral, notifications aux deux) ; lien de la notification d'une demande carte abandonnée vers le trajet | — |
| #460 | Trajets récurrents : carte décochable, recopiée sur les occurrences ; escales par étape | **V308**. |
| #461 | Voyageur : « Demander un nouveau code » de retrait (`POST /tracking/{bidId}/request-code`, 1 demande / 15 min / colis) | Notification `CONFIRMATION_CODE_REQUESTED`. Sans migration (limite lue dans `audit_log`). |
| #462 | Suggestions de trajets aussi pour les colis annulés après remise | `PARCEL_RETURN_REQUIRED` peut porter `cancellationId`. |
| #463 | Trajets récurrents : la devise du modèle est enregistrée (avant : euro par défaut) | 0 récurrence en staging ; **contrôle prod ci-dessous**. |

| Migration | Ce qu'elle fait | Risque |
|---|---|---|
| `V308__trip_recurrences_refus_carte` | `trip_recurrences.card_declined BOOLEAN NOT NULL DEFAULT FALSE`. | Très faible : instantané en PostgreSQL 16. |

- [ ] Contrôle prod (lecture seule) avant/après : récurrences en EUR créées par des voyageurs dont la devise active
      n'est pas l'euro (créées avant #463) : `SELECT count(*) FROM trip_recurrences WHERE currency = 'EUR' AND deleted_at IS NULL;`
      puis regarder, pour ces lignes, le pays du voyageur (`user_id`) et décider au cas par cas (aucune correction automatique).

#### Mode recette : doit rester fermé en prod

- `yadony.recette.enabled` vaut `false` par défaut et n'est vrai que dans `application-staging.yml` ; le profil prod
  le ferme de toute façon (`RecetteMode`).
- [ ] Vérifier que le `.env` prod ne contient **pas** `YADONY_RECETTE_ENABLED=true` (lire seulement ce nom de variable).
- [ ] Après la mise en prod : `PUT /api/v1/admin/users/{id}/recette-tester` doit répondre **409 `recette-disabled`**.
- [ ] Idem en masse : `PUT /api/v1/admin/users/recette-tester` → **409 `recette-disabled`**, et `GET /api/v1/admin/recette/status` → `{"enabled":false}` (l'admin masque alors la sélection « mode recette »).

#### dony-admin (#54 à #56)

- Chaque fusion sur `main` déploie la **staging** automatiquement (workflow « Deploy VPS ») ; la prod ne part qu'à la main.
- [ ] Après le back : `gh workflow run deploy.yml -R MONDONY/dony-admin -f environment=production -f image_tag=sha-<7 car.>`
      (tag du commit `main` recetté en staging ; `latest` = dernier `main`).
- [ ] En prod, la section « Mode recette (staging) » de la fiche utilisateur doit afficher « Indisponible hors staging »
      après la première tentative (409), jamais un interrupteur actif.

#### App iOS et Android

- `main` app embarque #556 à #574. Points sensibles :
  - **#563/#565 (Android)** : Stripe rattaché à l'activité recréée par HyperOS (moteur Flutter en cache) ; libération
    du module par réflexion sur un champ privé de `stripe_android` 12.1.0 (à revérifier à chaque montée de version).
  - Plusieurs contrats tolèrent l'ancien back, mais certaines actions renvoient une erreur tant que le back n'est pas
    à jour (ex. « Demander un nouveau code ») : **back avant app**, comme en 8.A.
- [ ] Avant le build prod iOS : restaurer `ios/Flutter/Release-prod.xcconfig` depuis `Release-prod.xcconfig.prod`
      (le fichier actif contient les valeurs **staging** depuis les builds de recette du 08/10). Contrôle :
      `YADONY_ENV_FILE=env.prod.json tool/verify_ios_release_config.sh <ipa>` doit citer le projet de `yadony-prod`.
- [ ] Recette Android réelle du paiement (carte et PayPal) sur l'AAB 109 : Options développeur
      « Ne pas conserver les activités », puis « Texte en gras » ; la feuille Stripe s'ouvre, aucun
      « FragmentManager has been destroyed ».

#### CI : exception de sécurité temporaire

- `.trivyignore` contient **CVE-2026-47890** (spring-webmvc 6.2.19, flux SSE ; le back n'utilise pas SSE).
  Décision du 09/10 : on garde l'exception et on la retire dès qu'un correctif Spring 6.2.x sort.
- [ ] À chaque mise en prod : vérifier si Spring a publié le correctif ; si oui, monter la version et retirer la ligne.

#### Recette staging en plus de 8.H, 9.6 et 9.10

- [ ] Report de trajet puis retrait d'un colis **déjà remis** : voyageur « rendez-lui le colis avant le … »,
      expéditeur « code de retour dans le suivi », contact ouvert, une seule notification chacun.
- [ ] Colis annulé après remise : bouton « Voir les trajets alternatifs » sur la fiche, bandeau « Colis à récupérer ».
- [ ] Code de retrait : 3 codes faux → bloqué, expéditeur notifié, « Générer un nouveau code » ; code expiré →
      voyageur « Demander un nouveau code », 2e demande avant 15 min refusée avec le délai.
- [ ] Trajet récurrent en XOF : devise conservée ; carte décochable (EUR) ; voyage multi-étapes avec escales différentes.
- [ ] Notification d'une demande carte abandonnée à la date limite : ouvre le trajet, jamais « demande introuvable ».

### 9.12 Ajouts de l'après-midi du 09/10 : back #465 et #466, app #575 et #576, admin #57

> Aucune migration (dernière : **V308**). Aucun secret, aucune nouvelle variable.

| PR | Sujet | À savoir |
|---|---|---|
| back #465 + admin #57 | Mode recette en masse : `PUT /api/v1/admin/users/recette-tester` (jusqu'à 200 comptes), `GET /api/v1/admin/recette/status`, champ et filtre `recetteTester` dans la liste admin | En prod : statut `enabled:false`, PUT en **409 `recette-disabled`**, barre de sélection masquée dans l'admin. |
| back #466 | Plafond de prix au kilo par devise (`CurrencyBounds`, 422 `price-out-of-bounds`) à la création **et à la modification** de tout trajet, chaque étape d'un voyage comprise | **Changement de comportement** : une annonce existante au-dessus du plafond de sa devise sera refusée à sa prochaine modification. Contrôle prod ci-dessous. Pas de prix minimum (décision en attente). |
| app #575 | Plantage « Annuler » après archivage (FLUTTER-GN) ; messages avec bouton qui disparaissent (4 s / 6 s avec « Annuler ») ; visionneuse photo (croix visible, glisser pour fermer) ; aperçu des moyens de paiement ; confirmation « Publier en XOF » et devise sur chaque étape ; route des retours testeurs depuis une feuille | Client seul, sauf le plafond (#466). |
| app #576 | Ligne expéditeur sur la fiche publique d'une demande ; résumé des colis du trajet ; fiche trajet au format billet ; onglets Envois / Réceptions dans le Suivi | Client seul. |

- [ ] Contrôle prod (lecture seule) avant la mise en prod de #466 : annonces actives au-dessus du plafond de leur devise.
      Plafonds dans `CurrencyBounds` (back) ; exemple pour l'euro (500 €/kg) :
      `SELECT currency, count(*) FROM announcements WHERE deleted_at IS NULL AND status = 'ACTIVE' AND currency = 'EUR' AND price_per_kg > 500 GROUP BY currency;`
      À répéter pour chaque devise avec son plafond. Si des lignes sortent : prévenir les voyageurs concernés avant la mise en prod.
- [ ] Après la mise en prod : `GET /api/v1/admin/recette/status` répond `{"enabled": false}`.
- [ ] Recette staging : archiver une conversation puis « Annuler » (pas de plantage) ; un message « Réessayer » disparaît seul ;
      photo blanche en plein écran fermée par la croix et par un glissement ; création d'un voyage avec « Publier en XOF »
      (confirmation, devise sur chaque étape, prix d'étape plafonné) ; sélection groupée « mode recette » dans Utilisateurs.

#### 9.12 bis : prix minimum au kilo (back #468, app #577)

- Minimum = **1 €/kg converti**, arrondi vers le haut : EUR 1,00 · USD 1,08 · CAD 1,47 · GBP 0,86 · CHF 0,95 · **XOF/XAF 656**.
  Même erreur que le plafond (`422 price-out-of-bounds`, nouvelle propriété `reason` = `too-low` / `too-high`, plus `min`, `max`,
  `currency`, `legIndex` sur une étape). Appliqué au trajet simple, aux étapes, à la modification, aux récurrences et aux modèles.
- Les prix au kilo issus d'une négociation (`NegotiationService`) ne passent pas par ce plancher.
- [ ] Contrôle prod (lecture seule) avant la mise en prod : annonces actives **sous** le minimum de leur devise (refusées à leur
      prochaine modification), ex. `SELECT currency, count(*) FROM announcements WHERE deleted_at IS NULL AND status = 'ACTIVE'
      AND ((currency = 'EUR' AND price_per_kg < 1) OR (currency IN ('XOF','XAF') AND price_per_kg < 656)) GROUP BY currency;`
      (staging le 09/10 : 1 en EUR, 1 en XOF). Prévenir les voyageurs concernés.
- [ ] Recette staging : une étape à 8 F CFA/kg est bloquée dans l'app (« Prix trop bas : minimum 656 F CFA/kg ») et refusée par le serveur.

#### 9.13 Argent : contraintes V309, rapprochement Stripe/pawaPay, capture des paiements négociés (back #469, #471, #472)

> Ajoutée le 09/10/2026. Migration **V309**. Aucun secret, aucune nouvelle variable obligatoire.
> **Staging = `sha-d1da639`** depuis le 09/10 à 14:54 UTC (V309 appliquée, contrôles à 0 juste avant, API saine) :
> c'est le tag à promouvoir, ou un tag plus récent recetté de la même façon.

| PR | Sujet | À savoir |
|---|---|---|
| back #469 | **V309** : CHECK sur `payments` (remboursé entre 0 et le montant, commission ≤ montant), sur `wallet_transactions` (solde après ≥ 0, signe du montant selon le type), `pawapay_operations.amount > 0`, index unique sur `provider_transaction_id` ; trigger `trg_wallet_transactions_append_only` | Le grand livre du wallet devient **ajout seul** : tout UPDATE/DELETE est refusé, même à la main en SQL. Une correction passe par un mouvement compensatoire (ajustement admin). Si une ligne existante viole une contrainte, Flyway échoue et l'API ne démarre pas : faire le contrôle ci-dessous avant. |
| back #471 | Rapprochement quotidien avec Stripe et pawaPay (04:30 UTC) : paiements colis, recharges wallet, commissions carte, opérations pawaPay terminées | Alertes admin `RECON_STRIPE_*` / `RECON_PAWAPAY_*` (Telegram + écran Alertes). Jauges `yadony_reconciliation_{mismatches,errors}{provider}` et `yadony_reconciliation_last_run_seconds`. Coupure : `PROVIDER_RECONCILIATION_ENABLED=false`. pawaPay coupé en prod : aucune opération, aucun appel. |
| back #472 | Capture du paiement carte d'une négociation passé en séquestre au `/checkout` | **Corrige une régression de #408** : sans #472, l'autorisation carte d'une négociation n'est jamais capturée, expire à J+7 et le voyageur n'est pas payé à la livraison. **Ne jamais promouvoir un tag qui contient #408 (`99b08aa6`) sans #472 (`5d9da84b`)** : le tag `sha-d5a99f5` est dans ce cas. |

- [ ] Tag : **`image_tag=sha-d1da639`** (ou plus récent). **Jamais `sha-d5a99f5`** : il contient #408 sans #472.
      Pour un autre commit, vérifier qu'il contient `5d9da84b` : `git merge-base --is-ancestor 5d9da84b <commit> && echo OK`.
- [ ] Contrôle prod (lecture seule) avant la mise en prod de V309, chaque requête doit répondre **0** (prod vérifiée conforme le 09/10) :
      ```sql
      SELECT count(*) FROM payments
       WHERE (refunded_amount IS NOT NULL AND (refunded_amount < 0 OR refunded_amount > amount)) OR commission_amount > amount;
      SELECT count(*) FROM wallet_transactions
       WHERE balance_after < 0
          OR (type IN ('TOP_UP','REFUND','REFERRAL_REWARD','ADMIN_CREDIT') AND amount < 0)
          OR (type IN ('BID_PAYMENT','COMMISSION_DEDUCTED','ADMIN_REFUND_OUT','SELF_REFUND_OUT','FORFEITED_ON_DELETION','ADMIN_DEBIT') AND amount > 0);
      SELECT count(*) FROM pawapay_operations WHERE amount <= 0;
      SELECT count(*) FROM (SELECT provider_transaction_id FROM pawapay_operations
       WHERE provider_transaction_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1) d;
      ```
- [ ] **Stripe Live, AVANT la mise en prod** : tableau de bord en mode **Live** → Développeurs → Webhooks → endpoint
      `https://api.yadony.com/api/v1/payments/webhook` (événements « de votre compte ») → « Sélectionner des événements ».
      Ces **16 événements** doivent être cochés, comme sur l'endpoint de test `we_1UOeu69i7EY14IsEeZ5SPhqG` :
      - `payment_intent.amount_capturable_updated` ← **indispensable** : sans lui, un paiement carte reste PENDING et n'est jamais capturé
      - `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`
      - `charge.refunded`, `charge.refund.updated`
      - `transfer.created`, `transfer.reversed`, `transfer.updated`
      - `charge.dispute.created`, `charge.dispute.closed`, `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`
      - `setup_intent.succeeded`, `payment_method.detached`
      - `radar.early_fraud_warning.created`

      Ne pas régénérer le secret de l'endpoint. Si tu le fais, mets à jour `STRIPE_WEBHOOK_PAYMENTS_SECRET` dans l'environnement GitHub
      `production` **avant** de déployer.
- [ ] Juste après, **tester l'endpoint Live** : « Envoyer un événement test » (`payment_intent.succeeded`) doit répondre **200**,
      et l'événement doit apparaître dans `SELECT event_type, status, received_at FROM stripe_event_inbox ORDER BY received_at DESC LIMIT 3;`.
      Un 400 signifie que le secret de l'endpoint ne correspond pas à `STRIPE_WEBHOOK_PAYMENTS_SECRET`.
- [ ] **Ne pas créer d'endpoint « comptes connectés »** (`account.updated`, `capability.updated`, `payout.paid`, `payout.failed`,
      `account.application.deauthorized`) tant que le back n'accepte qu'un secret par route : un endpoint Stripe reçoit soit les événements
      de ton compte, soit ceux des comptes connectés, jamais les deux, et chacun a son propre secret. Les 21 événements de 8.D ne tiennent
      donc pas dans un seul endpoint : les 5 événements Connect demandent d'abord un changement back (accepter un 2ᵉ secret).
      L'app relit le statut Connect à l'ouverture, ce qui couvre ce manque en attendant.
- [ ] Rappel staging (fait le 09/10) : l'endpoint de test `we_1UOeu69i7EY14IsEeZ5SPhqG` remplace le relais `stripe listen`
      (`stripe-cli-payments`), qui ne transmettait que 6 types d'événements ; événement de test accepté à 14:52:59 UTC.
      La prod n'a jamais eu de relais : Stripe Live appelle l'API directement.
- [ ] Recette staging **avant la prod** (sur `sha-d1da639`) : payer une négociation par carte, puis vérifier que le paiement a un `captured_at` dans la minute
      (`SELECT status, captured_at FROM payments WHERE negotiation_thread_id = '<fil>';` → `ESCROW` + date) et que le
      PaymentIntent est « Succeeded » dans Stripe (et non « Uncaptured »).
- [ ] Après la mise en prod : `yadony_money_invariant_violations{invariant="INV-08"}` reste à 0 ; le lendemain matin,
      `yadony_reconciliation_last_run_seconds` est daté d'après 04:30 UTC et `yadony_reconciliation_errors` vaut 0.

#### 9.14 Fin du 09/10 : back #476, app #578 à #580 (FLUTTER-H2 à H7)

> Ajoutée le 09/10/2026. Aucune migration (dernière : **V309**), aucun secret, aucune nouvelle variable.
> `main` back = `4a09f6ad` (#476). Builds de recette staging : IPA **109** et AAB **110** depuis `dony_app` `main` (#578 à #580 inclus).

| PR | Sujet | À savoir |
|---|---|---|
| back #476 | Accord de prix **en espèces** sur un trajet : le voyageur reçoit une notification dédiée `BID_NEGOTIATION_COMMISSION_DUE` (« Réglez la commission Yadony », lien `yadony://bids/{id}/negotiation`) au lieu de `BID_CREATED` « Nouvelle demande d'envoi » ; `commissionDueBy` ajouté en fin de `BidNegotiationResponse` (PENDING + espèces seulement) | Les demandes cash classiques gardent `BID_CREATED`. `notifications.type` est un `varchar(50)` sans CHECK : pas de migration. Une app ancienne ouvre le fil depuis le centre de notifications (lien serveur) ; un tap sur le push ouvre l'app sans naviguer, sans plantage. |
| app #580 | Bouton « Régler la commission » dans le fil d'un trajet payé en espèces (biométrie/PIN, portefeuille, 3DS, recharge si solde insuffisant, compte à rebours), lien « Refuser le colis » ; le fil ne se ferme plus après l'acceptation | **Jumelle de #476** : back avant app. Sans #476, pas de compte à rebours, le bouton fonctionne quand même. |
| app #578 | Plantage du fil d'étapes fermé en vol (FLUTTER-H3/H2) ; icône de signalement visible en thème sombre (FLUTTER-H6) | Client seul. |
| app #579 | Intention « Votre destination principale » : 14 pays UEMOA + CEMAC (+ Autre) ; section « Destinations favorites » (sans effet) retirée des réglages | Client seul, le back accepte déjà ces pays (`CountryCatalog`). |

- [ ] Tag prod : doit contenir `4a09f6ad` (#476) **et** `5d9da84b` (#472, voir 9.13) : `git merge-base --is-ancestor 4a09f6ad <commit> && echo OK`.
- [ ] Recette staging : un voyageur accepte un prix en espèces dans le fil d'un trajet → notification « Réglez la commission Yadony »
      qui ouvre le fil ; bouton « Régler la commission » avec compte à rebours ; solde insuffisant → recharge puis relance ;
      succès → écran « accepté » ; « Refuser le colis » fonctionne.
- [ ] Recette staging : fermer la fiche d'un voyage multi-étapes pendant son chargement (aucun plantage) ; icône de signalement
      visible en thème sombre ; intention avec un pays hors des 4 historiques (ex. Gabon) acceptée.

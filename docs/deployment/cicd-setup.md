# Configuration de la CI/CD yadony

## Vue d'ensemble

- `ci.yml` — quality gates sur chaque PR, et seulement sur les PR : un push ne
  lance rien. Un nouveau commit sur une PR annule la CI précédente.
- `deploy-staging.yml` — déclenchement MANUEL uniquement (Actions → Deploy Staging
  → Run workflow, ou `gh workflow run deploy-staging.yml -f ref=main`). Build l'image
  depuis `ref` (défaut `main`), push sur `ghcr.io` (tags `sha-<court>` + `staging`),
  déploie `sha-<court>` sur le VPS staging. Avec `image_tag`, redéploie une image
  existante sans build. Aucune fusion ni aucun push ne déploie la recette.
- `deploy-prod.yml` — déclenchement manuel : promeut une image existante vers
  le VPS prod, après approbation.
- `security-weekly.yml` — scan OWASP hebdomadaire.

## GitHub Environments

Créer dans Settings → Environments :

### `staging`
Secrets : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY` (VPS staging).

### `production`
- Règle de protection : activer « Required reviewers » (1 approbateur minimum).
- Secrets : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY` (VPS prod),
  `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`.

## Préparation des VPS

Sur chaque VPS, dans `~/yadony/` :
- VPS staging : `docker-compose.staging.yml`, `nginx/nginx.staging.conf`,
  `nginx/certs/`, `nginx/www/`, `firebase-service-account.json`, `.env`.
- VPS prod : `docker-compose.prod.yml`, `nginx/nginx.conf`, `nginx/certs/`,
  `nginx/www/`, `firebase-service-account.json`, `.env`.

Le fichier `.env` contient les variables référencées par le Compose
(`DB_USERNAME`, `DB_PASSWORD`, clés Stripe, etc.). Staging utilise les clés
Stripe **test**, prod les clés **live**. Ne jamais committer ce fichier.

## Déployer en production

1. Vérifier que la version est validée en staging.
2. Actions → « Deploy Production » → « Run workflow ».
3. Saisir le tag : `staging` pour la dernière version, ou `sha-xxxxxxx` pour
   un rollback vers une version précise.
4. Approuver le déploiement quand GitHub le demande.

## Rollback

Relancer « Deploy Production » avec le `sha-xxxxxxx` de la version stable
précédente (visible dans l'onglet Packages du dépôt GitHub).

# GitHub Secrets Setup — Staging Deployment

Guide pour configurer les secrets GitHub nécessaires au déploiement automatisé sur le VPS staging.

---

## Prérequis

✅ Repo privé GitHub  
✅ VPS avec Docker et Docker Compose  
✅ SSH key Ed25519 pour accéder au VPS  
✅ PAT GitHub pour authentification GHCR  

---

## Étapes de Configuration

### 1. Créer l'Environment Staging

1. Aller à **GitHub Settings** → **Environments**
2. Cliquer sur **New environment**
3. Entrer le nom : `staging`
4. Laisser les autres options par défaut
5. Cliquer sur **Configure environment**

### 2. Ajouter les Secrets VPS

Dans l'environment `staging`, ajouter ces secrets :

#### 2.1 Accès VPS
```
OVH_HOST = 141.95.41.96
OVH_USER = debian
OVH_SSH_KEY = -----BEGIN OPENSSH PRIVATE KEY-----
              ... (contenu complet de ~/.ssh/id_ed25519)
              -----END OPENSSH PRIVATE KEY-----
```

**Comment récupérer la clé SSH :**
```bash
cat ~/.ssh/id_ed25519
```
Copier l'intégralité du contenu (y compris BEGIN et END).

#### 2.2 GitHub Container Registry (GHCR)
```
GHCR_USERNAME = (votre login GitHub)
GHCR_PAT = ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**Comment générer le PAT :**
1. GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token (classic)
3. Donner un nom : `yadony-cicd-ghcr`
4. Cocher les permissions : `write:packages`, `read:packages`
5. Générer et copier (⚠️ Secret — ne pas oublier !)

#### 2.3 Base de Données
```
DB_USERNAME = yadony
DB_PASSWORD = your_secure_password_here_min_16_chars
```

⚠️ **Important :** Utiliser un mot de passe sécurisé (min 16 caractères, alphanumériques + spéciaux).

#### 2.4 Encryption & Security
```
ENCRYPTION_KEY = (32 bytes en base64)
INTERNAL_SHARED_SECRET = your_internal_api_secret
```

**Générer une clé de 32 bytes :**
```bash
openssl rand -base64 32
```

Copier le résultat dans `ENCRYPTION_KEY`.

#### 2.5 Stripe (Test Keys)
```
STRIPE_SECRET_KEY = sk_test_xxxxxxxxxxxxxxxxxxxx
STRIPE_WEBHOOK_SECRET = whsec_xxxxxxxxxxxxxxxxxxxx
```

Récupérer depuis [Stripe Dashboard](https://dashboard.stripe.com) → API Keys.

#### 2.6 Google Places API
```
GOOGLE_PLACES_API_KEY = AIzaSyDxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Récupérer depuis [Google Cloud Console](https://console.cloud.google.com) → Places API → Credentials.

#### 2.7 Cloud Storage (Cloudflare R2)
```
AWS_S3_ENDPOINT = https://your-account.r2.cloudflarestorage.com
AWS_S3_REGION = auto
AWS_S3_BUCKET = yadony-staging
AWS_S3_ACCESS_KEY = xxxxxxxxxxxxxxxxxxxx
AWS_S3_SECRET_KEY = xxxxxxxxxxxxxxxxxxxx
```

Récupérer depuis [Cloudflare Dashboard](https://dash.cloudflare.com) → R2 → Token Settings.

#### 2.8 Monitoring (Sentry)
```
SENTRY_DSN = https://xxxx@sentry.io/xxxxx
```

Optionnel en staging. Récupérer depuis [Sentry Projects](https://sentry.io/organizations).

#### 2.9 Email Service (Resend)
```
RESEND_API_KEY = re_xxxxxxxxxxxxxxxxxxxxxxxx
```

Optionnel. Récupérer depuis [Resend API Keys](https://resend.com/api-tokens).

#### 2.10 Monitoring (Grafana Cloud)
```
GRAFANA_PROM_URL = https://prometheus-blocks-prod-us-central1.grafana.net/api/prom/push
GRAFANA_PROM_USER = xxxx
GRAFANA_LOKI_URL = https://logs-prod-us-central1.grafana.net/loki/api/v1/push
GRAFANA_LOKI_USER = yyyy
GRAFANA_CLOUD_TOKEN = zzzz
DISCORD_WEBHOOK_URL = https://discord.com/api/webhooks/xxxx/yyyy
```

Récupérer les variables Grafana depuis [Grafana Cloud](https://grafana.com/auth/sign-in/grafana-cloud).
Créer `DISCORD_WEBHOOK_URL` depuis les paramètres du salon Discord destiné aux
alertes de production. Ne jamais mettre cette URL dans GitHub Actions logs ou
dans un fichier committé.

#### 2.11 CORS
```
CORS_ALLOWED_ORIGINS = https://api-staging.yadony.com,http://localhost:3000
```

Domaines autorisés pour les requêtes CORS. Adapter selon votre config.

---

## Vérification des Secrets

```bash
# Lister les secrets (sans afficher les valeurs)
gh secret list --org YADONY
# ou
gh secret list --repo YADONY/yadony-back
```

Pour vérifier dans l'UI : **Settings → Secrets and variables → Actions → All secrets**

---

## Test du Déploiement

### Tester manuellement

```bash
# 1. Push vers une branche feature
git checkout -b test/deployment-check
git push origin test/deployment-check

# 2. Ouvrir une PR ou attendre que la CI passe
# 3. Observer les logs du workflow "Deploy Staging"
# 4. Accéder à l'API : https://api-staging.yadony.com/api/v1/actuator/health
```

### Logs du Workflow

1. Aller à **Actions** → **Deploy Staging**
2. Cliquer sur le dernier run
3. Voir les logs détaillés de chaque étape :
   - ✅ Validation des secrets
   - ✅ Création du `.env.staging`
   - ✅ Préparation VPS
   - ✅ Transfer `.env.staging`
   - ✅ Docker Compose up
   - ✅ Health checks DB & API
   - ✅ Rapport final

---

## Secrets Récapitulatif

| Secret | Source | Obligatoire | Notes |
|--------|--------|-------------|-------|
| `OVH_HOST` | VPS | ✅ | Adresse IP du VPS |
| `OVH_USER` | VPS | ✅ | Utilisateur SSH (debian) |
| `OVH_SSH_KEY` | ~/.ssh/id_ed25519 | ✅ | Clé privée SSH |
| `GHCR_USERNAME` | GitHub | ✅ | Login GitHub |
| `GHCR_PAT` | GitHub | ✅ | Token personnel GitHub |
| `DB_USERNAME` | Config | ✅ | Utilisateur BD (yadony) |
| `DB_PASSWORD` | Config | ✅ | Mot de passe BD |
| `ENCRYPTION_KEY` | `openssl rand -base64 32` | ✅ | Clé AES-256 |
| `STRIPE_SECRET_KEY` | Stripe | ✅ | Clé secrète Stripe |
| `STRIPE_WEBHOOK_SECRET` | Stripe | ✅ | Secret webhook Stripe |
| `GOOGLE_PLACES_API_KEY` | Google Cloud | ✅ | Clé API Places |
| `AWS_S3_*` | Cloudflare R2 | ✅ | Credentials S3 |
| `CORS_ALLOWED_ORIGINS` | Config | ✅ | Origins CORS |
| `SENTRY_DSN` | Sentry | ❌ | DSN Sentry (optionnel) |
| `RESEND_API_KEY` | Resend | ❌ | Clé API email (optionnel) |
| `GRAFANA_*` | Grafana Cloud | ✅ prod | Credentials métriques et logs |
| `DISCORD_WEBHOOK_URL` | Discord | ✅ prod | Webhook du salon d'alertes |
| `INTERNAL_SHARED_SECRET` | Config | ✅ | Secret interne API |

---

## Troubleshooting

### ❌ "Secret is missing" — Déploiement bloqué

```
Error: OVH_HOST secret is missing
```

**Solution :** Ajouter le secret manquant dans l'environment `staging`.

### ❌ SSH Connection Failed

```
ssh: connect to host 141.95.41.96 port 22: Connection refused
```

**Causes possibles :**
1. VPS hors ligne
2. Clé SSH incorrecte
3. Firewall bloquant port 22

**Vérification :**
```bash
ssh -i ~/.ssh/id_ed25519 debian@141.95.41.96 "echo OK"
```

### ❌ Docker Login Failed

```
error response from daemon: unauthorized: authentication required
```

**Causes :**
1. `GHCR_PAT` expiré ou invalide
2. `GHCR_USERNAME` incorrect

**Solution :** Générer un nouveau PAT.

### ❌ Health Check Failed

```
API not available after 60s
```

**Diagnostics sur le VPS :**
```bash
ssh debian@141.95.41.96

cd ~/yadony

# Vérifier les conteneurs
docker ps -a

# Logs de l'API
docker logs yadony_api | tail -50

# Vérifier la BD
docker exec yadony_db_staging pg_isready -U yadony
```

### ❌ Disk Space Issues

```
docker: Error response from daemon: failed to create shm directory: no space left on device
```

**Solution :**
```bash
ssh debian@141.95.41.96

docker system prune -a --volumes
df -h  # Vérifier l'espace disque
```

---

## Rotation des Secrets

**Recommandé chaque trimestre :**

1. **PAT GitHub**
   - Générer un nouveau token
   - Mettre à jour `GHCR_PAT`
   - Supprimer l'ancien token

2. **SSH Key**
   - Générer une nouvelle clé : `ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_new`
   - Ajouter la clé publique au VPS
   - Mettre à jour `OVH_SSH_KEY`

3. **Stripe Webhook Secret**
   - Révoquer et régénérer dans Stripe Dashboard

4. **DB Password**
   - Changer dans PostgreSQL sur le VPS
   - Mettre à jour dans les secrets GitHub

---

## Checklist Sécurité

- [ ] SSH key fichier local = 600 permissions (`chmod 600 ~/.ssh/id_ed25519`)
- [ ] Secrets jamais hardcodés dans le code
- [ ] Secrets jamais loggés dans les workflows (GitHub masque automatiquement)
- [ ] PAT GitHub = tokens classiques (plus sûrs que les nouveaux formats)
- [ ] VPS = firewall ouvert uniquement au port 22 (SSH) depuis votre IP
- [ ] Database = mot de passe min 16 chars avec caractères spéciaux
- [ ] CORS = domaines spécifiques (pas `*`)

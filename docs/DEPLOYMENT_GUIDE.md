# 📚 Guide Complet de Déploiement en Production

**Application:** Yadony Backend (Spring Boot 3.4.x)
**Date:** 2026-05-02  
**Status:** Documentation officielle

---

## 📑 Table des matières

1. [Sélection du VPS OVH](#-sélection-du-vps-ovh)
2. [Architecture Déploiement](#-architecture-déploiement)
3. [Setup Initial du Serveur](#-setup-initial-du-serveur)
4. [Configuration des Secrets](#-configuration-des-secrets)
5. [Déploiement Manual (Premier)](#-déploiement-manual-premier)
6. [Pipeline CI/CD Automatisé](#-pipeline-cicd-automatisé)
7. [Configuration Nginx](#-configuration-nginx)
8. [Monitoring & Logs](#-monitoring--logs)
9. [Rollback & Récupération](#-rollback--récupération)
10. [Renouvellement SSL](#-renouvellement-ssl)
11. [Maintenance Régulière](#-maintenance-régulière)
12. [Dépannage](#-dépannage)

---

## 🖥️ Sélection du VPS OVH

### Analyse des Besoins de yadony

```
Application:
├── Spring Boot API (Java 21) → gourmande en RAM
├── PostgreSQL 16 → base de données
├── Nginx → reverse proxy + rate limiting
├── Docker/Docker Compose → orchestration
├── Caffeine Cache → mémoire vive
└── Backups quotidiens → stockage

Ressources estimées:
├── CPU: 2-4 cores (Java parallélisé)
├── RAM: 8GB minimum (JVM + PostgreSQL + Nginx)
├── Stockage: 30GB SSD (base + backups 7j)
└── Bande passante: ~1-5 Mbps (petit trafic MVP)
```

### Recommandation OVH

**Offre recommandée : VPS PRO**

| Spécification | VPS Essential | VPS Advantage | **VPS Pro** ⭐ | VPS Elite |
|---|---|---|---|---|
| **Processeur** | 1 core | 2 cores | **4 cores** | 8 cores |
| **RAM** | 2GB | 4GB | **8GB** | 16GB |
| **Stockage** | 10GB SSD | 20GB SSD | **40GB SSD** | 200GB SSD |
| **Bande passante** | 250 Mbps | 500 Mbps | **1 Gbps** | 2 Gbps |
| **Prix/mois** | ~3€ | ~6€ | **~20-25€** | ~50€ |
| **Adapté pour?** | ❌ Trop petit | ⚠️ Très juste | ✅ Parfait | Overpower |

### Justification du VPS Pro

- **4 cores CPU:** Permet à Spring Boot (JVM avec GC threads) de fonctionner sans throttle
- **8GB RAM:** 
  - JVM (Spring Boot): 4-5GB
  - PostgreSQL: 2-3GB
  - Nginx + OS: 0.5-1GB
- **40GB SSD:** 
  - Base PostgreSQL: ~5-10GB
  - Backups 7 jours: ~20GB
  - Logs et cache: ~5GB
- **Prix raisonnable:** ~22€/mois pour une MVP en production

### Alternatives

- **Si budget limité:** VPS Advantage (4GB RAM) peut marcher mais borderline → risque d'OOM
- **Si trafic élevé:** VPS Elite (8 cores) pour scale horizontal à l'avance

---

## 🏗️ Architecture Déploiement

```
┌─────────────────────────────────────────────────────────┐
│                   VPS OVH (VPS Pro)                     │
│  ┌──────────────────────────────────────────────────┐   │
│  │        Docker Compose Services                   │   │
│  │                                                  │   │
│  │  ┌──────────────┐   ┌──────────────┐           │   │
│  │  │   Nginx      │   │  Spring API  │           │   │
│  │  │  (Port 80,   │──▶│  (Port 8080) │           │   │
│  │  │   443)       │   │  Healthcheck │           │   │
│  │  └──────────────┘   └──────────────┘           │   │
│  │      ▲                      │                    │   │
│  │      │                      ▼                    │   │
│  │  Rate Limiting         ┌──────────────┐        │   │
│  │  SSL/TLS              │  PostgreSQL   │        │   │
│  │  Headers              │  16 (Port:   │        │   │
│  │                       │  internal)   │        │   │
│  │                       └──────────────┘        │   │
│  │                              ▲                │   │
│  │                              │                │   │
│  │                       ┌──────────────┐        │   │
│  │                       │  db-backup   │        │   │
│  │                       │  (pg_dump    │        │   │
│  │                       │  daily)      │        │   │
│  │                       └──────────────┘        │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  Volumes:                                              │
│  ├── yadony_db_prod_data/ (PostgreSQL persistent)       │
│  ├── backups/ (7 jours d'historique)                  │
│  ├── nginx/certs/ (SSL certificates)                  │
│  └── .env (secrets via env vars)                      │
└─────────────────────────────────────────────────────────┘
         ▲
         │ GitHub Actions (CI/CD)
         │ ┌─────────────────────┐
         ├─▶ Build Docker Image  │
         │  Push ghcr.io         │
         │  SSH Deploy + Restart │
         │  Health Check         │
         └─────────────────────┘
```

---

## 📦 Setup Initial du Serveur

### Étape 1: Commander le VPS OVH

1. Aller sur **ovh.com**
2. VPS → VPS Pro
3. Choisir région (France si données EU)
4. Système d'exploitation: **Ubuntu 24.04 LTS**
5. Ajouter clé SSH pendant la création (optionnel mais recommandé)

**Temps d'activation:** ~5-10 minutes

### Étape 2: Configuration SSH

**Sur votre machine locale:**

```bash
# Générer une clé SSH si vous n'en avez pas
ssh-keygen -t ed25519 -f ~/.ssh/ovh_deploy -C "yadony-deploy"

# Copier la clé publique sur le serveur
ssh-copy-id -i ~/.ssh/ovh_deploy.pub root@<IP_OVH>

# Tester la connexion
ssh -i ~/.ssh/ovh_deploy root@<IP_OVH>
```

### Étape 3: Installation des Dépendances

**Sur le serveur (SSH):**

```bash
#!/bin/bash
set -e

echo "🔄 Mise à jour du système..."
sudo apt-get update && sudo apt-get upgrade -y

echo "📦 Installation des dépendances..."
sudo apt-get install -y \
  docker.io \
  docker-compose \
  git \
  curl \
  wget \
  htop \
  certbot \
  openssl

echo "👤 Configuration Docker pour utilisateur non-root..."
sudo usermod -aG docker $USER
newgrp docker

echo "📁 Création de la structure de dossiers..."
mkdir -p ~/yadony
mkdir -p ~/yadony/nginx/{certs,www}
mkdir -p ~/yadony/backups

chmod 755 ~/yadony/backups

echo "✅ Installation terminée!"
docker --version
docker-compose --version
```

### Étape 4: Cloner le Repository

```bash
cd ~
git clone https://github.com/votre-org/yadony-back.git yadony
cd yadony

# Copier le docker-compose.prod.yml depuis votre repo
# (Vous devez avoir ces fichiers en git)
```

### Étape 5: Créer le Fichier .env

```bash
cd ~/yadony

# Créer le fichier .env avec les secrets
cat > .env << 'EOF'
# ========== DATABASE ==========
DB_USERNAME=yadony_prod
DB_PASSWORD=$(openssl rand -base64 32)

# ========== STRIPE ==========
# Obtenir de: https://dashboard.stripe.com/apikeys
STRIPE_SECRET_KEY=sk_live_YOUR_KEY_HERE
STRIPE_WEBHOOK_SECRET=whsec_YOUR_SECRET_HERE

# ========== FIREBASE ==========
# Obtenir de: Firebase Console → Settings → Service Accounts
# Copier le JSON dans ./firebase-service-account.json

# ========== SENTRY ==========
# Obtenir de: https://sentry.io/settings/auth/tokens/
SENTRY_DSN=https://xxxxx@sentry.io/xxxxx

# ========== STOCKAGE OBJET (Cloudflare R2) ==========
# Obtenir de: Cloudflare Dashboard → R2 → Manage API Tokens
# Le code lit ces variables via aws.s3.* : le SDK AWS S3 pointe sur
# l'endpoint R2, il n'existe aucune propriété hetzner.*.
AWS_S3_ENDPOINT=https://<account_id>.r2.cloudflarestorage.com
AWS_S3_REGION=auto
AWS_S3_BUCKET=yadony-prod
AWS_S3_ACCESS_KEY=YOUR_ACCESS_KEY
AWS_S3_SECRET_KEY=YOUR_SECRET_KEY

# ========== JAVA SETTINGS ==========
# 75% de la RAM disponible (VPS Pro = 8GB → 6GB pour JVM)
JAVA_OPTS=-XX:MaxRAMPercentage=75.0

# ========== FIREBASE SERVICE ACCOUNT ==========
# À copier depuis Firebase Console (voir étape suivante)
EOF

# Sécuriser le fichier
chmod 600 .env

echo "✅ Fichier .env créé (à compléter avec les clés réelles)"
```

### Étape 6: Ajouter le Firebase Service Account

**Localement, obtenir le JSON:**

1. Firebase Console → Settings (⚙️) → Service Accounts
2. Cliquer "Generate New Private Key"
3. Sauvegarder le JSON

**Copier sur le serveur:**

```bash
# Depuis votre machine locale
scp -i ~/.ssh/ovh_deploy firebase-service-account.json root@<IP_OVH>:~/yadony/

# Vérifier
ssh -i ~/.ssh/ovh_deploy root@<IP_OVH> "ls -la ~/yadony/firebase-service-account.json"
```

### Étape 7: Générer le Certificat SSL (Let's Encrypt)

```bash
# Sur le serveur
cd ~/yadony

# Créer le fichier www si pas présent
mkdir -p nginx/www

# Générer le certificat (remplacer par votre domaine)
sudo certbot certonly --standalone \
  -d api.votredomaine.com \
  --email admin@votredomaine.com \
  --agree-tos \
  --non-interactive

# Copier vers dossier nginx
sudo cp /etc/letsencrypt/live/api.votredomaine.com/* nginx/certs/
sudo chown -R $USER:$USER nginx/certs

# Vérifier
ls -la nginx/certs/
```

---

## 🔐 Configuration des Secrets

### GitHub Secrets

1. **Repository Settings → Secrets and variables → Actions**

2. **Créer ces secrets:**

```
OVH_HOST
  Valeur: <IP_PUBLIQUE_VPS>
  Exemple: 149.202.123.45

OVH_USER
  Valeur: root (ou votre utilisateur sudo)

OVH_SSH_KEY
  Valeur: (contenu du fichier privé ~/.ssh/ovh_deploy)
  ⚠️ Inclure "-----BEGIN OPENSSH PRIVATE KEY-----" et "-----END OPENSSH PRIVATE KEY-----"

SENTRY_AUTH_TOKEN
  Valeur: (depuis https://sentry.io/settings/auth/tokens/)

SENTRY_ORG
  Valeur: votre-org (slug de l'organisation Sentry)

SENTRY_PROJECT
  Valeur: yadony-back (slug du projet)
```

**Comment obtenir la clé SSH privée pour GitHub:**

```bash
# Afficher le contenu de la clé privée
cat ~/.ssh/ovh_deploy

# Copier-coller tout dans GitHub Secrets OVH_SSH_KEY
```

### Variables d'environnement sur le Serveur

```bash
# Sur le serveur, éditer le .env
nano ~/yadony/.env

# Remplir avec les vraies valeurs:
# - STRIPE_SECRET_KEY (de Stripe Dashboard)
# - STRIPE_WEBHOOK_SECRET (de Stripe Dashboard → Webhooks)
# - SENTRY_DSN (de Sentry Project Settings)
# - AWS_S3_* (de Cloudflare Dashboard → R2)

# Sauvegarder (Ctrl+O, Enter, Ctrl+X)

# Vérifier
cat ~/yadony/.env
```

---

## 🚀 Déploiement Manual (Premier)

### Étape 1: Tester la Configuration

```bash
cd ~/yadony

# Valider docker-compose.prod.yml
docker compose -f docker-compose.prod.yml config --quiet
echo "✅ docker-compose.prod.yml valide"
```

### Étape 2: Créer les Volumes (première fois)

```bash
# Les volumes nommés sont créés automatiquement
docker compose -f docker-compose.prod.yml up -d db

# Attendre que PostgreSQL soit healthy
sleep 20
docker compose -f docker-compose.prod.yml ps
```

### Étape 3: Démarrer tous les services

```bash
# Démarrer API, backup, nginx
docker compose -f docker-compose.prod.yml up -d api db-backup nginx

# Vérifier le status
docker compose -f docker-compose.prod.yml ps
```

### Étape 4: Vérifier la Santé

```bash
# Test local (depuis le serveur)
curl -s http://localhost:8080/api/v1/actuator/health | jq .

# Ou via Nginx
curl -s https://api.votredomaine.com/api/v1/actuator/health | jq .

# Afficher les logs de l'API
docker compose -f docker-compose.prod.yml logs -f api --tail 50
```

### Étape 5: Valider

```bash
# Tous les services doivent être "Up"
docker compose -f docker-compose.prod.yml ps

# Exemple de sortie:
# NAME              STATUS      PORTS
# yadony_api          Up 5 minutes (healthy)
# yadony_db_prod      Up 5 minutes (healthy)
# yadony_db_backup    Up 5 minutes
# yadony_nginx        Up 5 minutes 0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp
```

---

## 🔄 Pipeline CI/CD Automatisé

### Comment ça fonctionne

```
1. Vous faites: git push → main
2. GitHub Actions:
   ├── Exécute CI (Quality Gates)
   │   ├── Tests unitaires
   │   ├── Tests intégration
   │   └── Vérification JaCoCo (couverture ≥ 90%)
   │
   ├── Si ✅ CI réussi, déclenche CD:
   │   ├── Build JAR (./mvnw package -DskipTests)
   │   ├── Build image Docker
   │   ├── Push sur ghcr.io
   │   │
   │   ├── SSH au serveur OVH
   │   ├── docker pull ghcr.io/yadony/yadony-back:latest
   │   ├── docker compose up -d --no-deps api
   │   │
   │   ├── Health check (retry 12× pendant 60s)
   │   ├── Si ✅: notification Sentry release
   │   └── Si ❌: l'API redémarre avec l'ancienne image
   │
   └── Si ❌ CI échoue: CD ne se lance PAS
```

### Vérifier le Workflow

```bash
# Sur GitHub:
1. Repository → Actions
2. Cliquer sur "CD — Build & Deploy"
3. Voir l'historique des exécutions

# Pour déclencher manuellement:
1. Actions → CD — Build & Deploy
2. "Run workflow" → Main branch → "Run workflow"
```

### Logs du Déploiement

```bash
# Sur le serveur, voir les logs en temps réel
docker compose -f docker-compose.prod.yml logs -f api

# Voir seulement les 100 dernières lignes
docker compose -f docker-compose.prod.yml logs --tail 100 api

# Voir les logs Nginx
docker exec yadony_nginx tail -f /var/log/nginx/access.log
```

---

## ⚙️ Configuration Nginx

### Créer nginx/nginx.conf

```nginx
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for"';

    access_log /var/log/nginx/access.log main;

    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 2048;
    client_max_body_size 20M;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=api_general:10m rate=30r/m;
    limit_req_zone $binary_remote_addr zone=api_auth:10m rate=5r/m;

    # Upstream
    resolver 127.0.0.11 valid=10s;
    set $backend http://api:8080;

    # HTTP to HTTPS redirect
    server {
        listen 80;
        server_name api.votredomaine.com;

        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }

        location / {
            return 301 https://$host$request_uri;
        }
    }

    # HTTPS server
    server {
        listen 443 ssl http2;
        server_name api.votredomaine.com;

        ssl_certificate /etc/letsencrypt/live/api.votredomaine.com/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/api.votredomaine.com/privkey.pem;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;
        ssl_prefer_server_ciphers on;

        # Security headers
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;

        # Health check (no rate limit)
        location /api/v1/actuator/health {
            proxy_pass $backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # Auth endpoints (strict rate limit)
        location ~ /api/v1/auth/ {
            limit_req zone=api_auth burst=5 nodelay;
            proxy_pass $backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # All other API endpoints
        location /api/v1/ {
            limit_req zone=api_general burst=10 nodelay;
            proxy_pass $backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_connect_timeout 60s;
            proxy_send_timeout 60s;
            proxy_read_timeout 60s;
        }

        # Tracking public endpoint
        location ~ /tracking/ {
            proxy_pass $backend;
            proxy_set_header Host $host;
        }
    }
}
```

---

## 📊 Monitoring & Logs

### Logs Applicatifs

```bash
# Logs temps réel (API)
docker compose -f docker-compose.prod.yml logs -f api

# Logs avec timestamps
docker compose -f docker-compose.prod.yml logs --timestamps api

# Logs depuis les 10 dernières minutes
docker compose -f docker-compose.prod.yml logs --since 10m api

# Logs Nginx
docker exec yadony_nginx tail -f /var/log/nginx/access.log
docker exec yadony_nginx tail -f /var/log/nginx/error.log
```

### Monitoring Système

```bash
# CPU/Mémoire des conteneurs
docker stats

# Espace disque
df -h /

# Vérifier les backups
ls -lah ~/yadony/backups/

# Derniers backups (5 plus récents)
ls -lah ~/yadony/backups/ | tail -6
```

### Sentry (Erreurs en Production)

1. **Accès:** https://sentry.io
2. **Projet:** yadony-back
3. **Issues:** liste de tous les errors/exceptions
4. **Releases:** historique des déploiements

### Health Check

```bash
# Test local
curl -s http://localhost:8080/api/v1/actuator/health | jq .

# Test via HTTPS public
curl -s https://api.votredomaine.com/api/v1/actuator/health | jq .

# Résultat attendu:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    ...
  }
}
```

---

## 🔄 Rollback & Récupération

### Scenario: La dernière version casse la prod

```bash
# 1. Vérifier l'état
docker compose -f docker-compose.prod.yml ps

# 2. Voir les logs de l'erreur
docker compose -f docker-compose.prod.yml logs api | tail -100

# 3. Récupérer l'image précédente
# Docker garde les dernières images — voir l'historique
docker images | grep yadony-back | head -5

# 4. Redémarrer avec l'image précédente (si nécessaire)
# Éditer docker-compose.prod.yml et changer l'image tag
nano docker-compose.prod.yml

# Changer:
# image: ghcr.io/yadony/yadony-back:latest
# En:
# image: ghcr.io/yadony/yadony-back:sha-xxxxxxx  # ancien commit

# 5. Redémarrer
docker compose -f docker-compose.prod.yml pull api
docker compose -f docker-compose.prod.yml up -d --no-deps api

# 6. Vérifier
curl -s https://api.votredomaine.com/api/v1/actuator/health
```

### Restaurer depuis Backup

```bash
# 1. Lister les backups disponibles
ls -la ~/yadony/backups/

# 2. Arrêter les services qui utilisent la DB
docker compose -f docker-compose.prod.yml down api db-backup

# 3. Vider la base actuelle
docker exec yadony_db_prod dropdb -U yadony_prod yadony_prod

# 4. Restaurer depuis le backup
gunzip -c ~/yadony/backups/backup_YYYYMMDD_HHMMSS.sql.gz | \
  docker exec -i yadony_db_prod psql -U yadony_prod yadony_prod

# 5. Redémarrer
docker compose -f docker-compose.prod.yml up -d
```

---

## 🔒 Renouvellement SSL

### Vérifier l'expiration

```bash
# Voir la date d'expiration
openssl x509 -enddate -noout -in ~/yadony/nginx/certs/fullchain.pem

# Résultat:
# notAfter=May  4 10:00:00 2027 GMT
```

### Renouveler Manuellement

```bash
# Certbot renouvelle automatiquement 30j avant expiration
# Mais si besoin manuel:
sudo certbot renew --force-renewal

# Copier les nouveaux certs dans nginx
sudo cp /etc/letsencrypt/live/api.votredomaine.com/* ~/yadony/nginx/certs/
sudo chown -R $USER:$USER ~/yadony/nginx/certs

# Recharger Nginx (sans downtime)
docker exec yadony_nginx nginx -s reload

# Vérifier
echo "SSL renouvellé" && date
```

### Automatiser la Vérification

```bash
# Ajouter un cron job (optionnel)
crontab -e

# Ajouter cette ligne:
0 3 1 * * sudo certbot renew --quiet && sudo cp /etc/letsencrypt/live/api.votredomaine.com/* ~/yadony/nginx/certs/ && docker exec yadony_nginx nginx -s reload

# Sauvegarder (Ctrl+O, Enter, Ctrl+X)
```

---

## 🛠️ Maintenance Régulière

### Daily

```bash
# Vérifier les logs d'erreurs
docker compose -f docker-compose.prod.yml logs api 2>&1 | grep -i error | wc -l

# Vérifier la santé
curl -s https://api.votredomaine.com/api/v1/actuator/health | jq .status
```

### Weekly

```bash
# Vérifier l'espace disque
df -h /

# Vérifier les backups
ls -lah ~/yadony/backups/ | wc -l
echo "Backups: $(ls ~/yadony/backups/ | wc -l) fichiers"

# Nettoyer les images Docker inutilisées
docker image prune -f
```

### Monthly

```bash
# Mettre à jour le système
sudo apt-get update && sudo apt-get upgrade -y

# Vérifier l'expiration SSL
openssl x509 -enddate -noout -in ~/yadony/nginx/certs/fullchain.pem

# Revoir les logs Sentry pour tendances
# https://sentry.io → Releases
```

---

## 🐛 Dépannage

### L'API ne démarre pas

```bash
# Voir les logs détaillés
docker compose -f docker-compose.prod.yml logs api --tail 200

# Problèmes courants:

# 1. Erreur de connexion BD
# "Cannot get a connection"
# → Vérifier: DB_USERNAME, DB_PASSWORD dans .env
# → Vérifier: PostgreSQL est Up (docker compose ps)
docker compose -f docker-compose.prod.yml logs db

# 2. Erreur Firebase
# "Failed to verify ID token"
# → Vérifier: firebase-service-account.json existe et est valide
ls -la ~/yadony/firebase-service-account.json

# 3. Erreur mémoire (OutOfMemory)
# "java.lang.OutOfMemoryError"
# → Augmenter la RAM allouée au JVM dans docker-compose.prod.yml
# Éditer: environment.JAVA_OPTS
```

### PostgreSQL ne démarre pas

```bash
# Vérifier les logs
docker compose -f docker-compose.prod.yml logs db

# Réinitialiser la base (⚠️ DESTRUCTEUR)
docker compose -f docker-compose.prod.yml down -v
docker volume rm yadony_db_prod_data

# Redémarrer
docker compose -f docker-compose.prod.yml up -d db
```

### Nginx ne charge pas les certs

```bash
# Vérifier les fichiers existent
ls -la ~/yadony/nginx/certs/

# Vérifier les permissions
chmod 644 ~/yadony/nginx/certs/fullchain.pem
chmod 644 ~/yadony/nginx/certs/privkey.pem
chmod 755 ~/yadony/nginx/certs/

# Recharger Nginx
docker exec yadony_nginx nginx -s reload

# Vérifier syntaxe
docker exec yadony_nginx nginx -t
```

### Backups ne se créent pas

```bash
# Vérifier les logs du service backup
docker compose -f docker-compose.prod.yml logs db-backup

# Tester manuellement la commande pg_dump
docker exec yadony_db_backup pg_dump -h db -U yadony_prod yadony_prod | gzip > /tmp/test.sql.gz

# Vérifier les permissions
ls -la ~/yadony/backups/
chmod 755 ~/yadony/backups/
```

### Problèmes de Rate Limiting

```bash
# Tester le rate limiting
for i in {1..50}; do curl https://api.votredomaine.com/api/v1/auth/login; done

# Voir les 429 (Too Many Requests)
docker exec yadony_nginx tail -f /var/log/nginx/access.log | grep 429

# Augmenter les limites si besoin (dans nginx.conf):
# rate=30r/m  →  rate=60r/m  (pour API générale)
# rate=5r/m   →  rate=10r/m  (pour auth)
```

---

## 📋 Checklist Final

### Avant le Premier Déploiement

- [ ] VPS OVH commandé et activé
- [ ] SSH configuré et accessible
- [ ] Docker et Docker Compose installés
- [ ] Repository cloné (`git clone`)
- [ ] Certificat SSL généré (Let's Encrypt)
- [ ] Fichier `.env` créé avec tous les secrets
- [ ] Firebase `service-account.json` copié
- [ ] Docker images testées localement
- [ ] `docker-compose.prod.yml` valide (`docker compose config`)
- [ ] Nginx configuré avec le bon domaine

### Après le Premier Déploiement

- [ ] Tous les services Up (`docker compose ps`)
- [ ] Health check répond ✅
- [ ] HTTPS fonctionne (pas d'avertissement SSL)
- [ ] Logs sans erreurs (`docker logs api`)
- [ ] Sentry enregistre les releases
- [ ] Backups se créent quotidiennement
- [ ] GitHub Secrets configurés pour CI/CD
- [ ] Test: `git push` → déploiement automatique

### Production Ongoing

- [ ] Monitoring Sentry actif
- [ ] Alertes configurées (erreurs, performance)
- [ ] Rotation des logs mise en place
- [ ] Backup automatique tous les jours à 02:00 UTC
- [ ] Plan de rollback documenté
- [ ] Équipe au courant de la procédure

---

## 📞 Support & Ressources

**OVH:**
- Support: https://help.ovhcloud.com
- Manager: https://www.ovh.com/manager/web/

**Docker:**
- Docs: https://docs.docker.com
- Compose: https://docs.docker.com/compose

**Certbot (SSL):**
- Docs: https://certbot.eff.org

**Sentry:**
- Dashboard: https://sentry.io

**Stripe:**
- Webhooks: https://dashboard.stripe.com/webhooks

---

**Date: 2026-05-02**  
**Version: 1.0**  
**Mainteneur: Équipe yadony**

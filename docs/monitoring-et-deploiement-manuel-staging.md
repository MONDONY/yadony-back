# Monitoring & déploiement manuel — Staging

> Stack d'observabilité du staging (Prometheus + Grafana + Alloy, **sans Loki**) et
> procédure de déploiement **manuel par SSH** quand GitHub Actions est indisponible
> (facturation bloquée, quota épuisé, panne).

VPS staging : `141.95.41.96` · utilisateur `debian` · dossier projet `~/yadony` (`/home/debian/yadony`).

---

## 1. Accès rapides (liens à taper)

| Service | URL | Identifiants |
|---|---|---|
| **Grafana** (dashboards) | http://141.95.41.96:3000 | `admin` / `admin` |
| **Prometheus** (requêtes brutes) | http://141.95.41.96:9090 | — |
| Prometheus — cibles scrappées | http://141.95.41.96:9090/targets | — |
| API staging | https://api-staging.yadony.com/api/v1 | Firebase |
| Healthcheck API (public) | https://api-staging.yadony.com/api/v1/actuator/health | — |

> L'endpoint métriques `/api/v1/actuator/prometheus` n'est **pas** exposé publiquement
> (Nginx renvoie 404 sur `/api/v1/actuator/*` sauf `/health`, et le port 8080 n'est pas
> publié sur l'hôte). Il n'est joignable que par les conteneurs du réseau `yadony_internal`.

---

## 2. Architecture du monitoring

```
                    ┌──────────────┐
   API Spring Boot  │  yadony_api    │  /api/v1/actuator/prometheus
   (réseau interne) └──────┬───────┘
                           │ scrape direct (pull, 30s)
                           ▼
                    ┌──────────────┐        ┌──────────────┐
                    │ yadony_prometheus │◀────│  yadony_alloy  │ remote_write
                    │  (TSDB 30j)  │        │ (node_* hôte)│ (CPU/RAM/disque/réseau)
                    └──────┬───────┘        └──────────────┘
                           │ datasource
                           ▼
                    ┌──────────────┐
                    │ yadony_grafana │  6 dashboards provisionnés
                    └──────────────┘
```

- **Prometheus** scrape directement l'API (`api:8080`) et reçoit les métriques de l'hôte
  poussées par **Alloy** via `remote_write` (flag `--web.enable-remote-write-receiver`).
- **Alloy** ne collecte que les métriques système (`node_*`) ; il ne scrape plus l'API
  (évite les doublons) et ne fait plus de collecte de logs (Loki retiré).
- **Grafana** lit Prometheus (datasource `uid=prometheus`) et provisionne 6 dashboards
  depuis des fichiers.

### Fichiers de configuration (dans le repo)

| Fichier | Rôle |
|---|---|
| `docker-compose.staging.yml` | Orchestration de tous les services |
| `monitoring/prometheus/prometheus.yml` | Scrape de l'API + réception remote_write |
| `monitoring/alloy/config.alloy` | Collecte métriques hôte → Prometheus |
| `monitoring/grafana/provisioning/datasources/prometheus.yml` | Datasource Prometheus |
| `monitoring/grafana/provisioning/dashboards/*.json` | Les 6 dashboards |

### Les 6 dashboards

| UID | Titre | Contenu |
|---|---|---|
| `yadony-overview` | Vue Globale | Statut API, req/s, latence, CPU/RAM/disque |
| `yadony-api-http` | API HTTP | Débit, erreurs 4xx/5xx, latence p50/p95/p99, top endpoints |
| `yadony-jvm` | JVM | Mémoire heap/non-heap, GC, threads, CPU process |
| `yadony-database` | Base de données (HikariCP) | Connexions actives/idle/pending, temps d'acquisition |
| `yadony-host-infra` | Hôte & Infrastructure | CPU par mode, load, mémoire, disque, réseau, I/O |
| `yadony-logs-errors` | Logs & Erreurs | Volume de logs par niveau (`logback_events_total`) |

---

## 3. Déploiement normal (CI/CD GitHub)

Flux automatique quand GitHub Actions fonctionne :

1. Push sur une branche `staging/**` (en général `git push origin origin/main:refs/heads/staging/main`)
   → workflow **CI** (`.github/workflows/ci.yml`). Les branches de travail n'ont de CI que par leur PR.
2. À la réussite de cette CI → workflow **Deploy Staging** (`.github/workflows/deploy-staging.yml`).
3. Le déploiement : build + push de l'image vers GHCR, transfert du compose + `monitoring/`
   sur le VPS, puis `docker compose up -d` de **tous** les services.

> ⚠️ Pour un trigger `workflow_run`, GitHub exécute toujours le `deploy-staging.yml` de la
> **branche par défaut (`main`)**. Un correctif du workflow ne prend effet qu'une fois mergé
> sur `main`.

---

## 4. Déploiement MANUEL par SSH (GitHub indisponible)

À utiliser si la facturation Actions est bloquée, le quota épuisé, ou en cas de panne CI.

**Prérequis (machine locale) :** Docker installé, accès SSH au VPS (clé configurée),
être sur une branche à jour avec `main` (qui contient le correctif `permitAll` sur
`/actuator/prometheus`, indispensable au scrape des métriques).

> 🚫 **NE JAMAIS transférer `.env.staging` vers le VPS.** Le `.env.staging` du VPS contient
> les **vrais secrets** (générés depuis les GitHub Secrets / saisis manuellement). Le
> `.env.staging` local du repo contient des **placeholders**. L'écraser casse Stripe, S3,
> Resend et l'auth DB.

### Étape 1 — Build de l'image API

```bash
cd yadony-back
docker build -t ghcr.io/yadony/yadony-back:staging .
```

### Étape 2 — Export + transfert de l'image vers le VPS

```bash
docker save ghcr.io/yadony/yadony-back:staging | gzip -1 > /tmp/yadony-api-staging.tar.gz
scp /tmp/yadony-api-staging.tar.gz debian@141.95.41.96:/tmp/
```

### Étape 3 — Chargement de l'image sur le VPS

```bash
ssh debian@141.95.41.96 'gunzip -c /tmp/yadony-api-staging.tar.gz | docker load && rm -f /tmp/yadony-api-staging.tar.gz'
```

### Étape 4 — Transfert de la config (compose + monitoring + nginx)

Le dossier `monitoring/` peut contenir des fichiers appartenant à `root` (anciens conteneurs) :
on le supprime via un conteneur jetable root avant de le recopier.

```bash
ssh debian@141.95.41.96 'cd ~/yadony && [ -d monitoring ] && docker run --rm -v "$PWD:/work" alpine rm -rf /work/monitoring'

scp docker-compose.staging.yml debian@141.95.41.96:~/yadony/
scp nginx/nginx.staging.conf  debian@141.95.41.96:~/yadony/nginx/
scp -r monitoring             debian@141.95.41.96:~/yadony/
```

### Étape 5 — Démarrage de tous les services

```bash
ssh debian@141.95.41.96 'cd ~/yadony && docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --remove-orphans'
```

### Étape 6 — Vérifications

```bash
# État des conteneurs (10 attendus, sans Loki)
ssh debian@141.95.41.96 'docker ps --format "table {{.Names}}\t{{.Status}}" | grep yadony_'

# Prometheus scrape-t-il l'API ? (doit renvoyer "1")
ssh debian@141.95.41.96 'docker exec yadony_prometheus wget -qO- "http://localhost:9090/api/v1/query?query=up{job=\"yadony-api\"}" | grep -o "\"value\":\[[^]]*\]"'

# Dashboards chargés dans Grafana ?
ssh debian@141.95.41.96 'docker exec yadony_grafana wget -qO- "http://admin:admin@localhost:3000/api/search?type=dash-db" | grep -o "\"title\":\"[^\"]*\""'

# Logs stripe-cli (doit afficher "Ready! ... webhook signing secret is whsec_...")
ssh debian@141.95.41.96 'docker logs yadony_stripe_cli_payments --tail 5'
```

---

## 5. Dépannage (problèmes rencontrés et solutions)

### `rm: cannot remove 'monitoring/...': Permission denied`
Des fichiers de `monitoring/` appartiennent à `root` (un conteneur tournait en `user: root`).
**Solution :** supprimer via un conteneur root.
```bash
ssh debian@141.95.41.96 'cd ~/yadony && docker run --rm -v "$PWD:/work" alpine rm -rf /work/monitoring'
```

### API `unhealthy` + logs `password authentication failed for user "yadony"`
Le mot de passe dans `.env.staging` ne correspond pas à celui avec lequel le **volume Postgres**
a été initialisé (le volume garde le mot de passe d'origine, même après recréation du conteneur).
**Solution :** réaligner le mot de passe Postgres sur celui du `.env.staging` (non destructif,
garde les données) :
```bash
ssh debian@141.95.41.96 'bash -s' <<'EOF'
cd ~/yadony
PW=$(grep "^DB_PASSWORD=" .env.staging | cut -d= -f2-)
PWE=${PW//\'/\'\'}
printf "ALTER USER yadony WITH PASSWORD '%s';\n" "$PWE" | docker exec -i yadony_db_staging psql -U yadony -d yadony_staging
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --force-recreate api
EOF
```

### Prometheus : cible `yadony-api` à `up=0` (DOWN)
L'image API qui tourne ne contient pas le correctif `permitAll` sur `/actuator/prometheus`
→ Prometheus reçoit un `401`. **Solution :** rebuilder et recharger l'image (étapes 1→3 ci-dessus)
à partir d'une branche contenant le correctif (présent sur `main`).

### Grafana redémarre en boucle : `Datasource provisioning error: data source not found`
État obsolète dans le volume Grafana (ancienne datasource Loki). Comme tout est provisionné
depuis des fichiers, on réinitialise le volume (aucune perte) :
```bash
ssh debian@141.95.41.96 'bash -s' <<'EOF'
cd ~/yadony
docker rm -f yadony_grafana
docker volume rm yadony_yadony_grafana_data
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d grafana
EOF
```

### Webhooks Stripe non validés (signature)
`stripe listen` génère son **propre** secret de signature (`whsec_...`, visible dans
`docker logs yadony_stripe_cli_payments`). L'API valide avec `STRIPE_WEBHOOK_SECRET`.
Pour que les webhooks forwardés par stripe-cli soient acceptés, mettre dans `~/yadony/.env.staging` :
```
STRIPE_WEBHOOK_SECRET=whsec_<valeur affichée par stripe-cli>
```
puis recréer l'API :
```bash
ssh debian@141.95.41.96 'cd ~/yadony && docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --force-recreate api'
```

---

## 6. Commandes utiles (sur le VPS)

```bash
cd ~/yadony

# Voir l'état + les logs
docker ps
docker logs yadony_api --tail 50
docker logs yadony_grafana --tail 50

# Redémarrer un seul service
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d --force-recreate <service>

# Tout arrêter / relancer
docker compose --env-file .env.staging -f docker-compose.staging.yml down
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d

# Requête Prometheus rapide
docker exec yadony_prometheus wget -qO- "http://localhost:9090/api/v1/query?query=<promql>"
```

Services : `api`, `db`, `db-backup`, `nginx`, `certbot`, `prometheus`, `grafana`, `alloy`,
`stripe-cli-payments`, `stripe-cli-kyc`.

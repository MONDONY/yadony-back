# Load test STAGING (VPS) — runbook

Objectif : mesurer la **vraie capacité** du backend yadony sur staging (pas le laptop),
et estimer s'il tient une forte concurrence (vers 10 000 users).

> **Rappel honnête** : 1 instance backend + 1 Postgres (pool 10) **ne tiendra pas
> 10 000 requêtes vraiment simultanées**. Ce runbook sert à (a) mesurer le ceiling
> réel d'une instance, (b) vérifier la dégradation gracieuse, (c) dimensionner le
> scaling horizontal nécessaire. Pour 10k concurrents : plusieurs instances derrière
> le load balancer + Postgres managé + PgBouncer + Redis (voir § Verdict).

---

## 0. Pré-requis

- `k6` installé sur la machine qui génère la charge (`brew install k6` / paquet Linux).
- `python3` (pour l'inventaire et le rapport).
- Accès au VPS staging.

---

## 1. Où lancer k6 ? (crucial)

| Option | Réaliste ? | Note |
|--------|-----------|------|
| k6 sur une **machine séparée** du backend | ✅ le mieux | Pas de contention CPU avec le backend |
| k6 sur le **VPS** (même machine que le backend) | ⚠️ acceptable | Co-localisé → k6 vole du CPU au backend, latences gonflées. OK si le VPS a des cœurs en rab. |
| k6 cloud / distribué | ✅ pour gros volumes | Plusieurs IP sources (utile face au rate-limit per-IP) |

**Ne jamais** conclure sur la capacité prod si k6 tourne sur la même machine que le backend.

---

## 2. Bypass du rate-limit nginx (obligatoire pour un test de capacité)

nginx staging limite **120 req/min/IP** (`api_general`, burst 60) et **30 req/min/IP**
(`api_sensitive` = `/auth`, `/kyc`, burst 15), cf. `nginx/nginx.conf`. Un test depuis une seule IP via
`https://api-staging.yadony.com` est donc **429** en quelques secondes — on
mesurerait le rate-limiter, pas le backend.

Trois façons de tester la capacité réelle :

**A. Backend direct (recommandé pour mesurer l'instance)**
Le conteneur `yadony_api` écoute `:8080` (interne au réseau docker, non publié).
Sur le VPS :
```bash
# publier temporairement 8080 sur localhost (ne PAS laisser en prod)
docker run ... -p 127.0.0.1:8080:8080 ...   # ou ajouter le mapping au compose
# puis BASE_URL=http://localhost:8080/api/v1
```
ou lancer k6 dans le réseau docker staging avec `BASE_URL=http://api:8080/api/v1`.

**B. Lever le rate-limit le temps du test (mesure le chemin réel nginx→backend)**
Dans `nginx/nginx.staging.conf`, commenter les `limit_req zone=...` (lignes ~62 et
~73), recharger nginx (`docker exec yadony_nginx nginx -s reload`), tester, puis
**remettre** les limites. C'est le test le plus représentatif (édge inclus).

**C. Charge distribuée multi-IP** (k6 cloud) — chaque IP a son quota 120 r/m. Pour
simuler 10k vrais users (= 10k IP), le rate-limit per-IP n'est PAS un blocage ; il
ne gêne que les tests mono-source.

> En prod, le rate-limit per-IP ne bloque PAS 10k users distincts (chacun a son IP).
> Il ne pénalise que les load tests à source unique.

---

## 3. Obtenir un token staging

`/dev/token` est `@Profile("dev")` → absent sur staging. Choisis :

1. **Activer /dev temporairement sur staging** (le plus simple, à retirer après) :
   passer `DevTokenController` en `@Profile({"dev","staging"})`, redéployer, minter,
   re-désactiver. ⚠️ expose un mint de token — à retirer impérativement après le test.
2. **Minter hors-ligne** via le service account Firebase staging : créer un custom
   token (Admin SDK) puis l'échanger contre un ID token via
   `identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=<WEB_API_KEY>`.
3. **Réutiliser un vrai token** d'un compte de test staging (depuis l'app), collé
   dans `K6_ID_TOKEN`. Validité ~1h → re-minter pour un soak long.

Le token va dans `staging.env` → `K6_ID_TOKEN=...`.

---

## 4. Lancer

```bash
cp load-test/staging.env.example load-test/staging.env
# éditer staging.env : BASE_URL (backend direct de préférence), K6_ID_TOKEN, profil
bash load-test/run_staging.sh
```

**Trouver le point de rupture** (ramp vers le ceiling) — dans `staging.env` :
```bash
READ_STAGES=200:1m,1000:2m,3000:2m,5000:3m,8000:3m
```
k6 monte par paliers ; on regarde à quel palier p95 explose / les 5xx apparaissent.

---

## 5. Quoi surveiller pendant le run (sur le VPS)

| Signal | Outil | Ce qu'il révèle |
|--------|-------|-----------------|
| 5xx / `server_errors` | rapport k6 | saturation (pool, threads, OOM) |
| p95 / p99 latence | rapport k6 | où la dégradation décolle |
| Connexions Postgres | `SELECT count(*) FROM pg_stat_activity` | pool saturé (max 10) → file d'attente |
| `hikaricp.connections.pending` | `/actuator/metrics` | requêtes en attente d'une connexion DB |
| CPU/mém conteneur `yadony_api` | `docker stats` | le backend est-il CPU/mém-bound |
| Threads Tomcat actifs | `/actuator/metrics/tomcat.threads.busy` | plafond à 200 par défaut |
| 429 | rapport k6 | rate-limit nginx pas bypassé |

---

## 6. Lire les résultats

- **0 5xx + latence bornée** au palier N VUs → l'instance tient N concurrents.
- **p95 décolle sans 5xx** → file d'attente (pool DB 10 ou threads Tomcat 200). C'est
  le ceiling d'**une** instance — pas une panne.
- **5xx / connection-timeout** → vrai point de rupture (pool DB ou Postgres
  `max_connections=100` épuisé, ou OOM).
- Compare `count(pg_stat_activity)` au max-pool : s'il plafonne à 10 pendant que p95
  monte → la DB est le goulot → augmenter le pool **et** mettre PgBouncer.

---

## 7. Verdict 10 000 concurrents — ce qu'il faut

Le code est **stateless** (auth Firebase par requête) → scalable horizontalement.
Le blocage est infra, pas applicatif. Pour 10k concurrents :

- **N instances backend** derrière le load balancer (nginx/HAProxy). 1 instance ≈
  quelques centaines de concurrents → viser ~20-50 instances selon le mix.
- **Postgres managé** + **PgBouncer** (multiplexe des milliers de connexions client
  sur un petit pool serveur) — `max_connections=100` est un plafond dur sinon.
- **Read replicas** si lecture-intensif (le feed annonces/demandes l'est).
- **Redis** comme cache partagé (Caffeine est par-instance → pas mutualisé en
  multi-instance).
- **CDN/edge** pour le statique et le cache des lectures publiques.

Le seul moyen de *prouver* 10k : ce harness pointé sur cette archi-là, charge
générée depuis plusieurs machines, ramp jusqu'à 10k.

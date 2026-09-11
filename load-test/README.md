# yadony k6 Load Tests

P2P marketplace load-test suite using [k6](https://k6.io/).

---

## Prerequisites

### 1. Install k6

```bash
# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Docker alternative
docker pull grafana/k6
```

### 2. Required environment variables

Export these before running any test:

```bash
# MANDATORY — target server (staging or dev ONLY, see warning below)
export BASE_URL="https://staging.api.example.com/api/v1"

# Firebase credentials for the test account
export FIREBASE_API_KEY="AIza..."
export K6_TEST_EMAIL="loadtest@yadony-staging.example.com"
export K6_TEST_PASSWORD="..."

# Optional — enables the idempotent write cycle in favorites.js
# Set to a trip ID that exists in the staging database.
# WARNING: FAV_TRIP_ID must NOT be owned by the test account.
# FavoriteService rejects favoriting your own trip with 422 — if the test
# account is the traveler who created this trip, PUT /favorites/trip/{id}
# returns 422, the 'PUT favorite 200' check fails, and the
# http_req_failed threshold is breached (whole run fails).
# Use a trip created by a different account in staging.
export FAV_TRIP_ID="some-uuid-here"
```

### 3. Test account role — IMPORTANT

The test account (identified by `K6_TEST_EMAIL`) **MUST have `ROLE_TRAVELER`** assigned in the staging database.

Without `ROLE_TRAVELER`:
- `GET /favorites/package-requests` → 403 Forbidden
- `GET /package-requests` → 403 Forbidden
- All checks against these endpoints will silently fail (k6 records them as errors, not test failures)

To assign the role on staging:
```sql
INSERT INTO user_roles (user_id, role)
SELECT id, 'ROLE_TRAVELER' FROM users WHERE email = 'loadtest@yadony-staging.example.com'
ON CONFLICT DO NOTHING;
```

---

## Running the tests

### Full suite (all scenarios, sequential)

```bash
bash load-test/run.sh
```

The runner executes `read_endpoints.js` then `favorites.js` and writes a summary to `load-test/reports/load-report.md`.

### Single scenario

```bash
k6 run --env BASE_URL="${BASE_URL}" \
        --env FIREBASE_API_KEY="${FIREBASE_API_KEY}" \
        --env K6_TEST_EMAIL="${K6_TEST_EMAIL}" \
        --env K6_TEST_PASSWORD="${K6_TEST_PASSWORD}" \
        load-test/scenarios/favorites.js
```

### Single profile within a scenario

To run a single profile, comment out the other scenario blocks in the file (e.g. keep only `smoke` and remove `load`, `stress`, `soak`). Alternatively, run the smoke-only `read_endpoints.js` to validate endpoints without the full profile suite.

---

## Scenarios

### `read_endpoints.js`

Exercises the main read-only endpoints:

| Endpoint | Notes |
|----------|-------|
| `GET /announcements?departureCity=Paris&arrivalCity=Dakar` | Auth required (Bearer token sent) |
| `GET /favorites/ids` | Auth required |
| `GET /package-requests` | `ROLE_TRAVELER` required |
| `GET /auth/me` | Any authenticated user |
| `GET /cities/search?q=Par&limit=10` | `ROLE_SENDER` or `ROLE_TRAVELER`. Param is `q` (not `query`), `limit` clamped to [1, 15]. Served by the `city-search` Caffeine cache (30 min TTL) after the first hit |
| `GET /cities/corridors/popular?limit=10` | `ROLE_SENDER` or `ROLE_TRAVELER`. `limit` clamped to [1, 20]. Served by the `popular-corridors` cache (1 min TTL) |
| `GET /notifications/unread-count` | Any authenticated, non-guest user |

Profiles: **smoke** (1 VU / 30 s) + **load** (ramp 0→50 VUs).

The two `/cities` endpoints are cached: with a fixed query (`Par`) every VU hits the same
cache entry, so their latency measures the cache path, not Postgres. Check the hit ratio
(`cache_gets_total{cache="city-search"}` / `cache="popular-corridors"`) during the run to
confirm the cache is actually doing the work.

### `favorites.js`

Exercises the favorites feature with read + idempotent write cycle:

| Operation | Notes |
|-----------|-------|
| `GET /favorites/trips` | Auth required |
| `GET /favorites/package-requests` | `ROLE_TRAVELER` required |
| `PUT /favorites/trip/{FAV_TRIP_ID}` | Only when `FAV_TRIP_ID` is set |
| `DELETE /favorites/trip/{FAV_TRIP_ID}` | Only when `FAV_TRIP_ID` is set |

The PUT+DELETE pair is **idempotent and non-destructive** — it adds then immediately removes the favorite, leaving the database in the same state as before.

#### Load profiles (sequential, via `startTime` offsets)

| Profile | Start | VUs | Duration | Purpose |
|---------|-------|-----|----------|---------|
| smoke   | 0 s   | 1   | 30 s     | Baseline — confirm endpoints respond |
| load    | 31 s  | 0→50 | ~9 min  | Normal traffic simulation |
| stress  | 10 min | 0→200 | 5 min | Find breaking point |
| soak    | 16 min | 50 | 15 min | Detect memory leaks / degradation |

---

## Reports

After `run.sh` completes, check `load-test/reports/`:

- `read_endpoints.json` — raw k6 summary for read_endpoints
- `favorites.json` — raw k6 summary for favorites
- `load-report.md` — aggregated table: p95 / p99 / RPS / error rate per scenario

---

## Thresholds (from `lib/thresholds.js`)

| Metric | Threshold |
|--------|-----------|
| `http_req_duration p(95)` | < 800 ms |
| `http_req_duration p(99)` | < 1 500 ms |
| `http_req_failed rate` | < 1 % |

k6 exits with a non-zero code if any threshold is breached.

---

## WARNING — NEVER run against production

**NEVER point `BASE_URL` at `https://api.yadony.app`.**

The runner (`run.sh`) refuses to execute if `BASE_URL` contains `api.yadony.app` or is empty. This guard protects real users and production data. Load tests must only target **staging** or a local dev environment.

---

## Nginx rate limits (per client IP)

From `nginx/nginx.conf`, identical on staging and production:

| Zone | Paths | Rate | Burst |
|------|-------|------|-------|
| `api_general` | everything under `/api/v1/`, including `/auth/me`, `/auth/me/**` and `/auth/me/fcm-token` | 120 req/min | 60 (`nodelay`) |
| `api_sensitive` | `/api/v1/auth/**` (except the `/auth/me*` paths above) and `/api/v1/kyc/**`, except the Didit webhook | 30 req/min | 15 (`nodelay`) |

Excess requests get a `429`. These limits protect individual clients; they say nothing
about aggregate capacity. Every scenario here is far above 120 req/min from a single IP:
the `load` profile alone is 50 VUs looping over 7 endpoints with a 1 s pause, roughly
300 req/s. Through the public hostname, k6 would be measuring the rate limiter, not the
backend, within seconds. Follow `STAGING.md` (bypass the edge, or run from several IPs).

The token comes from `K6_ID_TOKEN` or from a Firebase login (`lib/auth.js`), never from
the API, so the setup phase does not consume the `api_sensitive` budget.

## Production gates

Do not raise real traffic (store rollout, campaign) until these signals have been green
for at least 15 minutes under the target load on staging:

- API availability: `up{job="yadony-api"} == 1`.
- 5xx rate below 1 % over 5 minutes.
- p95 latency below 800 ms and p99 below 1.5 s (the k6 thresholds above).
- Hikari active connections below 80 % of `maximum-pool-size` (10 in production).
- JVM heap below 80 %, no sustained increase in GC pause time.
- Cache hit ratio: `cache_gets_total{result="hit"}` versus `result="miss"` for
  `city-search`, `popular-corridors`, `bids-me`, `traveler-bids-me` and `negotiations-me`.
  Counters only move for caches built with `recordStats()` (see `CacheConfig`).
- Every request carries an `X-Request-Id` response header (`RequestCorrelationFilter`);
  when a 5xx shows up in the report, that id links the k6 log line, the API log line and
  the Sentry event.

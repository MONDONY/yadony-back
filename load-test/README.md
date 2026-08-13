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
| `GET /cities/search?query=Par&limit=10` | Cached public city search |
| `GET /cities/corridors/popular?limit=10` | Short-TTL cached corridor list |
| `GET /notifications/unread-count` | Authenticated notification counter |

Profiles: **smoke** (1 VU / 30 s) + **load** (ramp 0→50 VUs).

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

## Production gates

Do not increase traffic until these alerts are green for at least 15 minutes:

- API availability: `up{job="yadony-api"} == 1`.
- 5xx rate: below 1% over 5 minutes.
- p95 latency: below 800 ms and p99 below 1.5 s.
- Hikari active connections: below 80% of the configured maximum.
- JVM memory: below 80% and no sustained GC pause increase.
- Cache hit ratio: monitor `cache_gets_total` and `cache_puts_total` for the hot caches.

The production Nginx limits are per client IP: 30 requests/minute for the general
API and 5 requests/minute for auth/KYC. They protect individual clients, but do not
replace capacity testing at the aggregate traffic level.

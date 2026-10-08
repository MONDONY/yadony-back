// Parcours utilisateur réaliste (lecture) : accueil, recherche, messagerie, portefeuille.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const TOKEN = __ENV.K6_ID_TOKEN;
// Liste blanche : tunnel SSH local vers le conteneur staging (voir STAGING.md § 2),
// ou api-staging. Toute autre cible (prod en tête) est refusée.
if (!BASE || !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/|^https:\/\/api-staging\.yadony\.com\//.test(BASE)) {
  throw new Error(`cible refusée (${BASE}) : staging via tunnel uniquement`);
}
if (!TOKEN) throw new Error('K6_ID_TOKEN manquant (voir STAGING.md § 3)');

const ep = {};
const s5xx = new Counter('server_5xx');
const s503 = new Counter('busy_503');

const PATHS = [
  ['home_me', '/auth/me', 3],
  ['unread', '/notifications/unread-count', 3],
  ['search_trips', '/announcements?departureCity=Paris&arrivalCity=Dakar', 3],
  ['search_pkgs', '/package-requests?departureCity=Paris&arrivalCity=Dakar', 2],
  ['cities', '/cities/search?q=Dak&limit=10', 2],
  ['conversations', '/conversations', 2],
  ['negotiations', '/negotiations/me', 1],
  ['wallet', '/wallet/balance', 1],
  ['notifications', '/notifications', 1],
  ['my_trips', '/announcements/my', 1],
  ['my_pkgs', '/package-requests/me', 1],
  ['subscriptions', '/me/subscriptions', 1],
  ['ratings', '/ratings/me/received', 1],
  ['favorites', '/favorites/trips', 1],
];
for (const [n] of PATHS) ep[n] = new Trend(`lat_${n}`, true);
const BAG = PATHS.flatMap(([n, p, w]) => Array(w).fill([n, p]));

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: '1m', target: 50 }, { duration: '1m30s', target: 50 },
        { duration: '1m', target: 100 }, { duration: '1m30s', target: 100 },
        { duration: '1m', target: 200 }, { duration: '1m30s', target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: { server_5xx: ['count<1'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const h = { headers: { Authorization: `Bearer ${TOKEN}` }, timeout: '60s' };
  for (let i = 0; i < 4; i++) {
    const [n, p] = BAG[Math.floor(Math.random() * BAG.length)];
    const r = http.get(`${BASE}${p}`, Object.assign({ tags: { ep: n } }, h));
    ep[n].add(r.timings.duration);
    if (r.status >= 500) s5xx.add(1, { ep: n, st: String(r.status) });
    if (r.status === 503) s503.add(1);
    check(r, { '2xx': (x) => x.status >= 200 && x.status < 300 });
  }
  sleep(1 + Math.random());
}

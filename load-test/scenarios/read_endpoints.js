import http from 'k6/http';
import { check, sleep } from 'k6';
import { getToken } from '../lib/auth.js';
import { thresholds } from '../lib/thresholds.js';

const BASE = __ENV.BASE_URL; // staging/dev, jamais prod
export const options = {
  thresholds,
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 1, duration: '30s' },
    load:  { executor: 'ramping-vus', startVUs: 0, stages: [{duration:'1m',target:50},{duration:'3m',target:50},{duration:'1m',target:0}], startTime: '30s' },
  },
};
export function setup() { return { token: getToken() }; }
export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}` } };
  // Paths corrected to match real controller @RequestMapping:
  //   /announcements               (@GetMapping on root, params: departureCity/arrivalCity)
  //   /favorites/ids               (@GetMapping("/ids") — confirmed)
  //   /package-requests            (@GetMapping on root — no /search suffix)
  //   /auth/me                     (@RequestMapping("/auth") + @GetMapping("/me"))
  //   /cities/search               (CityController: param is `q`, NOT `query`; limit clamped to [1,15];
  //                                 served by the `city-search` Caffeine cache after the first hit)
  //   /cities/corridors/popular    (CityController: limit clamped to [1,20]; `popular-corridors` cache, 1 min TTL)
  //   /notifications/unread-count  (NotificationController: any authenticated, non-guest user)
  // The two /cities endpoints require ROLE_SENDER or ROLE_TRAVELER (see README, test account role).
  const paths = [
    '/announcements?departureCity=Paris&arrivalCity=Dakar',
    '/favorites/ids',
    '/package-requests',
    '/auth/me',
    '/cities/search?q=Par&limit=10',
    '/cities/corridors/popular?limit=10',
    '/notifications/unread-count',
  ];
  for (const p of paths) {
    const r = http.get(`${BASE}${p}`, h);
    check(r, { 'status 200': (x) => x.status === 200 });
  }
  sleep(1);
}

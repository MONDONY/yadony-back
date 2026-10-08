// bid_journey.js — parcours réaliste sender : login → recherche d'annonces →
// dépôt d'une offre (bid) cash → annulation immédiate (cleanup idempotent).
//
// Pourquoi l'annulation systématique : createBid crée un vrai bid PENDING en
// base. cancelBid (PUT /bids/{bidId}/cancel) l'annule, restaure la capacité de
// l'annonce et laisse la DB dans l'état d'avant — comme le cycle PUT+DELETE de
// favorites.js. Sans ce cleanup, chaque itération laisserait un bid PENDING et
// la règle "un bid actif par (sender, annonce)" bloquerait les relances sur la
// même annonce (409 already-bid-on-announcement).
//
// Paiement cash volontaire : aucune dépendance Stripe/mobile money pour ce scénario.
// Tous les trajets n'acceptent pas les espèces (choix du voyageur, devise) : seuls
// ceux dont availablePaymentMethods contient CASH sont retenus, sinon l'offre finit
// en 422 et le chemin d'écriture n'est jamais mesuré. Sur la staging du 08/10, le
// corridor Paris → Dakar en comptait peu : BID_SEARCH_ARRIVAL=Abidjan en trouve plus.
//
// 4xx ATTENDUS et NON comptés comme échec (cf. all_endpoints.js — même
// philosophie) : l'inventaire d'annonces réelles en staging varie à chaque run
// (catégorie refusée, annonce déjà prise, propre annonce, trajet dédié fermé
// aux tiers…). Le cas le plus fréquent : la plupart des comptes voyageur ont
// par défaut « profils vérifiés uniquement » actif (contactKycOnly) — un
// compte de test sender NON KYC-vérifié reçoit alors 403 sur la quasi-totalité
// des annonces. Pour mesurer le chemin d'écriture réel (201), vérifier le
// compte de test en KYC sur staging. Échec réel = 5xx uniquement.
//
// Env :
//   BASE_URL              requis — staging/dev uniquement
//   K6_ID_TOKEN            token Firebase pré-minté (voir STAGING.md)
//   BID_SEARCH_DEPARTURE   défaut "Paris"
//   BID_SEARCH_ARRIVAL     défaut "Dakar"
//   BID_WEIGHT_KG          défaut "1" — poids de l'offre
//   BID_RECIPIENT_PHONE    défaut "+221770000000"
//   BID_FALLBACK_CATEGORY  défaut "Vêtements, chaussures" — utilisée si l'annonce
//                          n'expose aucune catégorie acceptée

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { getToken } from '../lib/auth.js';
import { thresholds } from '../lib/thresholds.js';

const BASE = __ENV.BASE_URL; // staging/dev only — NEVER prod
const WEIGHT_KG = parseFloat(__ENV.BID_WEIGHT_KG || '1');
const DEPARTURE = __ENV.BID_SEARCH_DEPARTURE || 'Paris';
const ARRIVAL = __ENV.BID_SEARCH_ARRIVAL || 'Dakar';
const RECIPIENT_PHONE = __ENV.BID_RECIPIENT_PHONE || '+221770000000';
const FALLBACK_CATEGORY = __ENV.BID_FALLBACK_CATEGORY || 'Vêtements, chaussures';

const bidsCreated = new Counter('bids_created');
const bidsCancelled = new Counter('bids_cancelled');
const serverErrors = new Counter('journey_server_errors');

export const options = {
  thresholds: {
    ...thresholds,
    journey_server_errors: ['count<1'],
  },
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 1, duration: '30s' },
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 20 },
        { duration: '3m', target: 20 },
        { duration: '1m', target: 0 },
      ],
      startTime: '30s',
    },
  },
};

export function setup() {
  return { token: getToken() };
}

function note5xx(res, label) {
  if (res.status >= 500) serverErrors.add(1, { step: label });
  check(res, { [`${label}: pas de 5xx`]: (r) => r.status < 500 });
}

// Annonce éligible : active, pas la mienne, pas en mode KG_FREE (poids libre
// non comparable à notre WEIGHT_KG fixe), capacité suffisante, espèces acceptées.
function pickEligibleAnnouncement(content, selfId) {
  for (const a of content) {
    if (a.status !== 'ACTIVE') continue;
    if (a.travelerId === selfId) continue;
    if (a.capacityUnit === 'KG_FREE') continue;
    if (a.availableKg == null || Number(a.availableKg) < WEIGHT_KG) continue;
    const methods = a.availablePaymentMethods || a.acceptedPaymentMethods || [];
    if (!methods.includes('CASH')) continue;
    return a;
  }
  return null;
}

export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' } };

  // 1. LOGIN — le token est déjà acquis en setup() (une fois par VU, pas par
  //    itération) ; /auth/me sert ici à identifier le compte (filtrer ses
  //    propres annonces) et vaut pour le "login" applicatif du parcours.
  const meRes = http.get(`${BASE}/auth/me`, h);
  note5xx(meRes, 'GET /auth/me');
  const selfId = meRes.status === 200 ? JSON.parse(meRes.body).id : null;

  // 2. RECHERCHE
  const searchUrl = `${BASE}/announcements?departureCity=${encodeURIComponent(DEPARTURE)}&arrivalCity=${encodeURIComponent(ARRIVAL)}`;
  const searchRes = http.get(searchUrl, h);
  note5xx(searchRes, 'GET /announcements');

  let content = [];
  if (searchRes.status === 200) {
    try {
      content = JSON.parse(searchRes.body).content || [];
    } catch (e) {
      content = [];
    }
  }

  const target = content.length > 0 ? pickEligibleAnnouncement(content, selfId) : null;
  if (!target) {
    // Rien d'éligible cette itération (corridor vide, tout appartient au
    // compte de test, etc.) — pas un échec : on a quand même mesuré auth+recherche.
    sleep(1);
    return;
  }

  const categories = Array.isArray(target.acceptedContentTypes) && target.acceptedContentTypes.length > 0
    ? target.acceptedContentTypes
    : [FALLBACK_CATEGORY];

  // 3. BID — offre cash minimale sur l'annonce choisie.
  const bidBody = JSON.stringify({
    weightKg: WEIGHT_KG,
    description: 'Colis test k6 (load-test, annulé immédiatement)',
    contentCategory: categories[0],
    recipientName: 'K6 LoadTest',
    recipientPhone: RECIPIENT_PHONE,
    disclaimerSigned: true,
    paymentMethod: 'CASH',
  });
  const bidRes = http.post(`${BASE}/announcements/${target.id}/bids`, bidBody, h);
  note5xx(bidRes, 'POST /announcements/{id}/bids');

  if (bidRes.status === 201) {
    bidsCreated.add(1);
    const bidId = JSON.parse(bidRes.body).id;

    // 4. CLEANUP — annule immédiatement l'offre qu'on vient de créer (idempotent,
    //    non-destructif : restaure la capacité, laisse la DB comme avant).
    const cancelRes = http.put(`${BASE}/bids/${bidId}/cancel`, null, h);
    note5xx(cancelRes, 'PUT /bids/{bidId}/cancel');
    if (cancelRes.status === 200) bidsCancelled.add(1);
  }

  sleep(1);
}

export function handleSummary(data) {
  const se = (data.metrics.journey_server_errors && data.metrics.journey_server_errors.values.count) || 0;
  const created = (data.metrics.bids_created && data.metrics.bids_created.values.count) || 0;
  const cancelled = (data.metrics.bids_cancelled && data.metrics.bids_cancelled.values.count) || 0;
  const lines = [
    '=== bid_journey (login -> recherche -> bid -> cancel) ===',
    `bids créés : ${created} · annulés (cleanup) : ${cancelled}` +
      (created !== cancelled ? '  ⚠️ écart — vérifier reports/bid-journey-summary.json' : ''),
    `erreurs serveur 5xx : ${se}`,
    se === 0 ? 'VERDICT: ✅ aucun 5xx' : `VERDICT: ❌ ${se} erreur(s) serveur`,
  ];
  return {
    stdout: '\n' + lines.join('\n') + '\n',
    // Chemin relatif au dossier de lancement de k6 : run.sh passe le sien.
    [`${__ENV.REPORTS_DIR || 'load-test/reports'}/bid-journey-summary.json`]: JSON.stringify(data, null, 2),
  };
}

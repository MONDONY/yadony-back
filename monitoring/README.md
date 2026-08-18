# Monitoring yadony — Grafana Cloud

La stack de production combine Prometheus, Grafana Cloud, Loki, Grafana Alloy,
Alertmanager et Sentry. Prometheus évalue les règles versionnées dans
`monitoring/prometheus/rules/`; Alertmanager groupe les alertes et les transmet
au relais Discord interne.

## 1. Compte et tokens

1. Créer un compte sur https://grafana.com (free tier) et une stack.
2. Dans « Connections → Add new connection → Hosted Prometheus metrics »,
   récupérer : URL de push (`GRAFANA_PROM_URL`), username (`GRAFANA_PROM_USER`).
3. Idem pour « Hosted logs (Loki) » : `GRAFANA_LOKI_URL`, `GRAFANA_LOKI_USER`.
4. Générer un token d'accès (`GRAFANA_CLOUD_TOKEN`) avec les scopes
   `metrics:write` et `logs:write`.
5. Renseigner ces 5 variables + `YADONY_ENV` dans le `.env` de chaque VPS.
6. Créer un webhook dans le salon Discord `#alertes-prod` et renseigner
   `DISCORD_WEBHOOK_URL` dans le même `.env`.

## 2. Agent Alloy

L'agent tourne en conteneur sur chaque VPS (service `alloy` des fichiers
Compose). Il collecte les métriques API et VPS, puis les envoie à Prometheus
local et Grafana Cloud. Il collecte aussi les logs Docker et les pousse vers
Loki. Vérifier après déploiement :

```bash
docker logs yadony_alloy
docker logs yadony_alertmanager
docker logs yadony_discord_alerts
```

Les cibles doivent être `up` dans Grafana (Explore → `up{job="yadony-api"}`).

## 3.1 Alertes Discord

Prometheus évalue les règles dans `monitoring/prometheus/rules/yadony-alerts.yml`.
Les alertes sont regroupées pendant 30 secondes, répétées au maximum toutes les
4 heures, et une notification de résolution est envoyée à Discord.

Seuils inclus : API indisponible, erreurs 5xx, latence p95, heap JVM, pool
PostgreSQL, CPU VPS, mémoire VPS et espace disque.

Le webhook Discord est uniquement injecté dans le conteneur relais via
`DISCORD_WEBHOOK_URL`; il n'est jamais écrit dans les fichiers de configuration
committés.

## 4. Dashboard technique

Importer le dashboard communautaire **JVM (Micrometer)** : Grafana →
Dashboards → New → Import → ID `4701`. Source de données : le Prometheus de
la stack. Il couvre heap, GC, threads, et les requêtes HTTP.

## 5. Dashboard métier

Créer un nouveau dashboard « yadony — Métier » avec un panneau par métrique.
Type de panneau : « Time series », sauf mention contraire. Requêtes PromQL
(le filtre `{env="$env"}` suppose une variable de dashboard `env`) :

| Panneau | Requête PromQL |
|---|---|
| Inscriptions / h | `sum(rate(yadony_users_registered_total{env="$env"}[1h])) * 3600` |
| Annonces créées / h | `sum(rate(yadony_announcements_created_total{env="$env"}[1h])) * 3600` |
| Bids créés / h par corridor | `sum by (corridor) (rate(yadony_bids_created_total{env="$env"}[1h])) * 3600` |
| Taux d'acceptation des bids | `sum(rate(yadony_bids_accepted_total{env="$env"}[6h])) / clamp_min(sum(rate(yadony_bids_created_total{env="$env"}[6h])), 0.0001)` |
| Paiements en escrow / h | `sum(rate(yadony_payments_escrow_ready_total{env="$env"}[1h])) * 3600` |
| Paiements libérés / h | `sum(rate(yadony_payments_released_total{env="$env"}[1h])) * 3600` |
| KYC validés / h | `sum(rate(yadony_kyc_verified_total{env="$env"}[1h])) * 3600` |
| Livraisons confirmées / h | `sum(rate(yadony_deliveries_confirmed_total{env="$env"}[1h])) * 3600` |
| Litiges ouverts (total 24 h) | `sum(increase(yadony_disputes_opened_total{env="$env"}[24h]))` |
| Annulations par motif (24 h) | `sum by (reason) (increase(yadony_cancellations_confirmed_total{env="$env"}[24h]))` |
| No-show voyageurs (24 h) | `sum(increase(yadony_travelers_no_show_total{env="$env"}[24h]))` |

> Les séries `yadony_*` n'apparaissent qu'après la première occurrence de
> l'événement correspondant. C'est normal sur un environnement neuf.

Après création, exporter le JSON (Dashboard settings → JSON Model) et le
committer dans `monitoring/dashboards/yadony-metier.json`.

## 6. Contact point Discord Grafana Cloud

Grafana → Alerting → Contact points → Add :
- Type : Discord.
- Webhook URL : celle du salon `#alertes-prod`.
- Tester avec « Test ».

## 7. Règles d'alerte Grafana Cloud

Grafana → Alerting → Alert rules → New. Pour chaque règle : source de données
Prometheus, condition `IS ABOVE`/`IS BELOW` selon le seuil, `for` = durée de
persistance, contact point = Discord.

| Alerte | Requête PromQL | Condition | for |
|---|---|---|---|
| API prod down | `up{job="yadony-api", env="prod"}` | `IS BELOW 1` | 2m |
| Taux d'erreurs 5xx élevé | `sum(rate(http_server_requests_seconds_count{env="prod", status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{env="prod"}[5m])), 0.0001)` | `IS ABOVE 0.05` | 5m |
| Latence p95 élevée | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{env="prod"}[5m])))` | `IS ABOVE 2` | 10m |
| Heap JVM proche saturation | `sum(jvm_memory_used_bytes{env="prod", area="heap"}) / sum(jvm_memory_max_bytes{env="prod", area="heap"})` | `IS ABOVE 0.9` | 10m |
| Disque hôte presque plein | `1 - (node_filesystem_avail_bytes{env="prod", fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{env="prod", fstype!~"tmpfs|overlay"})` | `IS ABOVE 0.85` | 15m |
| Pool DB épuisé | `hikaricp_connections_pending{env="prod"}` | `IS ABOVE 5` | 5m |
| Échecs de paiement anormaux | `sum(increase(yadony_disputes_opened_total{env="prod"}[1h]))` | `IS ABOVE 10` | 5m |

## 8. Monitoring uptime externe

Grafana → Testing & synthetics → Synthetic Monitoring → Create check :
- Type : HTTP.
- Cible : `https://api.yadony.app/api/v1/actuator/health`.
- Fréquence : 1 min. Sondes : 2-3 régions proches (Europe).
- Associer une alerte sur l'échec de la sonde → contact point Discord.

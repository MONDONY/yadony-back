# Story — Billets d'avion en affiliation Travelpayouts (Backend)

**Date:** 2026-10-12
**Status:** ✅ Complète (derrière le feature flag `flights_enabled`, éteint par défaut)

## Résumé
Recherche de billets d'avion, bons plans, alertes prix et lien partenaire dans Yadony, **en affiliation** Travelpayouts / Aviasales : Yadony ne vend aucun billet, l'utilisateur réserve chez le partenaire et Yadony touche une commission. Toute la fonctionnalité est derrière le réglage plateforme `flights_enabled`, piloté depuis le back-office, pour ne rien changer à l'existant tant qu'il est éteint.

## Fichiers créés
- `flights/FlightController.java` — les 9 routes REST, toutes gardées par `FlightFeature.requireEnabled()`.
- `flights/FlightFeature.java` — lecture du flag `flights_enabled` ; 503 `flights-unavailable` s'il est éteint.
- `flights/TravelpayoutsClient.java` — client HTTP (prix `prices_for_dates`, autocomplétion `places2`, annuaire des compagnies).
- `flights/FlightDate.java` — date de départ/retour : jour précis (`AAAA-MM-JJ`) ou mois entier (`AAAA-MM`).
- `flights/FlightQuery.java` — recherche validée et normalisée (codes IATA, devise, dates à venir, retour ≥ aller).
- `flights/FlightPriceService.java` — prix d'un trajet, cache Caffeine 30 min partagé (recherche, bons plans, scheduler), 50 offres max.
- `flights/FlightSearchService.java` — `GET /flights/search` ; mémorise le trajet pour les bons plans (sauf visiteur).
- `flights/FlightPlaceService.java` — autocomplétion des villes et conversion ville Yadony → code IATA (table `flight_city_codes`).
- `flights/FlightDealService.java`, `FlightDealCalculator.java` — bons plans : prix le plus bas ≥ 20 % sous la médiane, sur les corridors de l'utilisateur, liste diaspora par défaut.
- `flights/FlightDefaultsService.java` — `GET /flights/defaults` : dernière recherche, sinon corridor le plus récent.
- `flights/FlightClickService.java` — `POST /flights/clicks` : lien affilié construit côté serveur, clic enregistré.
- `flights/FlightAlertService.java`, `FlightAlertWindow.java`, `FlightAlertRule.java` — alertes prix sur une période, règle d'envoi anti-spam.
- `flights/FlightPriceAlertScheduler.java` — vérification des alertes toutes les 6 h.
- `flights/FlightOfferMapper.java`, `AirlineDirectory.java` — offre affichable (nom traduit de la compagnie, `logoUrl`).
- `flights/FlightUsers.java` — identité de l'appelant ; un visiteur (`ROLE_GUEST`) est anonyme.
- `flights/*Entity.java`, `*Repository.java`, `FlightCorridorQueryRepository.java`, `dto/*` — persistance et contrats.
- `config/dto/FlightsEnabledResponse.java` — réponse de `GET /config/flights-enabled`.
- `db/migration/V313__flights_affiliation.sql` — tables `flight_searches`, `flight_city_codes`, `flight_price_alerts`, `flight_clicks`.
- `db/migration/V314__flight_alert_periods_guest_clicks.sql` — périodes d'alerte (mois existants convertis), clics sans propriétaire.

## Fichiers modifiés
- `config/PlatformSettingKey.java`, `PlatformSettingsInitializer.java`, `PlatformSettingsService.java` — clé `flights_enabled` (BOOLEAN, `false` amorcé), validation true/false.
- `config/ConfigController.java` — `GET /config/flights-enabled` (public, comme ses voisins).
- `config/SecurityConfig.java` — visiteurs autorisés sur `GET /flights/search|places|deals|defaults` et `POST /flights/clicks` ; `/me/flight-alerts` reste fermé aux invités.
- `notifications/*` — type `FLIGHT_PRICE_ALERT` (catégorie trajets, réglage `pushCorridorAlerts`, deeplink `yadony://flights/alerts`).
- `application.yml`, `.env.example`, workflows de déploiement — `TRAVELPAYOUTS_TOKEN`, `TRAVELPAYOUTS_MARKER`, `TRAVELPAYOUTS_SUBID_ENABLED`, `FLIGHTS_LOGO_URL_TEMPLATE`.
- `i18n/messages_*.properties` — titre et texte de la notification d'alerte prix.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'app lit `GET /config/flights-enabled` au démarrage. Faux : aucun onglet Billets, et le back répond de toute façon 503 sur chaque route du package.
2. Recherche : `GET /flights/search` → `FlightQuery.of` valide (codes, dates, devise) → `FlightPriceService` (cache 30 min, puis Travelpayouts) → `FlightOfferMapper` (nom de compagnie traduit, `logoUrl`) → réponse triée par prix.
3. Bons plans : corridors de l'utilisateur (recherches puis trajets, demandes et alertes corridor convertis en IATA), un appel de prix par trajet, `FlightDealCalculator` garde les prix ≥ 20 % sous la médiane.
4. Réservation : l'app renvoie l'`offerPath` à `POST /flights/clicks`, le serveur construit `booking-base-url + offerPath + marker`, enregistre le clic et rend l'URL que l'app ouvre dans le navigateur externe.
5. Alertes : création sur une période ; toutes les 6 h le scheduler groupe les alertes identiques, interroge un mois par mois encore à venir (une combinaison dont le retour finit avant le départ est sautée), filtre les offres hors période, applique `FlightAlertRule` et notifie (`FLIGHT_PRICE_ALERT`). Un échec sur un seul mois écarte le groupe jusqu'au passage suivant : un prix partiel remettrait l'anti-spam à zéro.

### Points d'entrée API
- `GET /api/v1/config/flights-enabled` — public, `{"enabled": bool}`.
- `GET /api/v1/flights/search?origin&destination&departureDate&returnDate&direct&currency` — comptes et visiteurs. Dates `AAAA-MM-JJ` ou `AAAA-MM`.
- `GET /api/v1/flights/places?term=` — comptes et visiteurs.
- `GET /api/v1/flights/deals?currency=` — comptes et visiteurs (liste par défaut pour un visiteur).
- `GET /api/v1/flights/defaults` — comptes et visiteurs (`source: none` pour un visiteur).
- `POST /api/v1/flights/clicks` — comptes et visiteurs.
- `GET/POST /api/v1/me/flight-alerts`, `PATCH/DELETE /api/v1/me/flight-alerts/{id}` — comptes inscrits uniquement, 10 alertes max.

### Entités JPA impliquées
- `FlightSearchEntity` → `flight_searches` : trajets cherchés (jamais pour un visiteur).
- `FlightCityCodeEntity` → `flight_city_codes` : ville Yadony → code IATA, `iata_code` NULL = ville introuvable mémorisée.
- `FlightPriceAlertEntity` → `flight_price_alerts` : `departure_from/to`, `return_from/to` (DATE, par paires, ordonnées — contrainte `ck_flight_price_alerts_periods`), `last_notified_price` (anti-spam).
- `FlightClickEntity` → `flight_clicks` : `owner_id` NULL pour un visiteur, `sub_id` unique.

### Logique métier critique
- Aucun appel réseau dans une transaction (recherche, bons plans, scheduler) : aucune connexion JDBC n'est tenue pendant Travelpayouts.
- `offerPath` validé (chemin `/search/` du partenaire seulement) : pas de redirection ouverte.
- Période d'alerte limitée à 31 jours (jusqu'à trois mois couverts, par exemple du 31 janvier au 2 mars) ; une borne de début passée est ramenée à aujourd'hui, à la création comme à chaque vérification (`FlightAlertWindow.from`), et le contrôle de doublon compare les périodes vues d'aujourd'hui ; l'alerte se met en pause quand sa période de départ est passée.
- `TravelpayoutsClient` encode l'URI sans expansion de gabarit : un « {x} » saisi dans l'autocomplétion part tel quel au lieu de provoquer une 500.
- Visiteur : rendu anonyme dans `FlightUsers` **même s'il a une ligne `users`** (née d'un favori) : rien du package ne la référence, car la purge nocturne des invités supprime ces lignes physiquement (clé étrangère).
- `logoUrl` n'est construite que pour un code IATA bien formé (`[A-Z0-9]{2,3}`).

### Events Spring publiés / écoutés
- Aucun event : la notification passe par `NotificationDispatcher` depuis le scheduler, comme les autres alertes.

### Pièges et points d'attention
- Ne jamais modifier V313 ni V314 : créer V(n+1).
- `FlightPriceAlertScheduler` regroupe par trajet + période + direct + devise ; le cache de `FlightPriceService` partage les appels mensuels entre alertes différentes.
- `TRAVELPAYOUTS_SUBID_ENABLED` reste à `false` tant que le format `marker.subid` n'est pas validé sur le compte : un marker mal formé ferait perdre la commission.
- Sans token Travelpayouts, ou flag éteint : 503 `flights-unavailable` partout, scheduler inactif.
- `FLIGHTS_LOGO_URL_TEMPLATE` pointe par défaut vers le service d'images Travelpayouts ; l'URL https n'a pas été vérifiée depuis l'environnement de développement.

## Critères d'acceptation couverts
- [x] Recherche aller simple / aller-retour, au jour précis ou au mois — `FlightQuery`, `FlightDate`.
- [x] Autocomplétion des villes — `FlightPlaceService`.
- [x] Bons plans sur les corridors de l'utilisateur — `FlightDealService`.
- [x] Lien affilié construit côté serveur, clic enregistré — `FlightClickService`.
- [x] Alertes prix sur une période, vérifiées toutes les 6 h, notifiées seulement si le prix baisse encore — `FlightAlertService`, `FlightPriceAlertScheduler`, `FlightAlertRule`.
- [x] Préremplissage par la dernière recherche ou le corridor préféré — `FlightDefaultsService`.
- [x] Accès visiteur sans 403 (alertes exceptées) — `SecurityConfig`, `FlightUsers`.
- [x] Logos des compagnies — `FlightOfferMapper.logoUrl`.
- [x] Feature flag éteint par défaut, sans effet sur l'existant — `FlightFeature`, `flights_enabled`.

## Tests
- CI GitHub « Tests & Couverture » verte sur `771c2003` : suite complète passée, `jacoco:check` « All coverage checks have been met » (seuils du `pom.xml` : 75 % des instructions, 70 % des branches). Le pourcentage exact n'a pas été relevé.
- Localement : 194 tests ciblés passent (`Flight*`, `Travelpayouts*`, `ConfigController*`, `PlatformSettings*`, `V313*`, `V314*`).
- Tests ajoutés : `FlightControllerTest` (dont invités et flag éteint), `FlightSearchServiceTest`, `FlightDealServiceTest`, `FlightDefaultsServiceTest`, `FlightAlertServiceTest`, `FlightAlertWindowTest`, `FlightAlertRuleTest`, `FlightPriceAlertSchedulerTest`, `FlightClickServiceTest`, `FlightPlaceServiceTest`, `FlightQueryTest`, `FlightDateTest`, `FlightUsersTest`, `FlightOfferMapperTest`, `FlightPriceAndAirlineTest`, `TravelpayoutsClientTest`, `FlightCorridorQueryRepositoryIT`, `V313FlightsAffiliationMigrationTest`, `V314FlightAlertPeriodsMigrationTest`, `FlightPriceAlertNotificationTypeTest`, `ConfigControllerPlatformSettingsIT` (flag).
- Note : un passage de CI a échoué sur `DeliveryTransferConcurrencyIT` (concurrence des versements, sans lien avec ce package) ; il passe en local sur le même commit et au passage suivant.

## Décisions techniques
- **Affiliation plutôt que vente** : aucune réservation ni paiement côté Yadony, donc aucune donnée de passager stockée.
- **Feature flag réglage plateforme** (même mécanisme que `pro_enabled`) plutôt qu'une property : allumé depuis le back-office sans redéploiement ; garde au niveau du contrôleur pour couvrir toutes les routes d'un coup.
- **Jour ou mois** : Travelpayouts accepte les deux formes ; le mois reste disponible car il renvoie plus d'offres.
- **Alertes sur une période** filtrées côté serveur plutôt qu'au jour : le partenaire ne filtre qu'au jour ou au mois.
- **Visiteurs anonymes** plutôt que rattachés à leur ligne `users` : évite tout lien avec des lignes purgées physiquement.
- **V314 plutôt que modifier V313** : règle Flyway, et V313 pouvait déjà être appliquée en staging.
- **`logoUrl` servie par le back** plutôt qu'une URL tierce codée en dur dans l'app : source changeable sans mise à jour de l'app.

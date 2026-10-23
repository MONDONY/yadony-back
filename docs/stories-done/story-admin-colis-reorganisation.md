# Story admin — Réorganisation de la liste des colis (Backend)

**Date:** 2026-10-23
**Status:** ✅ Complète
**Spec:** `docs/superpowers/specs/2026-10-23-colis-reorganisation-design.md` (dépôt dony-admin)
**PR jumelle:** dony-admin (page `/colis`, tiroir à onglets, visionneuse de photos), à fusionner APRÈS cette PR.

## Résumé
La liste admin des colis (`GET /admin/bids`) gagne des vues rapides (« À traiter », « En cours », « Livrés récemment », etc.), une alerte unique par colis, une recherche repliée (accents, casse) sur le suivi, l'expéditeur, le voyageur, le destinataire (nom et téléphone), le trajet et les identifiants, des compteurs, des suggestions de recherche et les photos du colis par étape. Aucune migration, aucune nouvelle permission : tout est calculé en SQL à partir des tables existantes.

## Fichiers créés
- `admin/AdminBidSql.java` — SQL natif unique : alerte (`ALERT_RANK`), vues, recherche, compteurs, suggestions. Constantes `UNPAID_AFTER_HOURS`, `LATE_AFTER_DAYS`, `RECENT_DELIVERY_HOURS`.
- `admin/AdminBidQuery.java` — paramètres normalisés de la requête (jetons repliés, UUID, suffixe de téléphone, `now`) ; sentinelle `NO_MATCH_TOKEN`.
- `admin/AdminBidView.java`, `AdminBidScope.java`, `AdminBidAlert.java` — énumérations de la vue, du périmètre de recherche et de l'alerte (rang 1 à 4).
- `admin/AdminBidPhotoAssembler.java` — photos par étape avec URL signées.
- `admin/dto/AdminBidCountsResponse.java`, `AdminBidSuggestResponse.java`, `AdminBidPhotosResponse.java` — DTO des trois nouveaux points d'entrée.
- `config/ClockConfig.java` — bean `Clock` UTC injectable.
- Tests : `AdminBidsListIT`, `AdminBidsCountsIT`, `AdminBidsSuggestIT`, `AdminBidSearchIT`, `AdminBidSearchPostgresIT`, `AdminBidPhotosIT`, `AdminBidPhotoAssemblerTest`, `AdminSearchTermsTest`.

## Fichiers modifiés
- `admin/AdminSearchTerms.java` — repli des accents/casse (`fold`, table `ACCENT_FROM`/`ACCENT_TO`, majuscules accentuées comprises, apostrophes retirées), jetons sans `%`, `_` ni `\`, `phoneSuffix`.
- `admin/AdminBidsController.java` — liste filtrée (vue, périmètre, paiement, contenu, alerte, dates), `/counts`, `/suggest`, `/{id}/photos`, alerte dans le détail.
- `admin/AdminBidDetailAssembler.java` — incidents du colis dans la fiche, photos purgées masquées.
- `admin/dto/AdminBidListItemResponse.java`, `AdminBidDetailResponse.java` — champ `alert`, nouveaux champs de fiche.
- `matching/BidRepository.java` — requêtes natives qui assemblent les constantes `AdminBidSql` (liste, ids, rangs d'alerte, compteurs, suggestions) ; l'ancienne `findAdminFiltered` est supprimée.
- Tests existants : `AdminBidsControllerIT`, `AdminBidsControllerTest`, `AdminBidDetailAssemblerTest`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'admin ouvre `/colis` : le front appelle `GET /admin/bids/counts` (pastilles des onglets) et `GET /admin/bids?view=TODO...`.
2. Le contrôleur construit un `AdminBidQuery` (`AdminBidQuery.of`) : repli des accents sur la saisie, au plus 4 jetons, UUID éventuel, suffixe de téléphone (seulement si l'admin a `USER_VIEW`), `now` pris sur le `Clock` injecté.
3. `BidRepository` exécute le SQL natif de `AdminBidSql` ; la liste et les compteurs partagent exactement les mêmes prédicats.
4. L'alerte de chaque ligne vient de `alertRanks(ids, now, lateBefore)` (même `ALERT_RANK` que le tri et les compteurs).
5. La saisie dans la barre de recherche appelle `GET /admin/bids/suggest?q=` (cinq propositions au plus par groupe).
6. Le tiroir charge `GET /admin/bids/{id}` puis `/{id}/photos` à l'ouverture de l'onglet Photos.

### Points d'entrée API
Tous exigent `ROLE_ADMIN` + `BID_VIEW` (`@PreAuthorize` au niveau de la classe).
- `GET /admin/bids` — liste paginée. Filtres : `view`, `status`, `announcementId`, `query`, `scope`, `paymentMethod`, `content`, `alert`, `dateFrom`, `dateTo`, `page`, `size`. Le téléphone du destinataire n'est cherché que si l'admin a aussi `USER_VIEW`.
- `GET /admin/bids/counts` — nombres par vue (`todo`, `active`, `recent`, `completed`, `closed`, `all`) et par alerte (`disputeOpen`, `noShow`, `paymentPending`, `late`) pour les mêmes filtres (sauf vue/alerte).
- `GET /admin/bids/suggest?q=` — groupes `senders`, `travelers`, `recipients`, `trips`, `parcels` ; moins de 2 caractères = groupes vides. Aucun téléphone dans la réponse.
- `GET /admin/bids/{id}/photos` — photos par étape (création, départ, transit, arrivée, refus, litige). Les images de conversation (litige) exigent en plus les droits de modération et de litiges.
- `GET /admin/bids/{id}` — inchangé dans son contrat, enrichi de `alert` et des incidents.

### Entités JPA impliquées
Aucune nouvelle entité ni table. Lecture de `bids`, `announcements`, `users`, `disputes`, `tracking_events`, conversations/images de messagerie. Les colis supprimés (`deleted_at`) sont exclus ; les fils de négociation (`NEGOTIATING`, `NEGOTIATION_CLOSED`) ne sont jamais des colis.

### Logique métier critique
- **Alerte écrite une seule fois, en SQL** (`AdminBidSql.ALERT_RANK`), la plus urgente l'emporte : 1 litige ouvert (tout statut), 2 no-show, 3 paiement attendu depuis plus de 24 h (`UNPAID_AFTER_HOURS`), 4 colis en route après l'arrivée prévue (départ à défaut) + 1 jour de grâce (`LATE_AFTER_DAYS`). Liste, tri « À traiter », compteurs, filtre `alert` et étiquette la réutilisent : ne jamais la recopier en Java.
- **Vues** (`VIEW_PREDICATE`) : `TODO` = rang d'alerte > 0 ; `ACTIVE` = statuts en cours ; `RECENT` = livré il y a moins de 72 h (`RECENT_DELIVERY_HOURS`) ; `COMPLETED` ; `CLOSED` = rejeté, annulé, expiré, colis refusé, no-show. Tri « À traiter » : rang croissant puis plus ancien d'abord ; sinon plus récent d'abord.
- **Négociations exclues** de la liste, des compteurs et des suggestions.
- **Repli des accents et apostrophes** : `REPLACE(REPLACE(TRANSLATE(LOWER(col), ACCENT_FROM, ACCENT_TO), '''', ''), '’', '')` en SQL, avec la même règle que `AdminSearchTerms.fold` en Java (« N'Diaye » se trouve par « ndiaye »). La table contient aussi les majuscules accentuées : le résultat ne dépend plus de la locale (`lc_ctype`) de la base pour le `LOWER`. Les jetons sont repliés côté Java, les colonnes côté SQL ; `%`, `_` et `\` (échappement de LIKE) sont retirés des jetons (pas de joker ni d'échappement injecté).
- **Téléphone du destinataire** : jamais dans la botte de foin textuelle. Recherche par suffixe de chiffres uniquement (au moins 8 chiffres, `MIN_PHONE_DIGITS`), le numéro stocké doit se terminer par la saisie et au plus 3 chiffres de tête peuvent être omis (l'indicatif pays, `PHONE_MAX_UNTYPED_DIGITS`) ; réservée à `USER_VIEW`. Raisonnement sécurité : le téléphone est affiché masqué (quatre derniers chiffres visibles) ; une recherche par milieu, préfixe ou suffixe court servirait d'oracle pour reconstituer les chiffres masqués un à un. La règle R3 (suffixe d'au moins 8 chiffres, au plus 3 chiffres de tête omis) réduit l'oracle sur le téléphone masqué à une force brute d'environ 10^3 à 10^4 essais par numéro (risque résiduel accepté, à surveiller ; piste : audit ou limite de débit propre aux saisies numériques).
- **Photos** : sources = photos de création du colis, scans de suivi (départ/transit/arrivée), photo de refus, images de conversation du litige (sous droits). URL signées à la demande, valables 15 minutes, jamais stockées ni journalisées ; une clé qui ne se signe pas est ignorée, une panne de stockage donne une liste partielle (pas de 500). Photos purgées (scans) masquées côté back ; l'expiration des URL signées est gérée par le front (rechargement). Les photos de création sont signées une à une : une clé qui échoue est ignorée sans vider l'étage. **Clé de refus** : `refusalPhotoUrl` est fourni par l'utilisateur et n'est pas validé à l'écriture ; l'assembleur n'accepte donc la clé que sous `tracking/<bidId>/` (sinon ignorée avec un WARN), pour ne pas signer un fichier arbitraire du bucket.
  **Suite proposée** : valider `refusalPhotoUrl` à l'écriture dans `BidService.refuseParcel` (même préfixe), pour fermer le défaut à la source.
- **Suggestions** : expéditeurs/voyageurs (comptes non supprimés) comptés par colis ; destinataires groupés par nom replié ; trajets groupés par couple (départ, arrivée) replié (« Paris » et « paris » = un trajet) ; colis par suivi partiel, plus récents d'abord. Les colis d'un compte supprimé restent dans la liste mais le compte n'est plus suggéré.

### Events Spring publiés / écoutés
Aucun.

### Pièges et points d'attention
- **H2 contre PostgreSQL** : les IT tournent sur H2 (profil `test`), `AdminBidSearchPostgresIT` rejoue les requêtes clés sur un PostgreSQL embarqué. Tout nouveau SQL doit passer dans les deux (`CAST(:p AS VARCHAR) IS NULL`, `INTERVAL '24' HOUR`, `TRANSLATE`).
- **Base H2 partagée entre les classes de test** : chaque test crée ses propres données avec un jeton unique (noms de villes, de personnes) et se restreint à elles ; sans cela les nombres incluent ceux des autres tests. Colonne `tracking_number` limitée à 12 caractères.
- **Bean `Clock`** (`ClockConfig`) : toute l'horloge du module passe par lui ; seul `AdminBidsControllerTest` fige l'horloge ; les IT utilisent l'horloge réelle avec de larges marges. Ne pas appeler `LocalDateTime.now()` directement.
- **UUID en `byte[]` sur les projections H2** : les projections natives renvoient un UUID en octets sur H2 et en `UUID` sur PostgreSQL ; les requêtes castent donc les identifiants en texte (`CAST(id AS VARCHAR)`, colonnes `idText`, `bidIdText`) et le code convertit.
- **`NO_MATCH_TOKEN`** : une saisie faite seulement de `%` ou `_` perdrait tous ses jetons et ramènerait tous les colis. Elle est remplacée par le jeton en majuscules `NOMATCH`, qui ne correspond à rien PARCE QUE le SQL passe les colonnes en minuscules (`LOWER`) : ne pas retirer ce `LOWER` des deux côtés.
- **`LOWER()` sur les majuscules accentuées** : les lettres de la table de repli y figurent en majuscules, le résultat ne dépend donc plus de la base pour elles ; pour toute autre lettre, `LOWER()` dépend de la collation de la base (`LC_CTYPE`). Contrôle opérationnel pour le propriétaire : vérifier que la base de prod a un `lc_ctype` compatible UTF-8 (par exemple `en_US.UTF-8`), avec `SHOW lc_ctype;`. Sans cela, « ÉLOÏSE » ne se replierait pas comme « éloïse ».
- **Performances à surveiller** : la recherche d'identifiant fait un parcours complet (`CAST(id AS VARCHAR)`) ; `/counts` et `/suggest` coûtent plusieurs requêtes (5 pour `/suggest`) à chaque frappe, d'où la temporisation de 200 ms côté front.

## Critères d'acceptation couverts
- [x] Retrouver un colis par suivi (même partiel), expéditeur, voyageur, destinataire (nom et téléphone), trajet, identifiant de colis, d'annonce, de paiement ou de litige — `SEARCH_PREDICATE` + `AdminBidSearchIT`.
- [x] Voir ce qui demande une action — vue `TODO`, alerte par ligne, compteurs par alerte (`AdminBidsListIT`, `AdminBidsCountsIT`).
- [x] Tiroir rangé par onglets sans rien perdre — incidents et alerte dans le détail (`AdminBidDetailAssemblerTest`, `AdminBidsControllerIT`).
- [x] Photos du colis par étape — `/photos` (`AdminBidPhotosIT`, `AdminBidPhotoAssemblerTest`).
- [x] Rien de ce qui existe ne casse — paramètres existants conservés, tous les nouveaux champs optionnels côté client.
- [x] Anti-objectifs : photo purgée masquée, comptes supprimés non suggérés (colis conservé dans la liste), téléphone jamais renvoyé ni démasqué.

## Tests
- `./mvnw verify -DforkCount=2` → 12 940 tests, 0 échec, 0 erreur (7 ignorés par hypothèse, aucun `@Disabled`), BUILD SUCCESS
- Couverture JaCoCo globale (lignes) : **96,26 %** (seuil 90 %). SpotBugs : `spotbugs:check` : 0 défaut (BugInstance size 0, Error size 0).
- Tests ajoutés : voir « Fichiers créés » ; `AdminBidsSuggestIT` couvre les limites de 5, le repli des villes, les clés JSON précises (aucun téléphone).

## Décisions techniques
- **Une seule définition SQL de l'alerte** plutôt que Java + SQL : évite que liste, compteurs et étiquette divergent ; coût : SQL natif plus long.
- **Compteurs en un aller-retour** (agrégation conditionnelle) plutôt qu'un `COUNT` par vue : une seule lecture, mêmes prédicats que la liste.
- **Repli par `TRANSLATE`** plutôt que l'extension `unaccent` : pas de nouvelle extension (hors périmètre), même table en Java et en SQL, fonctionne sur H2.
- **Téléphone en suffixe borné** plutôt que recherche libre : réduit l'oracle sur le numéro masqué à une force brute d'environ 10^3 à 10^4 essais (risque résiduel accepté), au prix de ne pas trouver un numéro partiel court.
- **Pas de migration** : index existants suffisants à l'échelle actuelle ; à réévaluer si la liste ralentit (index trigram sur le texte replié).

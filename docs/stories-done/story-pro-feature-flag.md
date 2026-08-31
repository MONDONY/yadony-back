# Story — Feature flag de l'offre PRO (`pro_enabled`) (Backend)

**Date:** 2026-08-31
**Status:** ✅ Complète

## Résumé

L'offre PRO (abonnement, écran de vente, octroi) est mergée mais pas prête à être ouverte
commercialement. Un réglage plateforme `pro_enabled`, faux par défaut, permet de livrer
l'application mobile sans aucune entrée PRO visible, puis d'ouvrir l'offre depuis le
back-office (Paramètres) sans redéploiement. Tant que l'offre est fermée, les quotas
réservés aux comptes standard (annonces mensuelles, brouillons) ne s'appliquent plus :
un quota dont le dépassement ne peut pas s'acheter n'est qu'un mur.

## Fichiers créés

- `config/dto/ProEnabledResponse.java` — forme publique `{"enabled": bool}`, identique à `SmsEnabledResponse` (même parseur côté mobile).
- `src/test/java/com/yadony/api/config/ConfigControllerProEnabledTest.java` — route publique, forme, défaut `false`.

## Fichiers modifiés

- `config/PlatformSettingKey.java` — clé `PRO_ENABLED("pro_enabled", BOOLEAN)`.
- `config/PlatformSettingsService.java` — property `yadony.pro.enabled`, `proEnabled()`, **`hasProQuotas(boolean isProAccount)`** (règle unique des quotas), `normalize` true/false, snapshot et `effectiveValue`.
- `config/PlatformSettingsSnapshot.java` — champ `proEnabled`.
- `config/PlatformSettingsInitializer.java` — amorçage de la ligne depuis la property.
- `config/ConfigController.java` — `GET /config/pro-enabled` (public via `/config/**`).
- `matching/AnnouncementService.java` — trois contrôles de quota lisent `settings.hasProQuotas(...)` au lieu de `user.isProAccount()` : limite mensuelle (`assertCanPublish`), brouillons à la création, brouillons à la dépublication.
- `requests/service/PackageRequestService.java` — nouvelle dépendance `PlatformSettingsService`, quota de brouillons via `hasProQuotas`.
- `application.yml` — `yadony.pro.enabled: ${YADONY_PRO_ENABLED:false}`.
- Tests : `PlatformSettingsTestFactory` (5e paramètre `proEnabled`, **ouvert par défaut** — voir pièges), `PlatformSettingsServiceIT`, `PlatformSettingsInitializerIT` (4 → 5 clés), `ConfigControllerPlatformSettingsIT`, `AdminSettingsControllerIT` (`$[4]`), `AnnouncementServiceTest` (classe imbriquée « offre PRO fermée »), `PackageRequestService*Test` (constructeur + cas offre fermée).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. Au démarrage, `PlatformSettingsInitializer` insère `pro_enabled` s'il manque, avec la valeur de `yadony.pro.enabled` (env `YADONY_PRO_ENABLED`, défaut `false`). Une ligne existante n'est jamais écrasée.
2. L'application mobile appelle `GET /config/pro-enabled` au boot et masque toute entrée PRO si `enabled=false`.
3. Un administrateur ouvre l'offre par `PUT /admin/settings/pro_enabled` `{"value":"true"}` (permission `CONFIG_MANAGE`, écran Paramètres). L'écriture est auditée (`PLATFORM_SETTING_CHANGED`) et évince le cache `platform-settings`.
4. Côté serveur, chaque contrôle de quota demande `settings.hasProQuotas(user.isProAccount())` : `true` si le compte est PRO **ou** si l'offre est fermée.

### Points d'entrée API

- `GET /config/pro-enabled` — public, `{"enabled": boolean}`.
- `GET /admin/settings` — la clé apparaît en 5e position (ordre de l'enum).
- `PUT /admin/settings/pro_enabled` — `ADMIN`/`SUPER_ADMIN` avec `CONFIG_MANAGE`.

### Logique métier critique

- **Règle des quotas centralisée** dans `PlatformSettingsService.hasProQuotas`. Ne jamais réintroduire un `user.isProAccount()` nu sur un site de quota : les quatre contrôles doivent lever ensemble.
- Offre fermée = tout le monde au plafond PRO (`maxDraftsPro`, pas de limite mensuelle). Le message « Passez en PRO pour en créer davantage » n'est ajouté que lorsque le quota standard s'applique.
- Aucun blocage sur `/billing/*` : le flag est un flag d'affichage et de quotas, pas un verrou serveur de l'abonnement (un compte déjà PRO garde ses droits).

### Pièges et points d'attention

- `PlatformSettingsTestFactory` stubbe `proEnabled()=true` **et** `hasProQuotas` (règle recopiée) : `hasProQuotas` est une méthode du mock, non stubbée elle rendrait `false` pour un compte PRO et casserait les tests de quota existants. Utiliser `withProEnabled(false)` pour les cas offre fermée.
- Le défaut de production est `false` alors que le défaut des tests unitaires est `true` : c'est voulu, les tests de quota décrivent le comportement offre ouverte.
- `PlatformSettingsInitializerIT` compte désormais **5** lignes amorcées.
- H2 de test partagée entre contextes : `PlatformSettingsServiceIT` remet la table à zéro avant et après chaque test, y compris `pro_enabled`.

## Critères d'acceptation couverts

- [x] Flag faux par défaut, exposé publiquement — `ConfigControllerProEnabledTest`.
- [x] Modifiable depuis le back-office, visible immédiatement — `ConfigControllerPlatformSettingsIT.proEnabledFollowsTheTableToo`.
- [x] Offre fermée ⇒ quotas standard levés (annonces mensuelles, brouillons trajet à la création et à la dépublication, brouillons colis) — `AnnouncementServiceTest.ProDisabledQuotaTests`, `PackageRequestServiceTest.create_asDraft_overStandardLimit_proDisabled_usesProQuota`.
- [x] Offre ouverte ⇒ comportement inchangé — tous les tests de quota existants.
- [x] Valeur non booléenne refusée en 422 — `PlatformSettingsServiceIT.proEnabledRejectsAnythingButABoolean`.

## Tests

- `./mvnw test` → voir la PR pour le total (0 rouge).
- `./mvnw test jacoco:report` → couverture globale indiquée dans la PR.

## Décisions techniques

- **Réglage plateforme plutôt que property** : la property seule exigerait un redéploiement ; PostHog feature flags introduirait une dépendance réseau dans un chemin de quota serveur. `platform_settings` existait déjà avec cache, audit et écran admin.
- **Pas de migration SQL** : l'amorçage vit dans `PlatformSettingsInitializer`, comme pour `sms_enabled`, parce que la valeur vient d'une variable d'environnement invisible depuis du SQL.
- **Traiter tout le monde comme PRO** plutôt que « sans limite » : réutilise les plafonds existants (`maxDraftsPro`), aucune nouvelle constante à maintenir.

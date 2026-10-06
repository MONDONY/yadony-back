# Story — Alertes admin lisibles et actionnables (Backend)

**Date:** 2026-10-06
**Status:** ✅ Complète

## Résumé
L'écran Alertes du back-office n'affichait qu'un code technique (`MONEY_INVARIANT_INV-16`,
`ESCROW_J48_TIMEOUT`…), toujours en sévérité « Info », sans dire ce qui s'était passé ni quoi
faire. Le back persiste maintenant la phrase de l'incident et la vraie sévérité, joint aux
alertes de cohérence de l'argent un extrait des lignes fautives, et expose un endpoint qui
ré-exécute la règle pour montrer ce qui reste à corriger. Le front (dony-admin) s'en sert
pour une fiche « Que faire » par alerte.

## Fichiers créés
- `src/main/resources/db/migration/V290__admin_alerts_detail_and_severity_backfill.sql` — colonne `admin_alerts.detail` + rattrapage de `severity` des alertes existantes.
- `src/main/java/com/yadony/api/admin/dto/AdminAlertViolationsResponse.java` — réponse de `GET /admin/alerts/{id}/violations`.
- `src/test/java/com/yadony/api/migrations/V290AdminAlertsDetailMigrationTest.java`
- `src/test/java/com/yadony/api/payments/EscrowSchedulerTest.java`

## Fichiers modifiés
- `admin/AdminAlertEntity.java` — champ `detail` (TEXT, nullable).
- `admin/dto/AdminAlertResponse.java` — expose `detail`.
- `admin/AdminAlertEscalator.java` — persiste `detail` et `severity` ; surcharge `raiseOnce(type, severity, detail, context)`. Sans sévérité explicite : `AdminAlertService.severityOf(type)`.
- `common/stripe/AdminAlertService.java` — `severityOf(code)` : INFO / WARN / CRITICAL, alignée sur la gravité qui pilote déjà log, Sentry et Telegram.
- `payments/integrity/MoneyIntegrityMonitor.java` — sévérité de la règle (CRITIQUE → CRITICAL, sinon WARN), extrait de 3 lignes fautives dans le contexte (`exemples`), méthode `inspect(code, limit)`.
- `admin/AdminAlertController.java` — `GET /admin/alerts/{id}/violations`.
- `payments/EscrowScheduler.java`, `cancellation/job/ReturnDeadlineScheduler.java` — `severity = WARN` + phrase `detail`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Un service lève une alerte via `AdminAlertEscalator.raiseOnce` (ou un scheduler crée la ligne directement).
2. La ligne `admin_alerts` porte désormais `type`, `severity` réelle, `detail` (la phrase envoyée sur Telegram) et `payload`.
3. `GET /admin/alerts` renvoie `detail` en plus ; le front affiche un titre en clair (catalogue côté dony-admin) et la phrase.
4. Pour une alerte `MONEY_INVARIANT_<code>`, le front appelle `GET /admin/alerts/{id}/violations` : la règle SQL est ré-exécutée (transaction lecture seule, timeout 30 s) et les lignes encore fautives sont renvoyées.

### Points d'entrée API
- `GET /api/v1/admin/alerts/{id}/violations?limit=50` — `ALERT_VIEW`. 404 `alert-not-found`, 422 `alert-without-violations` si l'alerte n'est pas une règle de cohérence, 404 `invariant-not-found` si le code n'existe plus. `limit` plafonné à 100.

### Entités JPA impliquées
- `AdminAlertEntity` → `admin_alerts` : nouvelle colonne `detail TEXT NULL`.

### Logique métier critique
- Sévérité : avant V290 personne n'écrivait la colonne, tout restait `INFO`. Les types escaladés (suffixés d'un identifiant) sont tous des incidents pour `AdminAlertService` → `CRITICAL`. Les règles de cohérence prennent leur gravité (CRITIQUE → CRITICAL, HAUTE/MOYENNE → WARN). J+48 et retour non rendu → WARN (à vérifier, pas un incident en soi).
- Les sévérités alimentent aussi la cloche de notifications admin (`AdminNotificationItems.alertSeverity`).
- `exemples` limité à 3 lignes : le contexte part aussi sur Telegram (limite 4096 caractères).
- Les valeurs JDBC (Timestamp, PGInterval, UUID) sont converties en texte (`toJsonFriendly`) : sérialisées en JSONB et en HTTP.

### Events Spring publiés / écoutés
Aucun nouvel event.

### Pièges et points d'attention
- La migration V290 ne touche que les lignes `severity = 'INFO'` : une sévérité déjà posée explicitement est conservée.
- `inspect` exécute du SQL potentiellement coûteux à chaque ouverture de fiche : le timeout de 30 s et le plafond de lignes le bornent.

## Critères d'acceptation couverts
- [x] Given une alerte ouverte, When l'admin la consulte, Then il voit ce qui s'est passé (`detail`) et la vraie sévérité.
- [x] Given une alerte de cohérence de l'argent, When l'admin ouvre la fiche, Then il voit les lignes encore en faute et peut les ouvrir.
- [x] Given des alertes levées avant V290, When la migration passe, Then leur sévérité est rattrapée et les règles de cohérence reçoivent une phrase.

## Tests
- Tests ciblés : `AdminAlertControllerTest`, `AdminAlertEscalatorTest`, `AdminAlertServiceTest`, `MoneyIntegrityMonitorTest`, `MoneyIntegrityMonitorIT`, `V290AdminAlertsDetailMigrationTest`, `EscrowSchedulerTest`, `ReturnDeadlineSchedulerTest`, `AdminAlertPersistenceTest` → verts.
- `mvn test` complet : voir le résultat de la PR (lancé avant push).

## Décisions techniques
- Catalogue « que faire » côté front, pas côté back : c'est du texte d'interface, modifiable sans redéploiement du back ; le back fournit les faits (`detail`, `payload`, lignes fautives).
- Lignes fautives recalculées à la demande plutôt que stockées : l'admin voit l'état actuel et sait quand l'anomalie a disparu ; l'extrait `exemples` garde la trace du moment de la levée.

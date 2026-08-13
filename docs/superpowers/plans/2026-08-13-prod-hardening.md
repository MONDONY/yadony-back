# Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Réduire le code mort, borner les lectures coûteuses, renforcer la visibilité production et fournir un scénario de charge reproductible.

**Architecture:** Le travail reste séparé dans deux worktrees, un pour Spring Boot et un pour Flutter. Les changements sont limités aux suppressions prouvées, aux bornes/paginations compatibles avec les API existantes, aux index Flyway et à l’observabilité déjà présente.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, Micrometer/Prometheus, Flutter/Dart, k6.

## Global Constraints

- Ne pas modifier les migrations existantes ; ajouter une migration V(n+1).
- Ne pas committer directement sur `main`.
- Préserver le changement local de `dony_app/pubspec.yaml` dans le checkout principal.
- Ne pas supprimer `MatchingRequestModel`, encore utilisé par les alertes de corridor.
- Vérifier chaque lot avec les tests ciblés puis les analyseurs/builds concernés.

### Task 1: Remove dead matching API clients

**Files:**
- Modify: `dony_app/lib/features/package_request/data/package_request_repository.dart`
- Modify: `dony_app/lib/features/package_request/data/models/matching_request.dart`
- Modify: `dony-back/src/main/java/com/yadony/api/matching/MatchingService.java`
- Modify: `dony-back/src/test/java/com/yadony/api/matching/MatchingServiceTest.java`

- [ ] Supprimer uniquement la méthode legacy du repository Flutter et ses imports devenus inutiles.
- [ ] Supprimer la méthode legacy du service Java et le groupe de tests qui ne couvre plus un chemin de production.
- [ ] Mettre à jour la documentation du modèle partagé pour référencer l’endpoint des alertes de corridor.
- [ ] Lancer les tests Matching/alertes et `flutter analyze`.

### Task 2: Bound hot reads

**Files:**
- Modify: `ConversationController.java`, `ConversationService.java`, tests associés.
- Modify: `BidController.java` et services/repositories concernés si l’API supporte déjà `Pageable`.
- Modify: `NegotiationService.java` uniquement avec une optimisation prouvée par test.

- [ ] Ajouter une pagination à l’archive des conversations avec une taille maximale serveur.
- [ ] Vérifier et borner les listes de bids chaudes sans casser les DTO clients.
- [ ] Remplacer les N+1 seulement lorsque les repositories offrent un chargement batch compatible.
- [ ] Ajouter les tests de taille maximale et de pagination.

### Task 3: Database indexes and protection

**Files:**
- Create: `src/main/resources/db/migration/V203__production_hot_path_indexes.sql` (version à ajuster au dernier fichier).
- Modify: configuration rate limiting/actuator uniquement si l’existant le permet.

- [ ] Ajouter les index ciblés sur utilisateur, statut, corridor et dates après vérification des colonnes.
- [ ] Vérifier que les endpoints publics et OTP restent limités par la configuration existante.
- [ ] Ne pas introduire Redis ni de mécanisme distribué non présent.

### Task 4: Observability and load test

**Files:**
- Modify: configuration Prometheus/alerting documentée.
- Create: `load-tests/README.md` et scénario k6 non destructif.

- [ ] Documenter les métriques et seuils d’alerte p95/p99, 5xx, pool DB et cache.
- [ ] Ajouter un scénario de charge avec variables d’environnement et endpoints en lecture.
- [ ] Vérifier les tests, l’analyse statique et les diffs finaux dans les deux worktrees.

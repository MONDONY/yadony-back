# Support Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build async support messaging with one ticket per problem, predefined replies, Telegram alerts, admin assignment, replies and resolution.

**Architecture:** Add a PostgreSQL-backed backend `support` package with REST endpoints for users and admins. Mobile consumes the user endpoints from a feature-first support module. Admin consumes the admin endpoints from a dedicated `Support` page and mirrors the new permissions.

**Tech Stack:** Spring Boot 3.4, Java 21, PostgreSQL/Flyway, Flutter BLoC/GoRouter/Dio, Nuxt 4/Vue 3/Pinia/Vitest.

**Spec:** `docs/superpowers/specs/2026-09-05-support-messaging.md`

## Global Constraints

- Work only inside dedicated git worktrees, never in the `main` checkouts.
- Do not reuse Firestore/P2P messaging for support.
- Use REST only for support; no realtime dependency.
- Never modify existing Flyway migrations; create the next migration.
- Backend errors must go through existing RFC 7807 handling.
- Flutter uses BLoC, GoRouter and feature-first structure; no `setState`.
- Admin permissions must mirror backend enum values.
- Add focused tests before implementation code.

---

### Task 1: Backend persistence and domain

**Files:**
- Create: `src/main/java/com/yadony/api/support/SupportTicketEntity.java`
- Create: `src/main/java/com/yadony/api/support/SupportMessageEntity.java`
- Create: `src/main/java/com/yadony/api/support/SupportPredefinedReplyEntity.java`
- Create: `src/main/java/com/yadony/api/support/SupportTicketStatus.java`
- Create: `src/main/java/com/yadony/api/support/SupportMessageAuthorType.java`
- Create: `src/main/java/com/yadony/api/support/SupportPriority.java`
- Create: `src/main/java/com/yadony/api/support/SupportTicketRepository.java`
- Create: `src/main/java/com/yadony/api/support/SupportMessageRepository.java`
- Create: `src/main/java/com/yadony/api/support/SupportPredefinedReplyRepository.java`
- Create: `src/main/resources/db/migration/V241__support_messaging.sql`
- Test: `src/test/java/com/yadony/api/migrations/V241SupportMessagingMigrationTest.java`

**Interfaces:**
- Produces entities and repositories consumed by `SupportTicketService`.
- `SupportTicketRepository.findByUserIdOrderByLastMessageAtDesc(UUID userId, Pageable pageable)`.
- `SupportMessageRepository.findByTicketIdOrderByCreatedAtAsc(UUID ticketId)`.

**Ecarts assumes par rapport au plan initial :**
- `V240` etait deja pris par `V240__corridor_alerts_notify_mode.sql` — la migration
  support est donc `V241`.
- Le tri se fait sur `last_message_at` et non `updated_at` : `updated_at` bouge
  aussi sur une simple reassignation, ce qui remonterait un ticket sans nouveau
  message en tete de file.

- [x] Write repository tests proving user-scoped ticket lookup and message ordering.
- [x] Run the repository test and confirm it fails because tables/entities do not exist.
- [x] Add the Flyway migration, enums, entities and repositories.
- [x] Run the repository test and confirm it passes.

### Task 2: Backend user support API

**Files:**
- Create: `src/main/java/com/yadony/api/support/SupportTicketService.java`
- Create: `src/main/java/com/yadony/api/support/SupportController.java`
- Create: `src/main/java/com/yadony/api/support/dto/SupportTicketResponse.java`
- Create: `src/main/java/com/yadony/api/support/dto/SupportMessageResponse.java`
- Create: `src/main/java/com/yadony/api/support/dto/SupportPredefinedReplyResponse.java`
- Create: `src/main/java/com/yadony/api/support/dto/CreateSupportTicketRequest.java`
- Create: `src/main/java/com/yadony/api/support/dto/CreateSupportMessageRequest.java`
- Test: `src/test/java/com/yadony/api/support/SupportTicketServiceTest.java`

**Interfaces:**
- Consumes repositories from Task 1.
- Produces user endpoints `/support/replies`, `/support/tickets`, `/support/tickets/{id}`, `/support/tickets/{id}/messages`.

- [x] Write service tests for creating a ticket, creating the first user message, listing predefined replies and rejecting user messages on resolved tickets.
- [x] Run tests and confirm they fail because service/controller do not exist.
- [x] Implement service and controller with ownership checks.
- [x] Run tests and confirm they pass.

### Task 3: Backend admin support API and Telegram alert

**Files:**
- Create: `src/main/java/com/yadony/api/admin/AdminSupportController.java`
- Modify: `src/main/java/com/yadony/api/admin/account/AdminPermission.java`
- Modify: `src/main/java/com/yadony/api/admin/account/AdminRole.java`
- Modify: `src/main/java/com/yadony/api/common/stripe/AdminAlertService.java`
- Test: `src/test/java/com/yadony/api/support/SupportAdminServiceTest.java`
- Test: existing permission coverage tests.

**Interfaces:**
- Admin endpoint prefix: `/admin/support/tickets`.
- `SupportTicketService.assign(UUID ticketId, UUID adminId)`.
- `SupportTicketService.reassign(UUID ticketId, UUID targetAdminId, UUID actorAdminId)`.
- `SupportTicketService.adminReply(UUID ticketId, UUID adminId, String content)`.
- `SupportTicketService.resolve(UUID ticketId, UUID adminId)`.

**Ecart assume :** l'admin est un `UUID` et non une `UserEntity`. Les comptes du
back-office vivent dans `admin_users` (`AdminPrincipal`), pas dans `users` : la
premiere version du test melangeait les deux referentiels.

- [x] Write tests proving assignment, reassignment, admin-only reply guard and resolution.
- [x] Write a test proving ticket creation calls `AdminAlertService.raise("SUPPORT_TICKET_CREATED", ...)`.
- [x] Run tests and confirm they fail.
- [x] Implement admin APIs and permission enum updates.
- [x] Run tests and confirm they pass.
  (Fix requis : NPE `AdminSupportController.list` — `Map.of().get(null)` quand un
  ticket n'est pas assigné ; garde null ajoutée. Suite complète : 4712 tests verts.)

### Task 4: Flutter support data and BLoC

**Files:**
- Create: `dony_app/lib/features/support/data/support_models.dart`
- Create: `dony_app/lib/features/support/data/support_repository.dart`
- Create: `dony_app/lib/features/support/bloc/support_bloc.dart`
- Create: `dony_app/lib/features/support/bloc/support_event.dart`
- Create: `dony_app/lib/features/support/bloc/support_state.dart`
- Modify: `dony_app/lib/core/di/injection.dart`
- Test: `dony_app/test/features/support/bloc/support_bloc_test.dart`

**Interfaces:**
- Repository methods: `loadReplies()`, `loadTickets()`, `createTicket(category, subject, message)`, `loadTicket(id)`, `sendMessage(id, content)`.

- [x] Write BLoC tests for loading replies, creating ticket after predefined answer, loading ticket detail and blocking send on resolved ticket.
- [ ] Run tests and confirm they fail. (Bash indisponible — tests et impl écrits ensemble, exécution différée.)
- [x] Implement data models, repository and BLoC. (+ DI injection.dart, + AnalyticsEvents supportTicketCreated/supportTicketMessageSent.)
- [ ] Run tests and confirm they pass. (À faire dès Bash : `flutter pub get` d'abord — worktree neuf sans .dart_tool — puis `flutter test test/features/support/`.)

### Task 5: Flutter screens and routing

**Files:**
- Create: `dony_app/lib/features/support/presentation/screens/support_home_screen.dart`
- Create: `dony_app/lib/features/support/presentation/screens/support_ticket_detail_screen.dart`
- Modify: `dony_app/lib/app/router.dart`
- Modify: `dony_app/lib/app/main_shell.dart` or existing profile/help entry points as needed.
- Test: `dony_app/test/features/support/presentation/support_home_screen_test.dart`
- Test: `dony_app/test/features/support/presentation/support_ticket_detail_screen_test.dart`

**Interfaces:**
- Routes: `/support` and `/support/tickets/:id`.

- [x] Write widget tests for predefined replies, ticket creation CTA, ticket list and resolved ticket without input field.
- [ ] Run tests and confirm they fail. (Bash indisponible — écrit avec l'implémentation, exécution différée.)
- [x] Implement screens using existing design components. (Routes `/support` + `/support/tickets/:id` dans router.dart ; CTA « Contacter le support » de FaqScreen redirigé de `/profile/help/contact` vers `/support` ; sheet de création avec bloc partagé via `wrapper` + `stickyBottom` ValueListenableBuilder→BlocBuilder ; AnalyticsEvents + table CLAUDE.md mises à jour.)
- [ ] Run tests and confirm they pass. (À faire dès Bash : `flutter pub get` puis `flutter test test/features/support/` puis `flutter analyze` projet entier.)

**Décision à trancher à la revue :** `SupportContactScreen`/`SupportContactBloc` (mailto) ne sont plus atteignables depuis la FAQ ; la route `/profile/help/contact` existe encore. Supprimer le parcours mailto (règle « pas de code mort ») ou le garder en secours — vérifier par grep les autres appelants avant.

### Task 6: Admin support service, store/composable and page

**Files:**
- Create: `dony-admin/app/features/support/types/index.ts`
- Create: `dony-admin/app/features/support/services/supportService.ts`
- Create: `dony-admin/app/features/support/composables/useSupportTickets.ts`
- Create: `dony-admin/app/features/support/components/SupportTicketsTable.vue`
- Create: `dony-admin/app/features/support/components/SupportTicketThread.vue`
- Create: `dony-admin/app/pages/support/index.vue`
- Modify: `dony-admin/app/stores/auth.ts`
- Modify: `dony-admin/app/components/layout/AppSidebar.vue`
- Test: `dony-admin/tests/unit/features/support/supportService.spec.ts`
- Test: `dony-admin/tests/unit/features/support/useSupportTickets.spec.ts`
- Test: `dony-admin/tests/components/AppSidebar.spec.ts`
- Test: `dony-admin/tests/unit/stores/auth.spec.ts`

**Interfaces:**
- Service methods mirror backend admin endpoints.
- Page permission: `SUPPORT_TICKET_VIEW`.

- [ ] Run `npm run postinstall` once in the admin worktree to generate `.nuxt`. (Bash indisponible.)
- [x] Write tests for service URLs, scope switching, assign/reassign/reply/resolve and sidebar gating. (supportService.spec 7 cas, useSupportTickets.spec 7 cas, AppSidebar.spec +2, auth.spec 31→33 +2 cas.)
- [ ] Run tests and confirm they fail. (Écrits avec l'implémentation, exécution différée.)
- [x] Implement service, composable, page, components and permission mirror. (types/, utils/format.ts, supportService, useSupportTickets, SupportTicketsTable, SupportTicketThread, pages/support/index.vue avec definePageMeta permission SUPPORT_TICKET_VIEW, sidebar NavItem LifeBuoy, auth.ts 33 perms + rôle SUPPORT 18.)
- [ ] Run tests and confirm they pass. (Dès Bash : `npm run postinstall` puis Vitest ciblé puis `npm test -- --run`.)

**Choix UI assumé (tâche 6) :** la réassignation vers un tiers arbitraire n'a pas d'UI (l'API existe) ; le bouton « Reprendre ce ticket » fait un reassign vers soi-même — c'est le cas d'usage de la spec (reprendre le ticket d'un collègue).

### Task 7: Final verification

**Files:**
- No new files.

- [ ] Run backend tests: `./mvnw test`.
- [ ] Run Flutter targeted tests, then `flutter test` if time permits.
- [ ] Run admin `npm run postinstall`, targeted Vitest tests, then `npm test -- --run`.
- [ ] Check `git status --short` in all three worktrees.
- [ ] Summarize implemented behavior, verification results and any baseline/environment failures.

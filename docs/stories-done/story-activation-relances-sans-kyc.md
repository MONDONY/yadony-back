# Relances J+1 / J+3 des comptes non vérifiés (Backend)

**Date:** 2026-11-27
**Status:** ✅ Complète (interrupteur éteint par défaut)

## Résumé
La relance « première action » (story activation-guidage-apres-kyc) ne touchait que les comptes au KYC vérifié. Avec le KYC demandé au moment de publier, la plupart des nouveaux comptes ne sont jamais vérifiés : ils n'étaient pas relancés. Cette story étend le même planificateur aux comptes non vérifiés récents.

## Fichiers modifiés
- `activation/ActivationRepository.java` — `findUnverifiedFirstActionReminderCandidates` : comptes non vérifiés, ancrés sur `users.created_at`, âge borné, hors comptes déjà suivis par la relance d'identité (`kyc_reminder_anchor_at` posée).
- `activation/FirstActionReminderScheduler.java` — interrupteur `include-unverified`, fusion des deux listes sans doublon, variante `welcome` pour un compte non vérifié sans intention déclarée.
- `i18n/messages_{fr,en}.properties` — `notification.first-action.welcome.*`.
- `application.yml`, `application-staging.yml`, `.github/workflows/deploy-prod.yml` — `YADONY_ACTIVATION_FIRST_ACTION_REMINDERS_INCLUDE_UNVERIFIED` (défaut `false`, `true` en staging).

## Comment ça fonctionne
1. Toutes les heures (minute 15), même planificateur, même compteur `first_action_reminder_count` (2 envois max au total, 10 h-20 h locales).
2. Si `include-unverified` est vrai, s'ajoutent les comptes non vérifiés créés depuis moins de `unverified-max-age` (7 jours) : J+1 après la création, J+3 au plus tôt 48 h après la première relance.
3. Un compte vérifié entre-temps passe dans le flux historique avec le même compteur : jamais plus de 2 relances.

## Pièges
- Interrupteur éteint par défaut : sans lui, aucun comportement ne change en prod.
- La borne d'âge évite de relancer tout le stock des anciens comptes à l'allumage.
- Un compte en cours de vérification d'identité reçoit déjà J+1/J+3/J+7 sur ce sujet : exclu ici pour ne pas doubler.

## Tests
- `FirstActionReminderSchedulerTest` (12), `ActivationRepositoryIT` (13), `NotificationTextsTest` (58), `NotificationDeeplinkTest` (23) : verts. Suite complète laissée à la CI.

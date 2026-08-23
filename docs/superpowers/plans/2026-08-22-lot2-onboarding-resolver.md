# Lot 2 — Résolveur `nextStep` et jauge d'onboarding · Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development` (recommandé) ou `superpowers:executing-plans` pour dérouler ce plan tâche par tâche. Les étapes sont en cases à cocher (`- [ ]`).

**But :** remplacer l'enchaînement de `if` de `resolvePostSignupRoute` par une fonction pure `nextStep` qui déduit l'étape manquante des seuls faits serveur, et rendre cette déduction visible par une jauge segmentée qui remplace les compteurs `1/4 → 4/4` posés en dur par le lot 1.

**Architecture :** trois fonctions pures dans `features/auth/presentation/` (`onboardingSteps`, `nextStep`, `onboardingProgress`), un composant `DonyOnboardingGauge` dans `core/design/widgets/` qui ne connaît aucune étape métier, et un point de lecture impur unique (`readOnboardingProgress`) appelé par les builders du routeur — jamais par les écrans, qui restent montables sans provider ambiant.

**Pile :** Flutter / flutter_bloc / GoRouter / GetIt / mocktail / bloc_test

**Spec :** `docs/superpowers/specs/2026-08-22-onboarding-progressif-design.md` (§2, §4.1, §4.2, §5, §6, §7, §8)

**Lot précédent :** `docs/superpowers/plans/2026-08-22-lot1-adresse-residence.md` (fusionné, back `ffce1d47`)

---

## Contraintes globales

- **Un seul dépôt cette fois.** Le lot 2 est **entièrement Flutter** (voir Partie A). Une seule branche, une seule PR : `dony_app` → `feature/onboarding-resolver`. La branche `feature/onboarding-resolver` du dépôt back reste vide et n'est pas poussée.
- **Worktree obligatoire.** Travailler dans `/Users/aboubakardiakite/Desktop/dony/dony_app/.claude/worktrees/onboarding-resolver`.
- **Jamais de commit sur `main`.** Jamais de ligne `Co-Authored-By`.
- **Couverture ≥ 90 %.** `flutter test --coverage` avant de marquer le lot terminé.
- **Jamais deux commandes Flutter en parallèle** — même dans deux worktrees différents (cache FVM partagé → `Bad state: Cannot add event while adding stream`). Découper la suite en tranches si besoin.
- **BLoC/Cubit uniquement**, jamais `setState`. **GoRouter uniquement**, jamais `Navigator.push`. Services par GetIt.
- **Design system :** `cs.*`, `DonyColors`, `DonySpacing`, `DonyRadius`, `DonyDuration`, `DonyCurve`, `Theme.of(context).textTheme`. Jamais de `Color(0xFF…)`, jamais de `EdgeInsets.all(16)`, jamais de `GoogleFonts.*` direct.
- **Analytics :** noms déclarés dans `AnalyticsEvents`, `unawaited`, énumération fermée pour `step`, aucune PII.
- **Piège n°1 de ce lot (déjà payé quatre fois au lot précédent, spec §8) :** faire lire un bloc fourni à l'échelle de l'app par un widget existant fait tomber en `ProviderNotFoundException` **tout harnais qui montait ce widget sans ce provider**. Les tests ciblés passent ; seule la suite complète le révèle. La tâche 5 identifie nominativement les trois harnais concernés — les corriger **dans le même commit**.

### Corrections apportées à la spec

Chacune a été vérifiée dans le code avant d'être écrite.

1. **§7 — « le lot 2 contient `onboarding_seen_at` » : c'est faux, tout est déjà livré.** Colonne (`V230__residence_address.sql`), endpoint (`AuthController.java:105` `PUT /auth/me/onboarding-seen`), exposition (`UserResponse.java:31`), lecture Flutter (`user_model.dart` `onboardingSeenAt`), et **pose** effective (`referral_code_screen.dart:44-45`, écran terminal du parcours). Le lot 2 ne fait que **lire** ce champ dans son résolveur.

2. **§4.1 — la signature `nextStep(UserModel user, StripeAccountState stripe, bool analyticsAnswered)` est incomplète.** Deux raisons vérifiées :
   - `user` doit être **nullable** : au routeur, `AuthBloc.state.currentUser` (`auth_state.dart:138`) rend `null` sur `AuthInitial`/`AuthLoading`.
   - il faut un **`countryFallback`** : `POST /auth/register` n'écrit pas `users.country`, et le `UserModel` en cache n'est jamais rafraîchi après l'étape pays — le routeur documente déjà ce trou et applique le repli `context.read<BusinessPrefsBloc>().state.country` (`router.dart:371-383`). Sans ce repli, la jauge afficherait « pays non fait » sur l'écran suivant, juste après que l'utilisateur l'a choisi.

3. **§2 — « étape pays validée par `user.country != null` » est insuffisant comme seule règle de routage.** `CountryOnboardingCubit.skip()` (`country_onboarding_cubit.dart:94`) et `.continueAsSenderOnly()` (`:118`) laissent volontairement `country` à `null` : router uniquement là-dessus renverrait indéfiniment un utilisateur qui a passé l'étape. L'échappatoire est `onboarding_seen_at` (§3.3) : **le résolveur de route teste `onboardingSeenAt != null` en premier et rend `/home`.**

4. **§4.1 — « l'étape 5 disparaît quand `connectAvailableInCountry` est faux » ne se produira jamais pendant le parcours.** `StripeAccountBloc` n'est chargé que par `main_shell.dart:162` (`initState` du shell), et les routes `/auth/*` sont **hors shell** (`router.dart`, commentaire « ── Auth (hors shell) ── »). Pendant tout le post-inscription, l'extension rend donc son repli optimiste `true` (`stripe_account_state.dart:32-36`) et le segment « Paiements » est toujours présent. **Assumé, pas contourné** : recopier en Dart les 22 pays de `StripeConnectCountries.java:25-30` serait une troisième copie d'une contrainte qui vit chez Stripe — le fichier back le dit lui-même (`StripeConnectCountries.java:17-20`).

5. **§4.2 — la jauge ne peut pas reprendre le compteur de `AuthFlowHeader`.** Les deux ne comptent pas la même chose : la pastille compte les **écrans** du tunnel (4, parrainage inclus, identité et paiements absents) ; la jauge compte les **étapes du compte** (4 ou 5, parrainage exclu par §4.2). Décision : les quatre écrans du parcours passent à la jauge et perdent leurs compteurs en dur ; le tunnel pré-compte (`phone_auth_screen.dart:151`, `email_auth_screen.dart:101`, `otp_verification_screen.dart:214`, tous en `total: 3`) **garde `DonyStepPill` inchangé**.

6. **§4.2 — `DonyOnboardingGauge` ne peut pas prendre un `OnboardingStep`.** Aucun fichier de `lib/core/design/widgets/` n'importe `lib/features/**` (vérifié par grep, zéro occurrence). Le composant prend une `List<DonyGaugeSegment>` primitive ; le mappage métier reste dans `features/auth/`.

7. **§4.2 — « animation de remplissage `flutter_animate` » : non.** Un `.animate()` rejoue son entrée à chaque reconstruction, alors qu'il s'agit d'animer *entre deux valeurs*. Le composant frère `DonyStepIndicator` (`dony_step_indicator.dart:64-66`) anime déjà avec `DonyDuration.base` / `DonyCurve.easeOut` ; on suit ce précédent avec `TweenAnimationBuilder`.

8. **§6 — deux events existent déjà, deux autres n'ont aucun déclencheur.** `onboarding_step_skipped` et `residence_address_saved` sont posés par le lot 1 (`analytics_events.dart:330-331`). `onboarding_exited` (croix → accueil) n'a **aucune croix** dans les quatre écrans du parcours, et `onboarding_resumed` suppose la carte de reprise : les deux sont reportés au lot 4.

9. **§4.2 — « chiffres tabulaires comme `dony_price_tag.dart:91` » : vérifié exact.** `fontFeatures: const [FontFeature.tabularFigures()]` s'y trouve bien à cette ligne.

---

# Partie A — `dony-back`

**Le lot 2 ne demande aucune ligne de code backend.** Tout ce que le résolveur lit est déjà exposé par `GET /auth/me` (`AuthController.java:52`, construit à l'unique site `AuthService.toResponse`, `AuthService.java:665-691`) :

| Fait de la spec §2 | Champ `UserResponse` | Preuve | Statut |
|---|---|---|---|
| Consentement analytics | — (endpoint dédié `GET /auth/me/analytics-consent`) | `AuthController.java:85` | déjà là |
| `user.country != null` | `country` | `UserResponse.java:26` ← `AuthService.java:680` | déjà là |
| `kycStatus == VERIFIED` | `kycStatus` (`String`) | `UserResponse.java:20` ← `AuthService.java:675` | déjà là |
| `user.residenceStreet != null` | `residenceStreet` | `UserResponse.java:28` ← `AuthService.java:687` | lot 1 |
| `stripeAccountStatus == ONBOARDING_COMPLETE` | `stripeAccountStatus` | `UserResponse.java:25` ← `AuthService.java:679` | déjà là |
| Parcours déjà vu | `onboardingSeenAt` | `UserResponse.java:31` ← `AuthService.java:690` | lot 1 |

Deux précisions utiles à qui déroule ce plan :

- **L'énumération KYC n'est pas celle que la spec suppose.** `UserEntity.kycStatus` est de type `com.yadony.api.auth.KycStatus` (`KycStatus.java:3`), valeurs `NOT_STARTED, PENDING, VERIFIED, REJECTED` — et **non** `com.yadony.api.kyc.KycVerificationStatus` (`PENDING, VERIFIED, REJECTED`), qui décrit l'entité de vérification, pas l'utilisateur. Côté Flutter c'est une `String` brute avec le getter `UserModel.isKycVerified` (`kycStatus == 'VERIFIED'`) : c'est lui qu'il faut utiliser.
- **`connectAvailableInCountry` n'est pas sur `UserResponse`**, uniquement sur `ConnectAccountResponse` (`ConnectAccountResponse.java:13`, servi par `GET /payments/connect/account`, `PaymentController.java:49`), où il vaut `StripeConnectCountries.isSupported(user.getCountry())` (`PaymentService.java:137`). L'exposer aussi sur `UserResponse` a été envisagé puis **écarté** : au moment de l'inscription `user.country` est encore `null`, donc le champ vaudrait `false` et la jauge **retirerait** le segment « Paiements » à tout le monde à l'étape 1 pour le réajouter après l'étape 2 — pire que le repli optimiste actuel (correction n°4).

Aucune tâche. Passer directement à la Partie B.

---

# Partie B — `dony_app`

### Tâche 1 : `OnboardingStep` et la fonction pure `nextStep`

**Fichiers :**
- Créer : `lib/features/auth/presentation/onboarding_step.dart`
- Test : `test/features/auth/presentation/onboarding_step_test.dart`

**Interfaces :**
- Consomme : `UserModel` (`country`, `isKycVerified`, `residenceStreet`, `stripeAccountStatus`, `onboardingSeenAt`), `StripeAccountState` + son extension `StripeAccountAvailability`.
- Produit : `enum OnboardingStep { consent, country, identity, address, payouts }` avec `wireName` et `route` ; `List<OnboardingStep> onboardingSteps(StripeAccountState)` ; `OnboardingStep? nextStep({...})`.

**Précédent de test à suivre :** `GdprHelper.resolveConsentAction` (`lib/core/services/gdpr_helper.dart:66-76`) — fonction pure rendant une énumération fermée, couverte cas par cas dans `test/core/services/gdpr_helper_test.dart` par des `group`/`test` nommés en français. Second précédent, même forme : `resolveAuthRedirect` (`lib/app/router.dart:238-249`, tests dans `test/app/router_auth_redirect_test.dart`).

**Les combinaisons à couvrir — sept, exhaustives.** Les étapes sont évaluées dans l'ordre ; la première non satisfaite est rendue.

| # | `analyticsAnswered` | pays | KYC | adresse | Connect couvert | statut Stripe | `nextStep` rend |
|---|---|---|---|---|---|---|---|
| 1 | `false` | — | — | — | — | — | `consent` |
| 2 | `true` | `null` | — | — | — | — | `country` |
| 3 | `true` | `FR` | ≠ `VERIFIED` | — | — | — | `identity` |
| 4 | `true` | `FR` | `VERIFIED` | `null` | — | — | `address` |
| 5 | `true` | `FR` | `VERIFIED` | posée | oui | ≠ `ONBOARDING_COMPLETE` | `payouts` |
| 6 | `true` | `SN` | `VERIFIED` | posée | **non** | peu importe | `null` (4 étapes, 4/4 atteignable) |
| 7 | `true` | `FR` | `VERIFIED` | posée | oui | `ONBOARDING_COMPLETE` | `null` |

Deux cas limites en plus, tirés du code réel :

| # | Situation | Attendu | Pourquoi |
|---|---|---|---|
| 8 | `user == null` (état `AuthInitial`) | `consent` si non répondu, sinon `country` | aucun fait serveur connu ⇒ rien n'est fait |
| 9 | `user.country == null` mais `countryFallback == 'FR'` | passe à `identity` | `POST /auth/register` n'écrit pas `users.country` (`router.dart:371-373`) |

- [ ] **Étape 1 : écrire le test qui échoue**

```dart
import 'package:dony/core/models/connect_account_status.dart';
import 'package:dony/features/auth/data/models/user_model.dart';
import 'package:dony/features/auth/presentation/onboarding_step.dart';
import 'package:dony/features/stripe_account/bloc/stripe_account_bloc.dart';
import 'package:flutter_test/flutter_test.dart';

/// Un utilisateur dont chaque fait serveur se pose champ par champ, pour tester
/// une étape à la fois sans construire sept fixtures presque identiques.
UserModel _user({
  String? country,
  String kycStatus = 'NOT_STARTED',
  String? residenceStreet,
  String stripeAccountStatus = 'NOT_CREATED',
  DateTime? onboardingSeenAt,
}) => UserModel(
  id: 'u1',
  country: country,
  kycStatus: kycStatus,
  residenceStreet: residenceStreet,
  stripeAccountStatus: stripeAccountStatus,
  onboardingSeenAt: onboardingSeenAt,
);

const _connectOk = StripeAccountReady(
  ConnectAccountStatus(status: 'NOT_CREATED'),
);
const _connectUnavailable = StripeAccountReady(
  ConnectAccountStatus(status: 'NOT_CREATED', connectAvailableInCountry: false),
);
const _connectDone = StripeAccountReady(
  ConnectAccountStatus(status: 'ONBOARDING_COMPLETE'),
);

void main() {
  group('onboardingSteps — le parrainage n\'entre jamais dans le décompte', () {
    test('pays couvert par Stripe → cinq étapes', () {
      expect(onboardingSteps(_connectOk), const [
        OnboardingStep.consent,
        OnboardingStep.country,
        OnboardingStep.identity,
        OnboardingStep.address,
        OnboardingStep.payouts,
      ]);
    });

    test('pays non couvert → quatre étapes, 4/4 réellement atteignable', () {
      expect(onboardingSteps(_connectUnavailable), const [
        OnboardingStep.consent,
        OnboardingStep.country,
        OnboardingStep.identity,
        OnboardingStep.address,
      ]);
    });

    test('statut non chargé → optimiste, le segment n\'est jamais perdu '
        'par accident réseau', () {
      expect(onboardingSteps(const StripeAccountInitial()).length, 5);
      expect(onboardingSteps(const StripeAccountLoading()).length, 5);
      expect(onboardingSteps(const StripeAccountLoadError()).length, 5);
    });
  });

  group('nextStep — une combinaison d\'états par test', () {
    test('1. consentement jamais répondu → consent', () {
      expect(
        nextStep(
          user: _user(country: 'FR'),
          stripe: _connectOk,
          analyticsAnswered: false,
        ),
        OnboardingStep.consent,
      );
    });

    test('2. pays absent → country', () {
      expect(
        nextStep(user: _user(), stripe: _connectOk, analyticsAnswered: true),
        OnboardingStep.country,
      );
    });

    test('3. identité non vérifiée → identity', () {
      expect(
        nextStep(
          user: _user(country: 'FR', kycStatus: 'PENDING'),
          stripe: _connectOk,
          analyticsAnswered: true,
        ),
        OnboardingStep.identity,
      );
    });

    test('4. adresse de résidence absente → address', () {
      expect(
        nextStep(
          user: _user(country: 'FR', kycStatus: 'VERIFIED'),
          stripe: _connectOk,
          analyticsAnswered: true,
        ),
        OnboardingStep.address,
      );
    });

    test('5. compte de paiement incomplet → payouts', () {
      expect(
        nextStep(
          user: _user(
            country: 'FR',
            kycStatus: 'VERIFIED',
            residenceStreet: '12 rue des Lilas',
          ),
          stripe: _connectOk,
          analyticsAnswered: true,
        ),
        OnboardingStep.payouts,
      );
    });

    test('6. pays hors couverture Stripe → null, l\'étape paiements n\'existe '
        'pas pour lui', () {
      expect(
        nextStep(
          user: _user(
            country: 'SN',
            kycStatus: 'VERIFIED',
            residenceStreet: '12 rue des Lilas',
          ),
          stripe: _connectUnavailable,
          analyticsAnswered: true,
        ),
        isNull,
      );
    });

    test('7. tout est fait → null', () {
      expect(
        nextStep(
          user: _user(
            country: 'FR',
            kycStatus: 'VERIFIED',
            residenceStreet: '12 rue des Lilas',
            stripeAccountStatus: 'ONBOARDING_COMPLETE',
          ),
          stripe: _connectDone,
          analyticsAnswered: true,
        ),
        isNull,
      );
    });

    test('8. utilisateur non chargé → aucun fait serveur connu', () {
      expect(
        nextStep(user: null, stripe: _connectOk, analyticsAnswered: false),
        OnboardingStep.consent,
      );
      expect(
        nextStep(user: null, stripe: _connectOk, analyticsAnswered: true),
        OnboardingStep.country,
      );
    });

    test('9. le pays fraîchement choisi vient du repli : POST /auth/register '
        'n\'écrit pas users.country', () {
      expect(
        nextStep(
          user: _user(kycStatus: 'VERIFIED'),
          stripe: _connectOk,
          analyticsAnswered: true,
          countryFallback: 'FR',
        ),
        OnboardingStep.address,
      );
    });

    test('un pays vide ne vaut pas un pays', () {
      expect(
        nextStep(
          user: _user(country: ''),
          stripe: _connectOk,
          analyticsAnswered: true,
          countryFallback: '  ',
        ),
        OnboardingStep.country,
      );
    });

    test('le statut Stripe du UserModel sert tant que le bloc n\'a pas chargé',
        () {
      expect(
        nextStep(
          user: _user(
            country: 'FR',
            kycStatus: 'VERIFIED',
            residenceStreet: '12 rue des Lilas',
            stripeAccountStatus: 'ONBOARDING_COMPLETE',
          ),
          stripe: const StripeAccountInitial(),
          analyticsAnswered: true,
        ),
        isNull,
      );
    });
  });

  group('OnboardingStep — énumération fermée', () {
    test('chaque étape porte un wireName snake_case et une route existante', () {
      expect(OnboardingStep.consent.wireName, 'consent');
      expect(OnboardingStep.country.wireName, 'country');
      expect(OnboardingStep.identity.wireName, 'identity');
      expect(OnboardingStep.address.wireName, 'address');
      expect(OnboardingStep.payouts.wireName, 'payouts');

      expect(OnboardingStep.consent.route, '/auth/analytics-consent');
      expect(OnboardingStep.country.route, '/auth/country-selection');
      expect(OnboardingStep.identity.route, '/kyc/verify');
      expect(OnboardingStep.address.route, '/auth/residence-address');
      expect(OnboardingStep.payouts.route, '/payments/onboarding');
    });
  });
}
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/presentation/onboarding_step_test.dart
```
Attendu : `Target of URI doesn't exist: 'package:dony/features/auth/presentation/onboarding_step.dart'`.

- [ ] **Étape 3 : implémenter**

```dart
import 'package:dony/features/auth/data/models/user_model.dart';
import 'package:dony/features/stripe_account/bloc/stripe_account_bloc.dart';

/// Étapes **comptées** de l'onboarding progressif.
///
/// Le parrainage n'en fait pas partie : il n'apporte rien au compte et reste
/// facultatif ; l'y compter ferait stagner un compteur que l'utilisateur croit
/// devoir remplir (spec §4.2).
///
/// Aucune progression n'est stockée : chaque étape est « faite » si le fait
/// serveur correspondant existe déjà (spec §2). C'est ce qui rend l'état juste
/// sans une ligne de synchronisation quand la vérification d'identité est faite
/// depuis le profil, quand l'onboarding Connect est terminé depuis le lien reçu
/// par e-mail, ou après une réinstallation.
enum OnboardingStep {
  consent('consent', '/auth/analytics-consent'),
  country('country', '/auth/country-selection'),
  identity('identity', '/kyc/verify'),
  address('address', '/auth/residence-address'),
  payouts('payouts', '/payments/onboarding');

  const OnboardingStep(this.wireName, this.route);

  /// Valeur envoyée dans les properties analytics. Énumération fermée, jamais
  /// de texte libre (spec §6).
  final String wireName;

  /// Route GoRouter de l'écran qui remplit l'étape.
  final String route;
}

/// Les étapes comptées pour cet utilisateur, dans l'ordre.
///
/// « Paiements » disparaît quand Stripe n'ouvre pas de compte connecté dans le
/// pays : la jauge passe à quatre segments et l'utilisateur atteint réellement
/// 4/4. `connectAvailableInCountry` est optimiste tant que le statut n'est pas
/// chargé (`stripe_account_state.dart`), donc un segment n'est jamais perdu par
/// accident réseau.
List<OnboardingStep> onboardingSteps(StripeAccountState stripe) => [
  OnboardingStep.consent,
  OnboardingStep.country,
  OnboardingStep.identity,
  OnboardingStep.address,
  if (stripe.connectAvailableInCountry) OnboardingStep.payouts,
];

/// Première étape non satisfaite, ou `null` si le compte est complet.
///
/// Fonction **pure** : ni réseau, ni widget, ni `BuildContext`. Un test par
/// combinaison d'états.
///
/// [user] est nullable : au routeur, `AuthBloc.state.currentUser` rend `null`
/// sur `AuthInitial`/`AuthLoading`.
///
/// [countryFallback] est indispensable et non optionnel dans les faits :
/// `POST /auth/register` n'écrit pas `users.country`, et le `UserModel` en
/// cache n'est jamais rafraîchi après l'étape pays. Le routeur applique déjà
/// ce même repli (`BusinessPrefsBloc.state.country`) pour l'écran d'adresse.
OnboardingStep? nextStep({
  required UserModel? user,
  required StripeAccountState stripe,
  required bool analyticsAnswered,
  String? countryFallback,
}) {
  for (final step in onboardingSteps(stripe)) {
    final done = switch (step) {
      OnboardingStep.consent => analyticsAnswered,
      OnboardingStep.country =>
        _hasText(user?.country) || _hasText(countryFallback),
      OnboardingStep.identity => user?.isKycVerified ?? false,
      OnboardingStep.address => _hasText(user?.residenceStreet),
      OnboardingStep.payouts =>
        _stripeStatus(user, stripe) == 'ONBOARDING_COMPLETE',
    };
    if (!done) return step;
  }
  return null;
}

/// Le statut du bloc fait foi quand il est chargé ; sinon celui porté par le
/// profil. Sans ce repli, le résolveur serait aveugle pendant tout le parcours
/// post-inscription : `StripeAccountBloc` n'est chargé que par
/// `MainShell.initState`, et les routes `/auth/*` sont hors shell.
String _stripeStatus(UserModel? user, StripeAccountState stripe) =>
    switch (stripe) {
      StripeAccountReady(:final accountStatus) => accountStatus.status,
      _ => user?.stripeAccountStatus ?? 'NOT_CREATED',
    };

bool _hasText(String? value) => value != null && value.trim().isNotEmpty;
```

- [ ] **Étape 4 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/presentation/onboarding_step_test.dart
```
Attendu : `All tests passed!`

- [ ] **Étape 5 : commit**

```bash
git add lib/features/auth/presentation/onboarding_step.dart \
        test/features/auth/presentation/onboarding_step_test.dart
git commit -m "feat(auth): déduit l'étape d'onboarding manquante des seuls faits serveur"
```

---

### Tâche 2 : `OnboardingProgress`, la vue jauge de l'état

**Fichiers :**
- Modifier : `lib/features/auth/presentation/onboarding_step.dart`
- Modifier : `test/features/auth/presentation/onboarding_step_test.dart`
- Dépend de : tâche 3 pour le type `DonyGaugeSegment` → **faire la tâche 3 avant cette tâche** si l'ordre gêne ; sinon déclarer d'abord l'enum dans la tâche 3 et revenir ici.

**Interfaces :**
- Produit : `class OnboardingProgress { steps, done, current, doneCount, total, segments }` et `OnboardingProgress onboardingProgress({...})`.
- Consomme : `DonyGaugeSegment` (tâche 3).

> **Ordre imposé :** exécuter la **tâche 3 avant la tâche 2**. `OnboardingProgress.segments` rend une `List<DonyGaugeSegment>`, type défini par le composant. Les tâches sont numérotées dans l'ordre de lecture, pas d'exécution.

- [ ] **Étape 1 : écrire le test qui échoue** (à ajouter à la fin de `onboarding_step_test.dart`, avec `import 'package:dony/core/design/design_system.dart';` en tête)

```dart
  group('onboardingProgress — ce que la jauge doit montrer', () {
    test('une étape faite est pleine, l\'étape en cours est à moitié, '
        'le reste est vide', () {
      final p = onboardingProgress(
        user: _user(country: 'FR'),
        stripe: _connectOk,
        analyticsAnswered: true,
        current: OnboardingStep.identity,
      );

      expect(p.total, 5);
      expect(p.doneCount, 2);
      expect(p.segments, const [
        DonyGaugeSegment.done, // consentement
        DonyGaugeSegment.done, // pays
        DonyGaugeSegment.current, // identité
        DonyGaugeSegment.todo, // adresse
        DonyGaugeSegment.todo, // paiements
      ]);
    });

    test('une étape passée reste vide — passer n\'est pas terminer', () {
      // L'utilisateur a passé le pays (skip) et se trouve sur l'adresse :
      // le segment « pays » ne doit pas se remplir pour autant.
      final p = onboardingProgress(
        user: _user(),
        stripe: _connectOk,
        analyticsAnswered: true,
        current: OnboardingStep.address,
      );

      expect(p.segments, const [
        DonyGaugeSegment.done,
        DonyGaugeSegment.todo, // pays passé, donc vide
        DonyGaugeSegment.todo,
        DonyGaugeSegment.current,
        DonyGaugeSegment.todo,
      ]);
    });

    test('sans étape en cours (écran parrainage, hors décompte) aucun segment '
        'n\'est à moitié', () {
      final p = onboardingProgress(
        user: _user(country: 'FR'),
        stripe: _connectOk,
        analyticsAnswered: true,
      );

      expect(p.current, isNull);
      expect(p.segments.contains(DonyGaugeSegment.current), isFalse);
      expect(p.doneCount, 2);
    });

    test('pays hors couverture Stripe → quatre segments', () {
      final p = onboardingProgress(
        user: _user(
          country: 'SN',
          kycStatus: 'VERIFIED',
          residenceStreet: '12 rue des Lilas',
        ),
        stripe: _connectUnavailable,
        analyticsAnswered: true,
      );

      expect(p.total, 4);
      expect(p.doneCount, 4);
      expect(p.segments, const [
        DonyGaugeSegment.done,
        DonyGaugeSegment.done,
        DonyGaugeSegment.done,
        DonyGaugeSegment.done,
      ]);
    });

    test('une étape déjà faite reste pleine même si elle est l\'étape '
        'en cours', () {
      final p = onboardingProgress(
        user: _user(country: 'FR'),
        stripe: _connectOk,
        analyticsAnswered: true,
        current: OnboardingStep.country,
      );

      expect(p.segments[1], DonyGaugeSegment.done);
    });
  });
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/presentation/onboarding_step_test.dart
```
Attendu : `The function 'onboardingProgress' isn't defined`.

- [ ] **Étape 3 : implémenter** (à la suite de `nextStep` dans `onboarding_step.dart`)

```dart
/// L'état de l'onboarding tel que la jauge doit le montrer : lesquelles sont
/// faites, laquelle est en cours, combien il en reste.
class OnboardingProgress {
  const OnboardingProgress({
    required this.steps,
    required this.done,
    this.current,
  });

  /// Les étapes comptées, dans l'ordre. Quatre ou cinq selon la couverture
  /// Stripe du pays.
  final List<OnboardingStep> steps;

  /// Celles dont le fait serveur existe.
  final Set<OnboardingStep> done;

  /// L'étape que l'écran courant remplit, ou `null` sur un écran hors décompte
  /// (parrainage).
  final OnboardingStep? current;

  int get total => steps.length;
  int get doneCount => done.length;

  /// Traduction pour `DonyOnboardingGauge`, qui ne connaît aucune étape métier.
  ///
  /// Une étape **passée reste vide** : passer n'est pas terminer (spec §4.2).
  List<DonyGaugeSegment> get segments => [
    for (final step in steps)
      if (done.contains(step))
        DonyGaugeSegment.done
      else if (step == current)
        DonyGaugeSegment.current
      else
        DonyGaugeSegment.todo,
  ];
}

/// Même déduction que [nextStep], mais rendue en entier plutôt qu'arrêtée à la
/// première étape manquante. Pure, mêmes arguments.
OnboardingProgress onboardingProgress({
  required UserModel? user,
  required StripeAccountState stripe,
  required bool analyticsAnswered,
  String? countryFallback,
  OnboardingStep? current,
}) {
  final steps = onboardingSteps(stripe);
  return OnboardingProgress(
    steps: steps,
    done: {
      for (final step in steps)
        if (_isDone(
          step,
          user: user,
          stripe: stripe,
          analyticsAnswered: analyticsAnswered,
          countryFallback: countryFallback,
        ))
          step,
    },
    current: current,
  );
}
```

Puis **factoriser** : extraire le `switch` de `nextStep` dans `_isDone`, et faire appeler `_isDone` par `nextStep` — les deux fonctions doivent partager exactement la même règle, sinon la jauge et la redirection finiraient par se contredire, ce que la spec §4.1 veut précisément éviter.

```dart
bool _isDone(
  OnboardingStep step, {
  required UserModel? user,
  required StripeAccountState stripe,
  required bool analyticsAnswered,
  String? countryFallback,
}) => switch (step) {
  OnboardingStep.consent => analyticsAnswered,
  OnboardingStep.country =>
    _hasText(user?.country) || _hasText(countryFallback),
  OnboardingStep.identity => user?.isKycVerified ?? false,
  OnboardingStep.address => _hasText(user?.residenceStreet),
  OnboardingStep.payouts =>
    _stripeStatus(user, stripe) == 'ONBOARDING_COMPLETE',
};
```

Et `nextStep` devient :

```dart
  for (final step in onboardingSteps(stripe)) {
    if (!_isDone(
      step,
      user: user,
      stripe: stripe,
      analyticsAnswered: analyticsAnswered,
      countryFallback: countryFallback,
    )) {
      return step;
    }
  }
  return null;
```

- [ ] **Étape 4 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/presentation/onboarding_step_test.dart
```
Attendu : `All tests passed!` — les tests de la tâche 1 doivent rester verts après la factorisation.

- [ ] **Étape 5 : commit**

```bash
git add lib/features/auth/presentation/onboarding_step.dart \
        test/features/auth/presentation/onboarding_step_test.dart
git commit -m "feat(auth): expose la progression complète pour la jauge d'onboarding"
```

---

### Tâche 3 : le composant `DonyOnboardingGauge`

> **À exécuter avant la tâche 2** (elle consomme `DonyGaugeSegment`).

**Fichiers :**
- Créer : `lib/core/design/widgets/dony_onboarding_gauge.dart`
- Modifier : `lib/core/design/design_system.dart` (ajouter l'export, à sa place alphabétique entre `dony_mascotte.dart` et `dony_page_scaffold.dart`)
- Test : `test/core/design/widgets/dony_onboarding_gauge_test.dart`

**Interfaces :**
- Produit : `enum DonyGaugeSegment { done, current, todo }` et `DonyOnboardingGauge({required List<DonyGaugeSegment> segments, required String label})`.
- Consomme : uniquement des tokens. **Aucun import de `lib/features/**`** — cette règle est tenue par tous les fichiers du dossier (vérifié : zéro occurrence).

**Contraintes non négociables :**
- couleurs par `cs.primary` / `cs.outline` — mêmes rôles que le composant frère `_BarIndicator` (`dony_step_indicator.dart:98-101`), jamais de `Color(0xFF…)` ;
- compteur en chiffres tabulaires : `fontFeatures: const [FontFeature.tabularFigures()]`, comme `dony_price_tag.dart:91` ;
- animation par `TweenAnimationBuilder` + `DonyDuration.base` + `DonyCurve.easeOut` — pas `flutter_animate` (correction n°7) ;
- `Semantics` porteur de l'information : l'état ne doit pas passer que par la couleur.

- [ ] **Étape 1 : écrire le test qui échoue**

```dart
import 'package:dony/core/design/design_system.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(
  theme: AppTheme.light(),
  home: Scaffold(body: Center(child: child)),
);

void main() {
  group('DonyOnboardingGauge', () {
    testWidgets('le compteur ne compte que les étapes terminées', (
      tester,
    ) async {
      await tester.pumpWidget(
        _wrap(
          const DonyOnboardingGauge(
            segments: [
              DonyGaugeSegment.done,
              DonyGaugeSegment.done,
              DonyGaugeSegment.current,
              DonyGaugeSegment.todo,
              DonyGaugeSegment.todo,
            ],
            label: 'Identité',
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('2 / 5 · Identité'), findsOneWidget);
    });

    testWidgets('un segment par étape', (tester) async {
      await tester.pumpWidget(
        _wrap(
          const DonyOnboardingGauge(
            segments: [
              DonyGaugeSegment.done,
              DonyGaugeSegment.current,
              DonyGaugeSegment.todo,
              DonyGaugeSegment.todo,
            ],
            label: 'Pays',
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(TweenAnimationBuilder<double>), findsNWidgets(4));
    });

    testWidgets('l\'information ne passe pas que par la couleur', (
      tester,
    ) async {
      await tester.pumpWidget(
        _wrap(
          const DonyOnboardingGauge(
            segments: [
              DonyGaugeSegment.done,
              DonyGaugeSegment.done,
              DonyGaugeSegment.current,
              DonyGaugeSegment.todo,
              DonyGaugeSegment.todo,
            ],
            label: 'Identité',
          ),
        ),
      );
      await tester.pumpAndSettle();

      final semantics = tester.getSemantics(find.byType(DonyOnboardingGauge));
      expect(semantics.value, 'Étape 3 sur 5, 2 terminées');
    });

    testWidgets('sans étape en cours, la lecture porte sur le total atteint', (
      tester,
    ) async {
      await tester.pumpWidget(
        _wrap(
          const DonyOnboardingGauge(
            segments: [DonyGaugeSegment.done, DonyGaugeSegment.todo],
            label: 'Parrainage',
          ),
        ),
      );
      await tester.pumpAndSettle();

      final semantics = tester.getSemantics(find.byType(DonyOnboardingGauge));
      expect(semantics.value, '1 étape sur 2 terminée');
    });

    testWidgets('tient à 200 % de taille de texte sans déborder', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(2)),
          child: _wrap(
            const DonyOnboardingGauge(
              segments: [
                DonyGaugeSegment.done,
                DonyGaugeSegment.current,
                DonyGaugeSegment.todo,
                DonyGaugeSegment.todo,
                DonyGaugeSegment.todo,
              ],
              label: 'Confidentialité',
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });
  });
}
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/core/design/widgets/dony_onboarding_gauge_test.dart
```
Attendu : `Undefined name 'DonyOnboardingGauge'`.

- [ ] **Étape 3 : implémenter le composant**

```dart
import 'dart:ui' show FontFeature;

import 'package:dony/core/design/design_system.dart';
import 'package:flutter/material.dart';

/// État d'un segment de [DonyOnboardingGauge].
enum DonyGaugeSegment {
  /// Le fait serveur existe : segment plein.
  done,

  /// Étape en cours : segment à demi rempli.
  current,

  /// Reste à faire — y compris une étape **passée** : passer n'est pas terminer.
  todo,
}

/// Jauge segmentée de l'onboarding progressif.
///
/// Segmentée et non continue : un segment par étape, pour montrer *lesquelles*
/// sont faites et pas seulement combien.
///
/// Ne connaît aucune étape métier — `lib/core/design/widgets/` n'importe jamais
/// `lib/features/**`. Le mappage se fait dans
/// `features/auth/presentation/onboarding_step.dart`
/// (`OnboardingProgress.segments`).
class DonyOnboardingGauge extends StatelessWidget {
  const DonyOnboardingGauge({
    super.key,
    required this.segments,
    required this.label,
  });

  final List<DonyGaugeSegment> segments;

  /// Nom de l'étape en cours, ou du contexte quand aucune ne l'est.
  final String label;

  int get _doneCount =>
      segments.where((s) => s == DonyGaugeSegment.done).length;

  String _semanticsValue() {
    final total = segments.length;
    final index = segments.indexOf(DonyGaugeSegment.current);
    final plural = _doneCount > 1 ? 's' : '';
    if (index >= 0) {
      return 'Étape ${index + 1} sur $total, $_doneCount terminée$plural';
    }
    return '$_doneCount étape$plural sur $total terminée$plural';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final total = segments.length;

    return Semantics(
      container: true,
      value: _semanticsValue(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '$_doneCount / $total · $label',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: tt.labelSmall?.copyWith(
              color: cs.primary,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.3,
              // Chiffres tabulaires : le compteur ne doit pas se décaler
              // quand il passe de 1 à 4 (cf. dony_price_tag.dart).
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: DonySpacing.xs),
          Row(
            children: [
              for (var i = 0; i < total; i++)
                Expanded(
                  child: Padding(
                    padding: EdgeInsets.only(
                      right: i < total - 1 ? DonySpacing.xs : 0,
                    ),
                    child: _GaugeSegment(state: segments[i]),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _GaugeSegment extends StatelessWidget {
  const _GaugeSegment({required this.state});

  final DonyGaugeSegment state;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    final fill = switch (state) {
      DonyGaugeSegment.done => 1.0,
      DonyGaugeSegment.current => 0.5,
      DonyGaugeSegment.todo => 0.0,
    };

    // Anime *entre deux valeurs* et non à l'entrée : un `.animate()` de
    // flutter_animate rejouerait son entrée à chaque reconstruction. Même
    // parti pris que le composant frère DonyStepIndicator.
    return TweenAnimationBuilder<double>(
      tween: Tween<double>(begin: 0, end: fill),
      duration: DonyDuration.base,
      curve: DonyCurve.easeOut,
      builder: (context, value, _) => Container(
        height: 6,
        decoration: BoxDecoration(
          // Le fond reste visible pour que le nombre total de segments se lise
          // même quand rien n'est fait.
          color: cs.outline,
          borderRadius: BorderRadius.circular(DonyRadius.full),
        ),
        child: FractionallySizedBox(
          alignment: Alignment.centerLeft,
          widthFactor: value,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: cs.primary,
              borderRadius: BorderRadius.circular(DonyRadius.full),
            ),
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Étape 4 : exporter dans le barrel**

Dans `lib/core/design/design_system.dart`, entre `dony_mascotte.dart` et `dony_page_scaffold.dart` :

```dart
export 'package:dony/core/design/widgets/dony_onboarding_gauge.dart';
```

- [ ] **Étape 5 : relancer, vérifier que ça passe**

```bash
flutter test test/core/design/widgets/dony_onboarding_gauge_test.dart
```
Attendu : `All tests passed!`

- [ ] **Étape 6 : commit**

```bash
git add lib/core/design/widgets/dony_onboarding_gauge.dart \
        lib/core/design/design_system.dart \
        test/core/design/widgets/dony_onboarding_gauge_test.dart
git commit -m "feat(design): jauge segmentée d'onboarding, une étape par segment"
```

---

### Tâche 4 : brancher la jauge dans les quatre écrans du parcours

**Fichiers :**
- Modifier : `lib/features/auth/presentation/widgets/auth_flow_chrome.dart` (`AuthFlowHeader`, lignes 55-112)
- Modifier : `lib/features/auth/presentation/onboarding_step.dart` (ajouter `readOnboardingProgress`)
- Modifier : `lib/features/auth/presentation/screens/analytics_consent_screen.dart` (ligne 44)
- Modifier : `lib/features/auth/presentation/screens/country_selection_screen.dart` (ligne 92)
- Modifier : `lib/features/auth/presentation/screens/residence_address_screen.dart` (ligne 117)
- Modifier : `lib/features/auth/presentation/screens/referral_code_screen.dart` (lignes 135 **et** 239 — deux chemins de build)
- Modifier : `lib/app/router.dart` (lignes 359-400)
- Modifier : `test/features/auth/presentation/screens/country_selection_screen_test.dart:39`
- Modifier : `test/features/auth/presentation/residence_address_screen_test.dart:22`
- Modifier : `test/features/auth/presentation/screens/referral_code_screen_test.dart:26`
- Modifier : `test/a11y/large_text_smoke_test.dart:822`

**Interfaces :**
- Produit : `AuthFlowHeader.gauge({required List<DonyGaugeSegment> segments, required String label, bool showBack})` ; un paramètre `progress` sur les quatre écrans ; `OnboardingProgress readOnboardingProgress(BuildContext, {OnboardingStep? current})`.
- Consomme : `OnboardingProgress.segments` (tâche 2).

**Pourquoi remplacer les compteurs en dur.** Ils comptent les **écrans** du tunnel (`1/4` → `4/4`, parrainage inclus), la jauge compte les **étapes du compte** (4 ou 5, parrainage exclu, identité et paiements inclus). Les deux ne peuvent pas partager un chiffre. Et dès que `nextStep` pilote l'entrée du parcours (tâche 5), le tunnel n'est plus une suite figée de quatre écrans : un utilisateur qui entre directement à l'étape pays verrait toujours « 2 / 4 » alors que rien ne précède. **Aucun test n'assertait sur ces compteurs** (vérifié : aucune occurrence de `sur 4`, `Étape`, `current:`, `total:` dans les quatre fichiers de test concernés) — seule la construction des écrans est à mettre à jour.

**Le tunnel pré-compte n'est pas touché.** `phone_auth_screen.dart:151`, `email_auth_screen.dart:101` et `otp_verification_screen.dart:214` utilisent le même `AuthFlowHeader` avec `total: 3` : ils gardent `DonyStepPill` par le constructeur par défaut.

**Les écrans ne lisent aucun bloc ambiant.** `residence_address_screen.dart:17-19` pose explicitement la règle : « injecté par le routeur — jamais lu directement ici via ce Bloc pour que cet écran reste testable sans provider ambiant ». On la suit : `readOnboardingProgress` n'est appelé que par les builders du routeur.

- [ ] **Étape 1 : écrire le test qui échoue**

Ajouter à `test/features/auth/presentation/residence_address_screen_test.dart` :

```dart
  testWidgets('la jauge remplace le compteur en dur du tunnel', (tester) async {
    await tester.pumpWidget(_wrap(cubit));
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.byType(DonyOnboardingGauge), findsOneWidget);
    expect(find.byType(DonyStepPill), findsNothing);
    expect(find.text('2 / 5 · Adresse'), findsOneWidget);
  });
```

et remplacer le harnais `_wrap` du même fichier :

```dart
const _progress = OnboardingProgress(
  steps: [
    OnboardingStep.consent,
    OnboardingStep.country,
    OnboardingStep.identity,
    OnboardingStep.address,
    OnboardingStep.payouts,
  ],
  done: {OnboardingStep.consent, OnboardingStep.country},
  current: OnboardingStep.address,
);

// … dans le GoRoute :
            child: const ResidenceAddressScreen(progress: _progress),
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/presentation/residence_address_screen_test.dart
```
Attendu : `No named parameter with the name 'progress'`.

- [ ] **Étape 3 : ajouter le constructeur nommé à `AuthFlowHeader`**

```dart
class AuthFlowHeader extends StatelessWidget {
  /// Tunnel pré-compte (téléphone, e-mail, code) : une pastille « n / total ».
  const AuthFlowHeader({
    super.key,
    required int this.current,
    required int this.total,
    required this.label,
    this.showBack = true,
  }) : segments = null;

  /// Parcours d'onboarding progressif : la jauge remplace la pastille.
  ///
  /// Les deux ne comptent pas la même chose. La pastille compte les écrans du
  /// tunnel d'inscription ; la jauge compte les étapes du compte (quatre ou
  /// cinq selon la couverture Stripe du pays, parrainage exclu).
  const AuthFlowHeader.gauge({
    super.key,
    required List<DonyGaugeSegment> this.segments,
    required this.label,
    this.showBack = false,
  }) : current = null,
       total = null;

  final int? current;
  final int? total;
  final List<DonyGaugeSegment>? segments;
  final String label;
  final bool showBack;
```

Et remplacer le bloc `Align` final de `build` (lignes 104-108) par :

```dart
        if (segments == null)
          Align(
            alignment: Alignment.centerRight,
            child: DonyStepPill(
              current: current!,
              total: total!,
              label: label,
            ),
          )
        else
          // Le `Column` parent est en `CrossAxisAlignment.stretch` : la jauge
          // prend la largeur, son propre `Column` aligne le compteur à droite
          // comme le faisait la pastille.
          DonyOnboardingGauge(segments: segments!, label: label),
```

- [ ] **Étape 4 : ajouter `progress` aux quatre écrans**

`analytics_consent_screen.dart` — la classe est un `StatelessWidget` :

```dart
class AnalyticsConsentScreen extends StatelessWidget {
  const AnalyticsConsentScreen({super.key, required this.progress});

  final OnboardingProgress progress;
```
puis ligne 44 :
```dart
                    AuthFlowHeader.gauge(
                      segments: progress.segments,
                      label: 'Confidentialité',
                    ),
```

> Corriger au passage le commentaire de classe (lignes 12-14), périmé : il dit « après le PIN setup » et « les deux choix naviguent vers /home », alors que `_respond` va vers `/auth/country-selection` (ligne 205) et que l'étape PIN a quitté l'inscription.

`country_selection_screen.dart` — même chose, `label: 'Pays'`, ligne 92.

`residence_address_screen.dart` — ajouter `required this.progress` **à côté** de `country` (garder `country`, il sert au champ verrouillé), `label: 'Adresse'`, ligne 117.

`referral_code_screen.dart` — `label: 'Parrainage'`, **aux deux sites** (135 et 239). Le parrainage est hors décompte : `progress.current` y vaut `null`, donc aucun segment n'est à moitié.

- [ ] **Étape 5 : ajouter le point de lecture impur**

À la fin de `lib/features/auth/presentation/onboarding_step.dart`, sous un séparateur explicite :

```dart
// ─── Lecture de l'état ambiant ───────────────────────────────────────────────
//
// Seul point impur du fichier. Il vit ici plutôt que dans `router.dart` pour
// rester à côté de la règle qu'il applique, et il n'est appelé que par les
// builders du routeur : les écrans du parcours restent montables sans provider
// ambiant (cf. `residence_address_screen.dart`).

/// Lit les blocs fournis à l'échelle de l'application et rend la progression.
OnboardingProgress readOnboardingProgress(
  BuildContext context, {
  OnboardingStep? current,
}) => onboardingProgress(
  user: context.read<AuthBloc>().state.currentUser,
  stripe: context.read<StripeAccountBloc>().state,
  analyticsAnswered:
      !getIt<AnalyticsService>().isConfigured ||
      getIt<AnalyticsService>().hasAnswered,
  // `POST /auth/register` n'écrit pas `users.country` et le profil en cache
  // n'est pas rafraîchi après l'étape pays : même repli que `router.dart` pour
  // l'écran d'adresse.
  countryFallback: context.read<BusinessPrefsBloc>().state.country,
  current: current,
);
```

Imports à ajouter en tête du fichier : `flutter/widgets.dart`, `flutter_bloc`, `core/di/injection.dart`, `core/services/analytics_service.dart`, `features/auth/bloc/auth_bloc.dart`, `features/auth/bloc/auth_state.dart`, `features/settings/bloc/business_prefs_bloc.dart` (chemin exact à confirmer par `grep -rn "class BusinessPrefsBloc" lib/`).

- [ ] **Étape 6 : câbler les quatre routes**

Dans `router.dart`, lignes 359-400 :

```dart
    GoRoute(
      path: '/auth/country-selection',
      builder: (context, state) => BlocProvider(
        create: (_) => getIt<CountryOnboardingCubit>(),
        child: CountrySelectionScreen(
          progress: readOnboardingProgress(
            context,
            current: OnboardingStep.country,
          ),
        ),
      ),
    ),
    GoRoute(
      path: '/auth/residence-address',
      builder: (context, state) => BlocProvider(
        create: (_) => getIt<ResidenceAddressCubit>(),
        child: ResidenceAddressScreen(
          country:
              (state.extra as String?) ??
              context.read<BusinessPrefsBloc>().state.country,
          progress: readOnboardingProgress(
            context,
            current: OnboardingStep.address,
          ),
        ),
      ),
    ),
    GoRoute(
      path: '/auth/referral-code',
      builder: (context, state) => BlocProvider(
        create: (_) => ReferralBloc(
          getIt<ReferralRepository>(),
          getIt<AnalyticsService>(),
        ),
        // Aucune étape en cours : le parrainage est hors décompte (spec §4.2).
        child: ReferralCodeScreen(progress: readOnboardingProgress(context)),
      ),
    ),
    GoRoute(
      path: '/auth/analytics-consent',
      builder: (context, state) => AnalyticsConsentScreen(
        progress: readOnboardingProgress(
          context,
          current: OnboardingStep.consent,
        ),
      ),
    ),
```

Conserver **mot pour mot** le commentaire existant de `router.dart:371-379` qui explique le repli `country` : il documente un piège toujours actif.

- [ ] **Étape 7 : mettre à jour les trois autres harnais**

Même fixture `_progress` que l'étape 1, adaptée à l'étape courante :
- `test/features/auth/presentation/screens/country_selection_screen_test.dart:39` → `current: OnboardingStep.country` ;
- `test/features/auth/presentation/screens/referral_code_screen_test.dart:26` → `current: null` ;
- `test/a11y/large_text_smoke_test.dart:822` → `ResidenceAddressScreen(country: 'SN', progress: _progress)` — attention, ce cas simule un pays hors couverture Stripe : y poser une progression **à quatre segments** (sans `OnboardingStep.payouts`) pour que le test couvre vraiment la variante réduite.

- [ ] **Étape 8 : relancer les tests ciblés**

```bash
flutter test test/features/auth/presentation/ test/core/design/widgets/dony_onboarding_gauge_test.dart test/a11y/
```
Attendu : `All tests passed!`

- [ ] **Étape 9 : commit**

```bash
git add lib/features/auth lib/app/router.dart lib/core/design \
        test/features/auth test/a11y test/core/design
git commit -m "feat(auth): la jauge d'onboarding remplace les compteurs figés du tunnel"
```

---

### Tâche 5 : brancher `nextStep` sur la redirection post-inscription

**Fichiers :**
- Modifier : `lib/features/auth/presentation/post_signup_route.dart` (réécriture complète)
- Modifier : `lib/features/auth/presentation/screens/otp_verification_screen.dart` (lignes 115-126, 172, 182)
- Modifier : `lib/features/auth/presentation/screens/auth_method_screen.dart` (lignes 26-38, 44-45)
- Modifier : `test/features/auth/presentation/post_signup_route_test.dart` (réécriture des trois `group`)
- Modifier : `test/features/auth/presentation/screens/otp_verification_screen_test.dart`
- Modifier : `test/features/auth/presentation/screens/otp_verification_screen_email_test.dart`
- Vérifier : `test/features/auth/presentation/screens/auth_method_screen_test.dart`

**Interfaces :**
- Consomme : `nextStep` (tâche 1), `AnalyticsService.syncFromBackend/hasAnswered/isConfigured`.
- Produit : `Future<String> resolvePostSignupRoute({required AnalyticsService analytics, required UserModel? user, required StripeAccountState stripe, String? countryFallback})`.

**Combien d'appelants réels ?** Deux aujourd'hui — `otp_verification_screen.dart:119` et `auth_method_screen.dart:26` — mais **un seul chemin de décision**. La spec §4.1 annonce « deux appelants, la redirection post-inscription et la carte de reprise » : la carte de reprise est du lot 4. Le lot 2 ne prévoit **aucune interface supplémentaire** pour elle : `nextStep` est déjà une fonction pure à quatre arguments nommés, la carte l'appellera telle quelle. Rien à anticiper, rien à sur-concevoir.

> **PIÈGE VÉRIFIÉ — à traiter dans ce commit.** Faire lire `StripeAccountBloc` et `BusinessPrefsBloc` par `_continueAfterSignup` fera tomber en `ProviderNotFoundException` les harnais qui montent l'écran sans ces providers. Deux fichiers émettent réellement `AuthNewAccountAuthenticated` et déclenchent donc la lecture : `otp_verification_screen_test.dart:123` et `otp_verification_screen_email_test.dart:168` et `:287`. `auth_method_screen_test.dart` ne l'émet pas aujourd'hui — le provisionner quand même, sinon le prochain test ajouté cassera sans raison apparente. Doublure disponible : `stubStripeAccountBloc()` de `test/helpers/stripe_account_test_doubles.dart`.

- [ ] **Étape 1 : réécrire le test qui échoue**

Conserver les trois `group` existants (réinstall / jamais répondu / pas de sync inutile) — ils protègent des régressions réelles — et adapter les appels à la nouvelle signature, puis ajouter :

```dart
  group('resolvePostSignupRoute — le parcours ne s\'impose plus une fois vu', () {
    test('onboardingSeenAt renseigné → /home, quelles que soient les étapes '
        'manquantes', () async {
      storedConsent = true;

      final route = await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(
          id: 'u1',
          onboardingSeenAt: DateTime.utc(2026, 8, 22),
        ),
        stripe: const StripeAccountInitial(),
      );

      expect(route, '/home');
    });

    test('un utilisateur qui a passé le pays n\'y est pas renvoyé en boucle',
        () async {
      // skip() et continueAsSenderOnly() laissent volontairement country null.
      storedConsent = true;

      final route = await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(
          id: 'u1',
          onboardingSeenAt: DateTime.utc(2026, 8, 22),
        ),
        stripe: const StripeAccountInitial(),
      );

      expect(route, isNot('/auth/country-selection'));
    });
  });

  group('resolvePostSignupRoute — route de chaque étape', () {
    test('compte tout neuf, consentement répondu → sélection du pays', () async {
      storedConsent = true;

      final route = await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(id: 'u1'),
        stripe: const StripeAccountInitial(),
      );

      expect(route, '/auth/country-selection');
    });

    test('identité non vérifiée → /kyc/verify', () async {
      storedConsent = true;

      final route = await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(id: 'u1', country: 'FR'),
        stripe: const StripeAccountInitial(),
      );

      expect(route, '/kyc/verify');
    });

    test('plus rien à compléter → le parrainage clôt le parcours', () async {
      storedConsent = true;

      final route = await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(
          id: 'u1',
          country: 'FR',
          kycStatus: 'VERIFIED',
          residenceStreet: '12 rue des Lilas',
          stripeAccountStatus: 'ONBOARDING_COMPLETE',
        ),
        stripe: const StripeAccountReady(
          ConnectAccountStatus(status: 'ONBOARDING_COMPLETE'),
        ),
      );

      expect(route, '/auth/referral-code');
    });
  });
```

- [ ] **Étape 2 : lancer, vérifier l'échec**

```bash
flutter test test/features/auth/presentation/post_signup_route_test.dart
```
Attendu : `No named parameter with the name 'analytics'` (l'ancienne signature est positionnelle).

- [ ] **Étape 3 : réécrire le résolveur**

```dart
import 'package:dony/core/services/analytics_service.dart';
import 'package:dony/features/auth/data/models/user_model.dart';
import 'package:dony/features/auth/presentation/onboarding_step.dart';
import 'package:dony/features/stripe_account/bloc/stripe_account_bloc.dart';

/// Route à suivre juste après la création du compte.
///
/// Fine enveloppe **asynchrone** autour de [nextStep] : la seule chose qu'elle
/// fait de plus est de réconcilier le consentement analytics avec le backend
/// avant de le lire. Sans ce sync, un utilisateur réinstallé (Hive vide, donc
/// `hasAnswered` faux à tort) qui a déjà consenti côté backend serait
/// redemandé — exactement la régression que la persistance backend élimine.
///
/// Toute la décision elle-même est dans la fonction pure, pour que la carte de
/// reprise (lot 4) ne puisse pas la contredire.
///
/// Anciennement `resolvePostPinSetupRoute`, puis une cascade de `if` pilotée
/// par le drapeau Hive `kCountryOnboardingSeen` : ce drapeau ne distinguait pas
/// l'étape pays de l'étape adresse et faisait sauter cette dernière.
Future<String> resolvePostSignupRoute({
  required AnalyticsService analytics,
  required UserModel? user,
  required StripeAccountState stripe,
  String? countryFallback,
}) async {
  if (analytics.isConfigured && !analytics.hasAnswered) {
    await analytics.syncFromBackend();
  }

  // Le parcours ne s'impose plus jamais une fois l'accueil atteint : on n'y
  // entre alors que par la carte de reprise. C'est aussi ce qui empêche de
  // renvoyer indéfiniment vers le pays un utilisateur qui l'a passé —
  // `CountryOnboardingCubit.skip()` laisse volontairement `country` à null.
  if (user?.onboardingSeenAt != null) return '/home';

  final step = nextStep(
    user: user,
    stripe: stripe,
    analyticsAnswered:
        !analytics.isConfigured || analytics.hasAnswered,
    countryFallback: countryFallback,
  );

  // Rien à compléter : le parrainage clôt le parcours, et c'est lui qui pose
  // `onboarding_seen_at` (`referral_code_screen.dart:44`).
  return step?.route ?? '/auth/referral-code';
}
```

- [ ] **Étape 4 : mettre à jour les deux appelants**

`otp_verification_screen.dart` — l'état écouté porte déjà le `UserModel` (`AuthNewAccountAuthenticated extends AuthAuthenticated`, `auth_state.dart:63`) :

```dart
  Future<void> _continueAfterSignup(
    BuildContext context,
    UserModel user,
  ) async {
    unawaited(
      getIt<AnalyticsService>().logEvent(AnalyticsEvents.signupCompleted),
    );
    // Lus avant l'await : après, le contexte peut être démonté.
    final stripe = context.read<StripeAccountBloc>().state;
    final country = context.read<BusinessPrefsBloc>().state.country;

    final route = await resolvePostSignupRoute(
      analytics: getIt<AnalyticsService>(),
      user: user,
      stripe: stripe,
      countryFallback: country,
    );
    if (context.mounted) {
      context.go(route);
    }
  }
```
et aux deux sites d'appel (lignes 172 et 182) : `unawaited(_continueAfterSignup(context, state.user));`

`auth_method_screen.dart` — même transformation, appel ligne 45 : `unawaited(_continueAfterNewAccount(context, state.user));`

- [ ] **Étape 5 : provisionner les trois harnais d'écran**

Dans `otp_verification_screen_test.dart`, `otp_verification_screen_email_test.dart` et `auth_method_screen_test.dart`, envelopper l'écran monté dans les deux providers manquants :

```dart
import '../../../../helpers/stripe_account_test_doubles.dart';

// … dans le harnais :
        MultiBlocProvider(
          providers: [
            BlocProvider<AuthBloc>.value(value: authBloc),
            BlocProvider<StripeAccountBloc>.value(
              value: stubStripeAccountBloc(),
            ),
            BlocProvider<BusinessPrefsBloc>.value(value: businessPrefsBloc),
          ],
          child: /* écran */,
        ),
```
Le stub `BusinessPrefsBloc` suit la même forme (`MockBloc` + `when(() => bloc.state).thenReturn(...)`) ; adapter le chemin d'import relatif au fichier.

- [ ] **Étape 6 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/
```
Attendu : `All tests passed!` — si un `ProviderNotFoundException` apparaît ailleurs, c'est le piège n°1 : provisionner le harnais concerné, ne jamais contourner en retirant la lecture.

- [ ] **Étape 7 : commit**

```bash
git add lib/features/auth test/features/auth
git commit -m "feat(auth): la redirection post-inscription suit l'état réel du compte"
```

---

### Tâche 6 : analytics

**Fichiers :**
- Modifier : `lib/core/services/analytics_events.dart` (à la suite du bloc « Onboarding progressif — lot 1 », lignes 329-331)
- Modifier : `lib/features/auth/presentation/post_signup_route.dart`
- Modifier : `lib/features/auth/bloc/country_onboarding_cubit.dart` (lignes 84, 110, 128)
- Modifier : `lib/features/auth/bloc/residence_address_cubit.dart` (après la ligne 78)
- Modifier : `lib/features/auth/presentation/screens/analytics_consent_screen.dart` (`_respond`, ligne 197)
- Modifier : `CLAUDE.md` (tableau des events)
- Tests : `test/features/auth/presentation/post_signup_route_test.dart`, `test/features/auth/bloc/country_onboarding_cubit_test.dart`, `test/features/auth/bloc/residence_address_cubit_test.dart`

**Ce qui existe déjà (ne pas redéclarer) :** `onboarding_step_skipped` et `residence_address_saved` (`analytics_events.dart:330-331`).

**Ce qui est reporté au lot 4 :** `onboarding_exited` (aucune croix dans les quatre écrans du parcours — vérifié) et `onboarding_resumed` (suppose la carte de reprise).

- [ ] **Étape 1 : écrire les tests qui échouent**

Dans `post_signup_route_test.dart` :

```dart
    test('l\'étape retenue est tracée, sans PII', () async {
      storedConsent = true;

      await resolvePostSignupRoute(
        analytics: service,
        user: UserModel(id: 'u1', country: 'FR'),
        stripe: const StripeAccountInitial(),
      );

      final captured = verify(
        () => backend.capture(
          eventName: captureAny(named: 'eventName'),
          properties: captureAny(named: 'properties'),
        ),
      ).captured;
      expect(captured, contains('onboarding_step_viewed'));
      expect(captured.toString(), contains('identity'));
      expect(captured.toString(), isNot(contains('u1')));
    });
```
*(adapter aux noms exacts exposés par `MockAnalyticsBackend` — `test/helpers/mock_analytics_backend.dart` ; s'il n'expose pas de `capture` capturable, monter l'assertion sur un `AnalyticsService` mocké à la place, comme le fait `residence_address_cubit_test.dart`.)*

Dans `residence_address_cubit_test.dart`, ajouter au test de succès :

```dart
    expect(
      captured.toString(),
      contains(AnalyticsEvents.onboardingStepCompleted),
    );
```

- [ ] **Étape 2 : déclarer les events**

```dart
  // Onboarding progressif — lot 2
  static const onboardingStepViewed = 'onboarding_step_viewed';
  static const onboardingStepCompleted = 'onboarding_step_completed';
  static const onboardingCompleted = 'onboarding_completed';
```

- [ ] **Étape 3 : émettre**

Dans `resolvePostSignupRoute`, juste après le calcul de `step` :

```dart
  final steps = onboardingSteps(stripe);
  if (step == null) {
    unawaited(
      analytics.logEvent(
        AnalyticsEvents.onboardingCompleted,
        properties: {'steps_total': steps.length},
      ),
    );
  } else {
    unawaited(
      analytics.logEvent(
        AnalyticsEvents.onboardingStepViewed,
        properties: {
          'step': step.wireName,
          'index': steps.indexOf(step) + 1,
          'total': steps.length,
        },
      ),
    );
  }
```

> C'est le seul endroit qui connaît l'étape retenue, et il reçoit déjà `AnalyticsService` en argument — pas un widget. La règle « les events métier se tirent dans le BLoC » vise les widgets ; le précédent le plus proche est `AnalyticsConsentScreen._respond` (`analytics_consent_screen.dart:199`), qui émet déjà depuis une fonction de présentation.

Dans `CountryOnboardingCubit.select()` (après la ligne 84) :
```dart
      unawaited(
        _analytics.logEvent(
          AnalyticsEvents.onboardingStepCompleted,
          properties: {'step': 'country'},
        ),
      );
```
Dans `skip()` et `continueAsSenderOnly()`, compléter l'event `countryOnboardingSkipped` existant par :
```dart
      unawaited(
        _analytics.logEvent(
          AnalyticsEvents.onboardingStepSkipped,
          properties: {'step': 'country'},
        ),
      );
```

Dans `ResidenceAddressCubit.submit()`, à côté de `residenceAddressSaved` :
```dart
      unawaited(
        _analytics.logEvent(
          AnalyticsEvents.onboardingStepCompleted,
          properties: {'step': 'address'},
        ),
      );
```

Dans `AnalyticsConsentScreen._respond`, à côté de `analyticsConsentAnswered` :
```dart
    unawaited(
      getIt<AnalyticsService>().logEvent(
        AnalyticsEvents.onboardingStepCompleted,
        properties: {'step': 'consent'},
      ),
    );
```

- [ ] **Étape 4 : relancer, vérifier que ça passe**

```bash
flutter test test/features/auth/ test/core/services/
```
Attendu : `All tests passed!`

- [ ] **Étape 5 : mettre à jour le tableau des events du `CLAUDE.md`**

Trois lignes à ajouter, après `residence_address_saved` :

| Event | Déclencheur |
|-------|-------------|
| `onboarding_step_viewed` | `resolvePostSignupRoute` — étape retenue à l'entrée du parcours (propriétés `step` énumération fermée, `index`, `total`) |
| `onboarding_step_completed` | `CountryOnboardingCubit.select` · `ResidenceAddressCubit.submit` · `AnalyticsConsentScreen._respond` (propriété `step`) |
| `onboarding_completed` | `resolvePostSignupRoute` — `nextStep` rend `null`, le compte est complet (propriété `steps_total`) |

- [ ] **Étape 6 : commit**

```bash
git add lib/core/services/analytics_events.dart lib/features/auth CLAUDE.md test/features/auth
git commit -m "feat(analytics): trace les étapes de l'onboarding progressif sans PII"
```

---

### Tâche 7 : retirer le drapeau Hive `kCountryOnboardingSeen`, devenu mort

**Fichiers :**
- Modifier : `lib/core/storage/hive_service.dart` (ligne 53 — supprimer la constante)
- Modifier : `lib/features/auth/bloc/country_onboarding_cubit.dart` (lignes 83, 109, 127 — supprimer les trois écritures)
- Modifier : `test/core/storage/hive_service_test.dart:6`
- Modifier : `test/features/auth/bloc/country_onboarding_cubit_test.dart` (lignes 76, 178, 207, 289, 312, 331, 341)
- Modifier : `test/features/auth/bloc/auth_bloc_test.dart` (lignes 119, 306, 324, 1201, 1220)

**Pourquoi.** Après la tâche 5, `kCountryOnboardingSeen` n'a **plus aucun lecteur** : son unique lecture était `post_signup_route.dart:44`. Il resterait écrit trois fois et lu zéro fois. Le `CLAUDE.md` du dépôt l'interdit explicitement (règle 18 : « un widget/écran/service sans aucun appelant doit être supprimé, pas laissé au cas où »). C'est `onboarding_seen_at`, côté serveur, qui le remplace (spec §3.3).

**Ce qu'il ne faut pas casser.** `AuthBloc._clearHiveAccountData()` (`auth_bloc.dart:88-91`) vide la **boîte entière** `user_prefs` à chaque inscription — il ne cite aucune clé nommément, donc rien à y changer. Les deux tests d'`auth_bloc_test.dart` qui citent la constante ne vérifient que cette purge : y remplacer `HiveService.kCountryOnboardingSeen` par une autre clé déjà vérifiée au même endroit (`HiveService.kCountryCode`) plutôt que d'ajouter une chaîne littérale.

- [ ] **Étape 1 : prouver qu'il ne reste aucun lecteur**

```bash
grep -rn "kCountryOnboardingSeen\|country_onboarding_seen" lib/
```
Attendu : uniquement la déclaration (`hive_service.dart:53`) et les trois écritures du cubit. **Si un lecteur apparaît, s'arrêter et ne pas supprimer.**

- [ ] **Étape 2 : supprimer les écritures et la constante**

Retirer les trois `await _prefs.put(HiveService.kCountryOnboardingSeen, true);` de `country_onboarding_cubit.dart` et la ligne 53 de `hive_service.dart`. Mettre à jour la doc de classe du cubit, qui mentionne « mémorise le passage ».

- [ ] **Étape 3 : mettre à jour les tests**

Retirer les `verify`/`verifyNever` correspondants de `country_onboarding_cubit_test.dart` (ne pas supprimer les tests eux-mêmes, seulement l'assertion sur cette clé), la ligne 6 de `hive_service_test.dart`, et les cinq références d'`auth_bloc_test.dart`.

- [ ] **Étape 4 : lancer**

```bash
flutter test test/core/storage/ test/features/auth/
```
Attendu : `All tests passed!`

- [ ] **Étape 5 : commit**

```bash
git add lib/core/storage/hive_service.dart lib/features/auth/bloc/country_onboarding_cubit.dart \
        test/core/storage test/features/auth
git commit -m "refactor(auth): retire le drapeau Hive d'onboarding, remplacé par onboarding_seen_at"
```

---

### Tâche 8 : vérification complète, device, PR

- [ ] **Étape 1 : analyse et format**

```bash
dart format lib/ test/
flutter analyze > /tmp/analyze.log 2>&1; echo "ANALYZE=$?"; tail -3 /tmp/analyze.log
```
Attendu : `ANALYZE=0`.

- [ ] **Étape 2 : suite complète — obligatoire, jamais de conclusion sur les tests ciblés**

```bash
flutter test > /tmp/front-full.log 2>&1; echo "TEST=$?"; tail -8 /tmp/front-full.log
```
Attendu : `TEST=0`. Lire le **code de sortie**, jamais juger sur un `| tail` seul — un tube masque le code de sortie.

Ce lot fait lire deux blocs fournis à l'échelle de l'application (`StripeAccountBloc`, `BusinessPrefsBloc`) par des écrans existants : c'est exactement la situation qui a produit quatre `ProviderNotFoundException` au lot précédent, dont une par une atteinte indirecte qu'aucun `grep` ne trouvait. **Seule cette commande le révèle.**

- [ ] **Étape 3 : couverture**

```bash
flutter test --coverage > /tmp/front-cov.log 2>&1; echo "COV=$?"
genhtml coverage/lcov.info -o coverage/html
```
Attendu : `COV=0` et couverture globale ≥ 90 %. Les fonctions pures de la tâche 1-2 doivent être à 100 % — elles n'ont aucune excuse.

- [ ] **Étape 4 : suites d'accessibilité**

```bash
flutter test test/a11y/
```
Attendu : vert. Le nouveau cas 200 % de `dony_onboarding_gauge_test.dart` et le cas résidence de `large_text_smoke_test.dart` couvrent le débordement ; `tap_targets_test.dart` n'est pas concerné (la jauge n'est pas interactive).

- [ ] **Étape 5 : vérification sur le téléphone connecté**

```bash
flutter run --dart-define-from-file=env.dev.json -d <device-id>
```

Parcourir une inscription complète et vérifier de visu :
- la jauge apparaît sur les quatre écrans du parcours et **gagne un segment** après le pays et après l'adresse ;
- sur l'écran de parrainage, aucun segment n'est à moitié et le compteur ne bouge pas ;
- avec un pays hors couverture Stripe (Sénégal), la jauge n'a que **quatre** segments ;
- les écrans `/auth/phone`, `/auth/email` et `/auth/otp` affichent toujours l'ancienne pastille `n / 3` ;
- rien ne déborde en thème clair **et** sombre, à taille de texte normale **et** à 200 % (Réglages Android › Affichage › Taille du texte) ;
- tous les boutons répondent : « Accepter » / « Non merci », le choix d'un pays, « Passer pour l'instant », « Continuer », « Appliquer ».

- [ ] **Étape 6 : PR**

```bash
git push -u origin feature/onboarding-resolver
gh pr create --title "feat(auth): résolveur d'onboarding et jauge (lot 2)" \
  --body "Lot 2 du parcours d'onboarding progressif. Aucune contrepartie backend : tous les faits sont déjà exposés par GET /auth/me (voir la Partie A du plan). Spec : docs/superpowers/specs/2026-08-22-onboarding-progressif-design.md"
```

> `gh pr merge` est bloqué par le classifieur : demander à l'utilisateur de lancer la fusion lui-même.

---

## Ordre de fusion

**Une seule PR, sur `dony_app`.** Rien à coordonner : le backend est inchangé et sert déjà tous les champs lus. Une PR back n'est ni nécessaire ni souhaitable.

Ordre d'exécution des tâches, qui n'est pas leur ordre de lecture :
**3 → 2 → 1 → 4 → 5 → 6 → 7 → 8.**
La tâche 3 définit `DonyGaugeSegment`, consommé par la tâche 2 ; la tâche 1 peut être faite avant ou après les deux, mais la factorisation `_isDone` de la tâche 2 suppose que `nextStep` existe — faire 1 puis 2 si l'on préfère un ordre linéaire, en acceptant un échec de compilation transitoire sur `segments`.

## Hors périmètre du lot 2

- **Écran récapitulatif `/account/setup`, carte de reprise dans `EvergreenGuidanceCarousel`, renvoi vers l'étape 3 depuis le refus de publication (403 `kyc-not-verified`)** — lot 4. `nextStep` et `OnboardingProgress` sont conçus pour eux mais ne sont pas appelés par eux ici.
- **Préremplissage de Stripe Connect depuis Identity `verified_outputs`** (`StripeV2AccountProvisioner`) — lot 3, entièrement backend.
- **Events `onboarding_exited` et `onboarding_resumed`** — aucun déclencheur n'existe avant le lot 4.
- **Exposer `connectAvailableInCountry` sur `UserResponse`** — envisagé, écarté (Partie A). À reconsidérer seulement si le lot 4 montre que le repli optimiste induit les utilisateurs en erreur.
- **Rafraîchir le profil après l'étape pays** — le repli `BusinessPrefsBloc.state.country` suffit et suit le précédent du routeur. Un vrai rafraîchissement (`GET /auth/me` après `select()`) serait plus propre mais dépasse le lot.

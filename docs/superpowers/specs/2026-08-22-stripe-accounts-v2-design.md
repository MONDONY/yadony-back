# Migration Stripe Connect Accounts v1 → v2

**Date :** 2026-08-22
**Statut :** conception validée, implémentation à faire
**Remplace :** `docs-claude/docs/specs/2026-08-19-migration-accounts-v2.md` (préparation, plusieurs points ouverts désormais tranchés)

---

## 1. Pourquoi maintenant

`POST /v1/accounts` est bloqué par Stripe (`v1_accounts_create_blocked`). Le blocage a été
constaté en production : plus aucun voyageur ne peut créer son compte de paiement, donc plus
aucun ne peut accepter la carte. Le contournement self-service (`feat_accounts_v1_support`)
n'est **pas disponible sur le compte de production** — la page « Fonctionnalités du compte »
affiche « Sans objet » sur la colonne production, sans bouton d'activation, et le support
Stripe n'a pas débloqué la situation.

La migration vers `POST /v2/core/accounts` n'est donc plus un choix d'architecture : c'est le
seul chemin de création de compte qui fonctionne.

---

## 2. Ce qui a été vérifié (et non supposé)

Tout ce tableau vient d'appels réels à l'API Stripe en sandbox le 2026-08-22, ou de
l'inspection du jar `stripe-java` 33.3.0. Les comptes de test créés ont tous été refermés.

| Question | Réponse vérifiée |
|---|---|
| `POST /v2/core/accounts` en `recipient` seul fonctionne-t-il ? | **Oui** pour FR, BE, DE, ES, IT, PT, NL, CH, GB, AT, IE, FI, GR. |
| Faut-il la configuration `merchant` ? | **Non.** Le modèle actuel (*separate charges and transfers*) laisse les fonds sur le solde plateforme, `on_behalf_of` a été retiré, et le versement passe par `Transfer.create`. `card_payments` ne servait qu'à la compatibilité de vieux comptes. |
| Que se passe-t-il si on ajoute `merchant` ? | Erreur `account_token_required` : une plateforme basée en FR ne peut pas écrire l'identité sur une configuration `merchant` sans passer par les *account tokens*. Raison de plus pour rester en `recipient` seul. |
| `payouts` est-il une capacité demandable ? | **Non.** L'API répond `configuration.recipient.capabilities.stripe_balance.payouts: Unknown field`, et le SDK ne l'expose pas non plus (`StripeBalance$Builder` n'a que `setStripeTransfers`). `payouts` est un **statut en lecture seule** dans la réponse. |
| Le planning de virement quotidien de la v1 est-il perdu ? | **Non.** Le défaut d'un compte v2 est déjà `interval: daily` (`delay_days: 7`). Aucun code n'est nécessaire. |
| `AccountLink.create` (v1) fonctionne-t-il sur un identifiant de compte v2 ? | **Oui**, testé, renvoie une URL `connect.stripe.com/setup/e/acct_.../...`. `createOnboardingLink` reste inchangé. |
| `Account.retrieve` (v1) fonctionne-t-il sur un compte v2 ? | **Oui**, et la réponse a exactement la même forme qu'avant (`capabilities`, `requirements`, `controller`, `charges_enabled`…). Les webhooks et le rafraîchissement restent inchangés. |
| Le SDK 33.3.0 casse-t-il les 34 usages v1 actuels ? | **Non.** Tous les points d'entrée statiques utilisés (`Account`, `PaymentIntent`, `Transfer`, `AccountLink`, `Customer`, `EphemeralKey`, `Refund`, `SetupIntent`, `VerificationSession`) existent toujours. |
| La v2 réduit-elle la couverture pays ? | **Non.** Les pays qui échouent en v2 (`SN`, `CI`, `ML`, `CM`, `US`, `CA`, `MA`, `TN`) échouaient **déjà** en v1, avec des messages équivalents (« not currently supported by Stripe », ou « cannot request `transfers` without `card_payments` »). |

**Version du SDK :** la dernière version stable est **33.3.0** (33.4.0 n'existe qu'en alpha).
La note du 2026-08-19 visait 33.2.0 ; 33.3.0 la remplace.

---

## 3. Décisions

1. **Bascule complète, sans flag ni cohabitation.** Aucun compte Connect réel n'existe en
   production — le blocage a empêché toute création. Il n'y a donc rien à faire cohabiter.
   `StripeExpressAccountProvisioner` est supprimé, pas conservé « au cas où ».
2. **`recipient` seul**, capacité `stripe_balance.stripe_transfers` uniquement. Objectif
   produit : réduire l'onboarding du voyageur au strict minimum (identité + IBAN), sans
   déclencher la vérification marchande lourde qui ne sert à rien dans notre modèle.
3. **`String provision(UserEntity)`** au lieu de `Account provision(UserEntity)`.
   `PaymentService` ne consomme que `account.getId()`. Conserver le type `Account` (v1)
   obligerait soit à un `Account.retrieve()` superflu après création, soit à fabriquer un
   faux objet v1 depuis un `v2.core.Account`, qui est un type distinct.
4. **Pays non couverts par Stripe : Connect est désactivé, pas contourné.** Ces voyageurs
   restent en paiement espèces. Le backend connaît la liste, refuse proprement, et l'expose
   au front pour que le bouton d'activation ne soit pas proposé du tout.

---

## 4. Conception backend

### 4.1 Client Stripe v2 (`config/StripeConfig.java`)

L'API v2 n'est pas accessible depuis les points d'entrée statiques : elle passe par une
instance `StripeClient`. `Stripe.apiKey` reste posé pour tout le code v1 existant, et un bean
`StripeClient` est ajouté à côté.

```java
@Bean
public StripeClient stripeClient() {
    return new StripeClient(secretKey);
}
```

### 4.2 Éligibilité pays (`payments/StripeConnectCountries.java`, nouveau)

`CountryCatalog` décrit les 38 pays desservis par yadony. Stripe n'en accepte que 22 pour un
compte `recipient` : les 20 de la zone euro, plus `CH` et `GB`. Les 16 autres (`US`, `CA`, les
8 pays XOF, les 6 pays XAF) ne peuvent pas avoir de compte Connect.

```java
public final class StripeConnectCountries {
    private static final Set<String> SUPPORTED = Set.of(
            "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES",
            "CH", "GB");

    public static boolean isSupported(String iso2) {
        return iso2 != null && SUPPORTED.contains(iso2.toUpperCase(Locale.ROOT));
    }
}
```

Cette liste double une contrainte qui vit chez Stripe. Elle doit rester **strictement
informative** : le provisionneur la consulte avant l'appel pour rendre une erreur lisible,
mais un refus de Stripe reste possible et doit être traité proprement (§4.5).

### 4.3 Interface (`payments/ConnectAccountProvisioner.java`)

```java
public interface ConnectAccountProvisioner {
    String provision(UserEntity user) throws StripeException;
}
```

`RateLimitException` hérite de `StripeException` : la signature actuelle suffit.

### 4.4 Implémentation (`payments/StripeV2AccountProvisioner.java`, nouveau)

Remplace `StripeExpressAccountProvisioner`, qui est supprimé avec son test.

```java
@Override
public String provision(UserEntity user) throws StripeException {
    String country = user.getCountry();

    // Le pays d'un compte Connect est immuable chez Stripe : mieux vaut refuser
    // que fabriquer un compte sur un pays par défaut.
    if (country == null || country.isBlank()) {
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "country-required", "Country Required",
                "Renseignez votre pays dans les réglages avant de créer votre "
                        + "compte de paiement.");
    }
    if (!StripeConnectCountries.isSupported(country)) {
        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "country-not-supported-by-stripe", "Country Not Supported",
                "Le paiement par carte n'est pas encore disponible dans votre pays. "
                        + "Vous pouvez continuer à recevoir vos paiements en espèces.");
    }

    var params = AccountCreateParams.builder()
            .setContactEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
            .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
            .setIdentity(
                    AccountCreateParams.Identity.builder()
                            .setCountry(country)
                            .setEntityType(user.isProAccount()
                                    ? AccountCreateParams.Identity.EntityType.COMPANY
                                    : AccountCreateParams.Identity.EntityType.INDIVIDUAL)
                            .build())
            .setDefaults(
                    AccountCreateParams.Defaults.builder()
                            // Stripe impose APPLICATION dès qu'un compte porte
                            // stripe_transfers : STRIPE est rejeté à la création.
                            .setResponsibilities(
                                    AccountCreateParams.Defaults.Responsibilities.builder()
                                            .setLossesCollector(LossesCollector.APPLICATION)
                                            .setFeesCollector(FeesCollector.APPLICATION)
                                            .build())
                            .setProfile(
                                    AccountCreateParams.Defaults.Profile.builder()
                                            .setBusinessUrl(stripeConnectProperties.businessUrl())
                                            .setProductDescription(
                                                    stripeConnectProperties.productDescription())
                                            .build())
                            .build())
            .setConfiguration(
                    AccountCreateParams.Configuration.builder()
                            .setRecipient(recipientConfiguration())
                            .build())
            .putMetadata("user_id", user.getId().toString())
            .addInclude(AccountCreateParams.Include.CONFIGURATION__RECIPIENT)
            .build();

    return stripeGateway.createAccountV2(params).getId();
}
```

Notes de correspondance avec la v1 :

- `type: express` → `dashboard: EXPRESS` (même expérience d'onboarding hébergée).
- `business_type` → `identity.entity_type`.
- `country` (racine) → `identity.country`.
- `business_profile.url` / `product_description` → `defaults.profile.*`.
- **`business_profile.mcc` disparaît** : aucun équivalent dans les paramètres de création v2.
  Le MCC 4215 n'était pas exigé pour un compte `recipient`.
- `settings.payouts.schedule.interval=daily` disparaît : c'est déjà le défaut (§2).

### 4.5 Passerelle (`payments/StripeGateway.java` / `StripeGatewayImpl.java`)

Une méthode s'ajoute, aucune ne change :

```java
com.stripe.model.v2.core.Account createAccountV2(
        com.stripe.param.v2.core.AccountCreateParams params) throws StripeException;
```

```java
@Override
public com.stripe.model.v2.core.Account createAccountV2(
        com.stripe.param.v2.core.AccountCreateParams params) throws StripeException {
    return stripeClient.v2().core().accounts().create(params);
}
```

`createAccount(AccountCreateParams)` (v1) est **retiré** de l'interface : ses seuls appelants
sont `StripeExpressAccountProvisioner` et son test, tous deux supprimés.

`retrieveAccount` (v1) est **conservé** : c'est lui qui alimente le rafraîchissement du statut
et les webhooks, et il répond identiquement sur un compte créé en v2.

Le bouchon du profil e2e (`e2e/config/E2EMockConfig`, un mock Mockito de `StripeGateway`) suit
le même changement : `createAccount` y devient `createAccountV2` et rend un `v2.core.Account`.

### 4.6 Éligibilité exposée au front (`payments/dto/ConnectAccountResponse.java`)

Un champ s'ajoute, calculé depuis `StripeConnectCountries.isSupported(user.getCountry())` :

```java
public record ConnectAccountResponse(
        String stripeAccountId,
        StripeAccountStatus stripeAccountStatus,
        boolean connectAvailableInCountry) { }
```

Le DTO est construit à **quatre** endroits de `PaymentService` (lignes 128, 145, 181 et 295 à
ce jour) : statut lu, retour anticipé quand un compte existe déjà, création, et
rafraîchissement. Les quatre doivent renseigner le nouveau champ — le compilateur les signale
tous puisqu'il s'agit d'un record.

### 4.7 Ce qui ne change pas

Volontairement hors périmètre, parce que vérifié comme inchangé :

- `createOnboardingLink` — `AccountLink.create` (v1) accepte un identifiant v2.
- `refreshConnectAccount`, `handleAccountUpdated`, `handleCapabilityUpdated` — `Account.retrieve`
  (v1) rend la même structure sur un compte v2.
- `ensureCardPaymentsCapability` — ne concerne que d'anciens comptes ; **inutile sur les
  nouveaux comptes v2**, mais conservé tant que des comptes v1 existent en sandbox. À supprimer
  dans un chantier distinct, pas ici.
- Tout le flux de paiement : PaymentIntent, Transfer, escrow, commission.

---

## 5. Conception frontend (`dony_app`)

Le contrat de réponse ne change pas de forme : le front continue de lire `stripeAccountId` et
`stripeAccountStatus`. **Aucune adaptation n'est nécessaire pour la bascule v1 → v2 elle-même.**

Le seul changement front vient de la décision 4 (§3) — masquer l'activation carte là où elle ne
peut pas aboutir :

- `lib/core/models/connect_account_status.dart` — ajouter `connectAvailableInCountry`
  (défaut `true` si le champ est absent, pour tolérer un backend plus ancien).
- `lib/features/profile/presentation/widgets/activate_card_payments_cta_card.dart` — ne pas
  afficher le CTA quand `connectAvailableInCountry` est faux.
- `lib/features/matching/presentation/widgets/create_announcement/prix_conditions_step.dart` —
  le forçage en espèces existe déjà quand le compte n'est pas complet ; y ajouter un libellé
  explicite quand la cause est le pays, plutôt que de laisser croire à un onboarding inachevé.
- `lib/features/connect_onboarding/presentation/screens/connect_onboarding_intro_screen.dart` —
  même garde à l'entrée du parcours.

**Hors périmètre, signalé :** deux parcours d'onboarding coexistent et appellent les mêmes
endpoints avec deux modèles différents (`payments/` en WebView contre `connect_onboarding/` via
le navigateur externe, `ConnectAccountModel` contre `ConnectAccountStatus`). Cette redondance
préexiste à la migration. La consolider ici mélangerait deux sujets ; elle mérite son propre
chantier.

---

## 6. Tests

**Backend**

- `StripeV2AccountProvisionerTest` (nouveau, sur le gabarit de l'ancien test v1) : compte
  individuel, compte professionnel (`entity_type: company`), pays absent → 422
  `country-required`, pays non couvert → 422 `country-not-supported-by-stripe`, capacité
  demandée limitée à `stripe_transfers`, absence de configuration `merchant`, métadonnée
  `user_id`.
- `StripeConnectCountriesTest` (nouveau) : appartenance, insensibilité à la casse, `null`.
- `StripeExpressAccountProvisionerTest` — supprimé avec la classe qu'il couvre.
- Adaptations : `StripeConnectAccountCreationTest`, `PaymentServiceRefreshConnectAccountTest`,
  `StripeConnectWebhookAccountUpdatedTest`, et le double de `StripeGateway` du profil e2e.
- Compilation complète après montée du SDK : c'est le vrai filet sur les 34 usages v1.

**Frontend**

- `test/core/models/connect_account_status_test.dart` — parsing du nouveau champ, défaut à
  `true` quand il est absent.
- Tests widget des trois écrans touchés : CTA masqué, libellé « pays non couvert ».

**Vérification manuelle en sandbox, avant de considérer la tâche terminée**

Un parcours complet, qu'aucun test automatisé ne remplace : création du compte → lien
d'onboarding → complétion réelle chez Stripe → réception du webhook `account.updated` →
statut `ONBOARDING_COMPLETE` → `Transfer` de bout en bout. C'est le seul moyen de confirmer
que l'onboarding est bien allégé (identité + IBAN, sans vérification marchande).

---

## 7. Risques

| Risque | Traitement |
|---|---|
| L'API v2 est encore servie sous un en-tête `preview` (`2026-07-29.preview`). | Le SDK 33.3.0 porte lui-même la version d'API : on ne pose pas d'en-tête à la main. À confirmer en production lors de la vérification manuelle. |
| Montée de 7 versions majeures du SDK (26.12.0 → 33.3.0). | Tous les points d'entrée utilisés ont été vérifiés présents. Le risque résiduel porte sur des signatures modifiées, que la compilation révèle immédiatement. |
| La liste des 22 pays diverge de la réalité Stripe. | Elle ne sert qu'à produire un message lisible ; un refus de Stripe reste géré. À revoir si Stripe étend sa couverture. |
| 9 des 22 pays n'ont pas été testés individuellement (`HR`, `CY`, `EE`, `LV`, `LT`, `LU`, `MT`, `SK`, `SI`). | Tous en zone euro/SEPA, même régime que les 13 testés. Vérifiable à peu de frais si un doute apparaît. |
| Des comptes v1 subsistent en sandbox et portent `merchant` + `recipient`. | Sans effet : la lecture v1 fonctionne indifféremment sur les deux. Aucune reprise de données n'est nécessaire. |

---

## 8. Séquence de livraison

Deux dépôts distincts, donc deux branches et deux pull requests. Le front dépend du champ
exposé par le back.

1. **Backend** — montée du SDK, bean `StripeClient`, `StripeConnectCountries`, interface en
   `String`, `StripeV2AccountProvisioner`, suppression de la v1, champ
   `connectAvailableInCountry`, tests.
2. **Vérification manuelle en sandbox** (§6) avant toute fusion.
3. **Frontend** — lecture du nouveau champ et gardes d'affichage.

Le déploiement en production n'a d'intérêt qu'une fois les deux fusionnés : sans le backend, le
front n'a pas le champ ; sans le front, la création reste bloquée pour tout le monde.

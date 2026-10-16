# Story Assistant IA — Assistant de l'app : fiches, fournisseurs gratuits, API admin (Backend)

**Date:** 2026-10-16
**Status:** ✅ Complète

## Résumé
L'app peut poser une question sur le fonctionnement de Yadony : le back cherche les fiches d'aide les plus proches (index Lucene en mémoire), demande une réponse courte à un fournisseur d'IA gratuit compatible OpenAI, et renvoie le texte avec au plus deux boutons vers les écrans de l'app. Les fournisseurs se gèrent dans l'admin (ajout, ordre de priorité, limite par jour, test, suppression douce) et se relaient automatiquement quand l'un est en panne ou à court de quota. Coût nul : aucune API payante. L'assistant est éteint par défaut (`ASSISTANT_ENABLED=false`).

Spec : docs-claude `docs/superpowers/specs/2026-10-16-assistant-ia-support-design.md`. Plan : `docs/superpowers/plans/2026-10-16-assistant-ia-back.md`.

## Fichiers créés
Paquet `com.yadony.api.assistant` :
- `AssistantController` — `POST /assistant/ask`, 404 `assistant-disabled` si éteint, 403 sans uid.
- `AssistantService` — enchaîne quota, recherche, prompt, routeur, filtrage des actions, mode dégradé.
- `AssistantQuotaGuard` — quota par utilisateur et par jour UTC (Caffeine), 429 `assistant-quota-exceeded` avec `retryAfter`.
- `AssistantPromptBuilder`, `AssistantAnswerParser` — consignes envoyées au modèle (texte utilisateur neutralisé) et lecture stricte de sa réponse JSON.
- `AssistantProperties` — `yadony.assistant.*` (enabled, daily-quota, min-score…).
- `dto/` — `AssistantAskRequest`, `AssistantAskResponse`, `AssistantAction`, `AssistantHistoryTurn`, `ParsedAnswer`.

Paquet `assistant.kb` :
- `KnowledgeBaseLoader` — lit `classpath:assistant/kb/{fr,en}/*.md` au démarrage, en-tête YAML strict, refuse tout écart (démarrage en échec).
- `KnowledgeBase`, `AssistantAnalyzers`, `KbCorpus`, `KbArticle`, `KbRoute`, `KbHit` — index Lucene en mémoire FR/EN (accents pliés), recherche avec relance courte, seuil `min-score`, liste blanche des routes.

Paquet `assistant.llm` :
- `OpenAiCompatibleClient` — seul chemin sortant vers un fournisseur (frontière anti-SSRF, voir Pièges).
- `ProviderAddressGuard` — refuse toute adresse non publique, branché comme résolveur DNS de la connexion.
- `LlmRouter` — essaie les fournisseurs par priorité, 3 essais au plus, budget de 25 s par question.
- `ProviderHealthRegistry` — compteurs du jour, limite atomique (`tryAcquire`), mises de côté, `reset`.
- `LlmCallException`, `LlmProviderConfig` (clé masquée dans `toString`), `LlmResult`.

Paquet `assistant.provider` :
- `AssistantLlmProviderEntity`, `AssistantLlmProviderRepository` — table `assistant_llm_providers`, clé chiffrée, suppression douce.
- `AssistantProviderAdminService`, `AdminAssistantProviderController` — API `/admin/assistant/providers`.
- `ProviderCatalog` — fournisseurs actifs gardés 60 s, vidé après chaque écriture admin.
- `BaseUrlValidator`, `SlugMaker`, `dto/*`.

Ressources :
- `src/main/resources/assistant/kb/fr/*.md` et `en/*.md` — 84 fiches par langue.
- `db/migration/V324__assistant_llm_providers.sql`.

## Fichiers modifiés
- `pom.xml` — Lucene (core, analysis-common, queryparser), `httpclient5` déclaré explicitement.
- `application.yml` — bloc `yadony.assistant` (valeurs par défaut, assistant éteint).
- `admin/account/AdminPermission.java` — permission `ASSISTANT_MANAGE` (ADMIN, SUPER_ADMIN).
- `NotificationPrefsClassificationTest` — `json_object` exclu (format de réponse d'un fournisseur, pas une notification).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'app envoie `POST /api/v1/assistant/ask` `{question, locale, history}` avec le jeton Firebase.
2. Si l'assistant est éteint : 404 `assistant-disabled`. Sans uid : 403.
3. Le quota du jour est décompté (20 par défaut) ; au-delà, 429 avec `retryAfter` = minuit UTC suivant.
4. La langue est ramenée à `fr` ou `en`, l'historique aux 12 derniers tours valides (1000 caractères chacun).
5. Recherche des 4 meilleures fiches au-dessus de `min-score`. Aucune fiche : réponse fixe « je n'ai pas trouvé », `provider: "none"`, aucun appel fournisseur.
6. Le routeur essaie les fournisseurs actifs par priorité, en sautant ceux mis de côté ou à leur limite du jour, 3 essais au plus, dans un budget de 25 s.
7. La réponse JSON du modèle est lue strictement ; texte coupé à 600 caractères ; actions gardées seulement si la route est dans la liste blanche ET dans les fiches envoyées, 2 au plus, libellé repris de la fiche.
8. Si aucun fournisseur ne répond : mode dégradé (texte fixe + liens des meilleures fiches), toujours 200.

### Points d'entrée API
- `POST /api/v1/assistant/ask` — tout utilisateur connecté non invité.
- `GET /api/v1/admin/assistant/providers` — liste avec stats du jour (ADMIN + `ASSISTANT_MANAGE`).
- `POST /api/v1/admin/assistant/providers` — ajout (201).
- `PATCH /api/v1/admin/assistant/providers/{id}` — modification ; clé vide = inchangée ; changer l'adresse exige de ressaisir la clé (422 `apiKey`).
- `PUT /api/v1/admin/assistant/providers/order` — ordre de priorité (la liste doit contenir exactement les fournisseurs existants).
- `POST /api/v1/admin/assistant/providers/{id}/test` — appel réel de test (latence, extrait de 200 caractères).
- `DELETE /api/v1/admin/assistant/providers/{id}` — suppression douce (204, puis 404).

### Entités JPA impliquées
- `AssistantLlmProviderEntity` → `assistant_llm_providers` : `name`, `slug` (unique parmi les non supprimés, index partiel), `base_url` (https, port 443, hôte public), `model`, `api_key` chiffrée (`EncryptedStringConverter`), `supports_json_mode`, `daily_request_limit` (null = sans limite), `priority`, `enabled`, soft delete.

### Logique métier critique
- La clé d'API n'est jamais renvoyée (4 derniers caractères seulement), jamais dans l'audit, les journaux ni les messages d'erreur.
- Changer l'adresse sans ressaisir la clé est refusé : sinon un compte admin compromis enverrait la clé existante vers son propre serveur. Le contrôle passe avant toute modification de l'entité.
- Mises de côté : 60 s après un 429, 10 min après 401/403 ou une adresse refusée. Corriger la clé, l'adresse ou le modèle lève la mise de côté (après le commit).
- Aucune donnée personnelle n'est envoyée au fournisseur : seulement la question, l'historique de la conversation et les fiches.
- Le texte de l'utilisateur est neutralisé (`<` `>` retirés, rôles limités à user/assistant) : il ne peut pas se faire passer pour une consigne.

### Events Spring publiés / écoutés
Aucun. Le paquet `assistant` n'injecte aucun service d'un autre paquet métier (il utilise `common` : audit, exceptions, chiffrement).

### Pièges et points d'attention
- **Frontière anti-SSRF** : tout appel sortant passe par `OpenAiCompatibleClient`. Il ne faut ni ajouter un second client, ni réactiver redirections, relances automatiques, compression ou proxy système, ni sortir du résolveur DNS gardé (il ferme le rebinding DNS). Corps lu borné à 64 Kio, délai total par appel (20 s au plus), connexion jetée sans vider le corps.
- **Budget de 25 s par question** : nginx coupe `/api/v1/` à 30 s. Ne pas allonger les délais sans relire `nginx/nginx.conf`.
- **Ne jamais passer `org.apache.hc` en DEBUG** : ses journaux afficheraient l'en-tête `Authorization` (clé du fournisseur).
- **Quota et santé en mémoire, par instance** : remis à zéro à chaque redémarrage ; en multi-instances la limite réelle est multipliée.
- **Fiches** : une fiche invalide empêche le back de démarrer (voulu). Chaque fiche existe en FR et EN avec les mêmes routes (test de parité). Aucun chiffre réglable dans l'admin (commission, plafond, durées de conservation) ne doit y être écrit. Pas de tiret cadratin.
- **Chargement depuis le jar** : les tests lisent les fiches depuis un dossier ; vérifier au premier déploiement staging que le back démarre et que l'index contient 84 fiches par langue.
- **Seuil de pertinence `min-score` = 6,5** : score BM25 brut, calibré sur les vraies fiches par `KnowledgeBaseFichesTest` (questions réelles 16 à 30, mots seuls 8,7 à 13). Quelques hors-sujet passent encore (6,9 à 11,6) et des mots isolés très courts (« kyc », « password », « mon colis ») tombent en « non trouvé ». Le seuil dépend de la taille du corpus : recalibrer et mettre à jour le test quand le nombre de fiches change beaucoup.
- **Tests admin** : certains tests valident de vraies transactions et nettoient en SQL dans `@AfterEach`.

## Mise en route
1. Déployer le back (prod), `ASSISTANT_ENABLED` encore à `false`.
2. Créer les comptes gratuits Groq et Gemini (sans carte bancaire), puis les ajouter dans l'admin et cliquer « Tester ».
3. Livrer l'app (bulle, icône dans Messages) et l'admin.
4. Mettre à jour la politique de confidentialité (yadony-web : Groq aux États-Unis, Google).
5. Passer `ASSISTANT_ENABLED=true`.

## Critères d'acceptation couverts
- [x] Réponse à partir des fiches avec chemin et boutons vers l'écran — `AssistantService`, `KnowledgeBase`.
- [x] Coût nul, fournisseurs gratuits interchangeables gérés depuis l'admin — `assistant.provider`, `LlmRouter`.
- [x] Bascule automatique quand un fournisseur est en panne ou à court de quota — `LlmRouter`, `ProviderHealthRegistry`.
- [x] Clés protégées (chiffrées, jamais renvoyées, ressaisie obligatoire si l'adresse change) — `AssistantProviderAdminService`.
- [x] Quota par utilisateur, interrupteur global, mode dégradé — `AssistantQuotaGuard`, `AssistantController`, `AssistantService`.
- [x] Aucune donnée personnelle envoyée au fournisseur, aucun texte de question dans les journaux.

## Tests
- `./mvnw test` → 10 372 tests, 0 échec, 0 erreur, 7 ignorés (BUILD SUCCESS, sur ad620268)
- `./mvnw test jacoco:report` → couverture lignes / branches : `assistant` 100 % / 94,7 %, `assistant.kb` 95,0 % / 91,8 %, `assistant.llm` 97,8 % / 89,9 %, `assistant.provider` 99,5 % / 92,1 % (≥ 90 % de lignes sur chaque paquet)
- Tests ajoutés : `AssistantAnswerParserTest`, `AssistantPromptBuilderTest`, `AssistantPropertiesTest`, `AssistantQuotaGuardTest`, `AssistantServiceTest`, `AssistantControllerIntegrationTest`, `AssistantControllerDisabledIntegrationTest`, `KnowledgeBaseLoaderTest`, `KnowledgeBaseSearchTest`, `KnowledgeBaseFichesTest`, `OpenAiCompatibleClientTest`, `ProviderAddressGuardTest`, `LlmRouterTest`, `ProviderHealthRegistryTest`, `ProviderCatalogTest`, `BaseUrlValidatorTest`, `SlugMakerTest`, `AssistantLlmProviderRepositoryTest`, `AssistantProviderAdminServiceTest`, `AdminAssistantProviderControllerIT`, `V324AssistantLlmProvidersMigrationTest`. Modifiés : `AdminPermissionsLotBTest`, `AdminPermissionsLotDTest` (compteurs de permissions), `NotificationPrefsClassificationTest`.

## Décisions techniques
- **Index Lucene en mémoire plutôt qu'envoyer toute la base au modèle** : les quotas gratuits sont trop petits ; seules les 4 meilleures fiches partent.
- **Format unique compatible OpenAI** : Groq, Gemini, Mistral, OpenRouter s'ajoutent sans code.
- **Apache HttpClient direct plutôt que `RestClient`** : nécessaire pour résoudre le DNS par la garde, abandonner une connexion sans lire le reste du corps, couper relances et compression.
- **Caffeine plutôt que la base pour quota et santé** : règle du projet (pas de Redis), coût nul, perte au redémarrage acceptée.
- **Fiches Markdown dans le dépôt** : relues en PR, validées au démarrage et par les tests.

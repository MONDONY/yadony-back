# Story Centre d'aide de la console admin : documentation, recherche et assistant IA (Backend)

**Date:** 2026-11-16
**Status:** ✅ Complète (PR jumelle : dony-admin `feature/centre-aide-ia`)

## Résumé
La console admin a un centre d'aide pour une personne non technique : 89 fiches qui expliquent chaque page et chaque geste, une recherche plein texte dans ces fiches (sans IA, toujours disponible) et un assistant qui répond en langage courant à partir des fiches, avec des boutons vers les pages de la console. Exemple : « comment je fais pour regarder les colis en cours » renvoie les étapes et le bouton `/colis?view=ACTIVE`.

## Fichiers créés
Paquet `com.yadony.api.assistant.adminhelp` :
- `AdminHelpController` : `/admin/help/**`, `hasRole('ADMIN')` (support compris).
- `AdminHelpService` : sommaire, fiche et fiches proches, recherche avec extrait, question à l'assistant, mode dégradé.
- `AdminHelpLoader` : lit et valide `classpath:assistant/admin-help/*.md` ; une fiche invalide fait échouer le démarrage.
- `AdminHelpPages` : pages de la console et permission de chacune (miroir des `definePageMeta` de dony-admin).
- `AdminHelpIndex` : index Lucene en mémoire (analyseur français de l'assistant de l'app).
- `AdminHelpPromptBuilder`, `AdminHelpRedactor`, `AdminHelpQuota`, `AdminHelpProperties`, `AdminHelpArticle`, `dto/*`.

Ressources : `src/main/resources/assistant/admin-help/*.md` (89 fiches, 6 rubriques).

## Fichiers modifiés
- `assistant/kb/AssistantAnalyzers` : rendu public pour réutiliser l'analyseur français.
- `application.yml` : bloc `yadony.assistant.admin-help` (seuil de pertinence seulement).
- `config/PlatformSettingKey`, `PlatformSettingsService`, `PlatformSettingsInitializer` : réglages `admin_help_assistant_enabled` (allumé par défaut) et `admin_help_daily_quota` (60, bornes 1 à 1000), modifiables dans Paramètres et audités.
- `i18n/messages_{fr,en}.properties` : `problem.admin-help.*`.

## Points d'entrée API
- `GET /api/v1/admin/help/articles` : rubriques, fiches (`accessible`, routes filtrées selon les permissions), `assistantEnabled`.
- `GET /api/v1/admin/help/articles/{id}` : fiche complète et 4 fiches proches ; 404 `admin-help-article-not-found`.
- `GET /api/v1/admin/help/search?q=` : fiches au-dessus d'un quart du meilleur score, avec la phrase la plus pertinente en extrait.
- `POST /api/v1/admin/help/ask` `{question, history}` : `{answer, answered, actions, sources, provider, remaining}` ; 404 `admin-help-assistant-disabled`, 429 `admin-help-quota-exceeded`.

## Comment ça fonctionne
1. Recherche des 4 meilleures fiches au-dessus de `min-score` (6,0) ; relance courte : la question précédente complète la recherche.
2. Aucune fiche : réponse fixe et bouton vers `/aide`, sans appel au modèle.
3. Appel du `LlmRouter` de l'assistant de l'app (mêmes fournisseurs, même bascule, même budget de temps).
4. Routes du modèle gardées seulement si elles figurent dans une fiche transmise ET que l'admin peut ouvrir la page.
5. Aucun fournisseur : mode dégradé avec les premières routes des fiches trouvées et les fiches en sources.

## Sécurité et données personnelles
- Emails, numéros (6 chiffres et plus) et identifiants sont masqués dans la question et l'historique avant l'envoi au fournisseur. La doc ne contient aucune donnée.
- Le texte de l'admin est neutralisé (`<` `>` retirés) : il ne peut pas se faire passer pour une consigne.
- Une fiche reste lisible par tout admin ; seuls ses boutons vers une page interdite au rôle sont retirés (`AdminHelpPages`).
- Quota par admin et par jour UTC (réglage `admin_help_daily_quota`) dans `assistant_user_daily_usage`, clé `admin:<id>` (ne croise pas un uid Firebase). Base injoignable : la question passe.

## Ajouter ou corriger une fiche
1. Écrire `src/main/resources/assistant/admin-help/<id>.md` (en-tête : `id`, `title`, `category`, `order`, `summary`, `permission` facultatif, `keywords`, `routes`).
2. Rubriques : `premiers-pas`, `comptes`, `argent`, `activite`, `support`, `systeme`.
3. Corps en Markdown restreint : `##`/`###`, listes `- ` et `1. `, `**gras**`, encadrés `> **Attention :**` / `> **Bon à savoir :**`, liens `[texte](/page)` ou `[texte](/aide/<id>)`. Pas de HTML, pas de tiret cadratin.
4. Une nouvelle page de la console doit être ajoutée à `AdminHelpPages`, sinon ses liens sont refusés au démarrage.
5. Lancer `./mvnw test -Dtest='AdminHelp*'` : `AdminHelpFichesTest` charge les vraies fiches et vérifie les questions courantes.

## Pièges
- Le seuil `min-score` dépend de la taille du corpus (score BM25 brut) : recalibrer `AdminHelpFichesTest` si beaucoup de fiches sont ajoutées.
- Aucun chiffre réglable dans l'admin (commission, plafonds, quotas) ne doit être écrit dans une fiche : renvoyer vers la page qui le règle.
- Le réglage « Assistant de la console » (Paramètres) coupe seulement l'assistant ; la doc et la recherche restent ouvertes.

## Tests
- `AdminHelpLoaderTest`, `AdminHelpFichesTest` (vraies fiches, questions réelles et hors-sujet), `AdminHelpServiceTest`, `AdminHelpQuotaTest`, `AdminHelpRedactorTest`, `AdminHelpControllerIT`.

---
id: assistant-ia
title: "Assistant IA : gérer les fournisseurs d'intelligence artificielle"
category: support
order: 50
summary: "La page Assistant IA règle les fournisseurs d'IA utilisés par l'assistant de l'application mobile et par celui de la console : ajout, test, ordre, pause."
permission: ASSISTANT_MANAGE
keywords: assistant IA, intelligence artificielle, IA, chatbot, bulle d'aide, fournisseur, fournisseurs, Groq, Gemini, Claude, Mistral, OpenRouter, clé d'API, modèle, tester un fournisseur, ordre des fournisseurs, quota atteint, en pause, erreur, limite de requêtes par jour, assistant ne répond pas
routes:
  - path: /assistant
    label: Ouvrir les fournisseurs d'IA
---
## À quoi ça sert

L'application mobile a un **assistant** : une bulle où l'utilisateur pose une question sur le fonctionnement de Yadony. L'assistant cherche la réponse dans les fiches d'aide de l'application, puis demande à une **intelligence artificielle** de rédiger une réponse courte avec des boutons vers les bons écrans. La console a le même type d'assistant, appuyé sur cette documentation. Voir [Utiliser le centre d'aide](/aide/centre-aide).

Ces deux assistants utilisent les **fournisseurs d'IA** réglés dans la page **Assistant IA** (menu **Support et communauté**).

## Comprendre la page

Les fournisseurs sont **essayés dans l'ordre**. Quand l'un est en panne ou a atteint son quota, l'assistant passe automatiquement au suivant.

Pour chaque fournisseur : **Ordre**, **Fournisseur** (nom et modèle), **État**, le nombre de requêtes **Aujourd'hui**, et les **Actions**. Les états :

- **Actif** : il répond normalement.
- **En pause** : désactivé par l'équipe.
- **Quota atteint** : sa limite du jour est atteinte.
- **Erreur** suivie d'un code, ou **de côté jusqu'à** une heure : il a échoué et sera réessayé plus tard.

> **Attention :** si aucun fournisseur n'est actif, un message rouge l'indique. L'assistant répond alors seulement en proposant des fiches d'aide.

## Étapes : ajouter un fournisseur

1. Ouvrez [Assistant IA](/assistant) et cliquez sur **Ajouter un fournisseur**.
2. Choisissez **Partir d'un modèle** pour préremplir l'adresse, ou remplissez : **Nom**, **Adresse de l'API**, **Modèle** et **Clé**.
3. Indiquez si besoin une **Limite de requêtes par jour** (vide = sans limite).
4. Enregistrez, puis cliquez sur **Tester** sur la ligne. Le test fait un vrai appel et affiche le résultat.

## Étapes : les autres gestes

- **Changer l'ordre** : glissez les lignes, ou utilisez les flèches **Monter** et **Descendre**.
- **Mettre en pause ou réactiver** : utilisez l'interrupteur **Actif** de la ligne.
- **Modifier** : changez les champs. Laissez la clé vide pour garder la clé actuelle. Changer l'adresse oblige à ressaisir la clé.
- **Supprimer** : l'assistant ne l'utilisera plus.

> **Bon à savoir :** la clé d'API est un secret. Elle n'est jamais réaffichée : seuls ses derniers caractères sont visibles.

## Qui peut le faire

Il faut la permission ASSISTANT_MANAGE (Super admin et Admin, jamais le Support).

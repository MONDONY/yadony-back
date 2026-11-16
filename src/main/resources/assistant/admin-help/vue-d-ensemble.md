---
id: vue-d-ensemble
title: Lire la page d'accueil « Vue d'ensemble »
category: premiers-pas
order: 11
summary: La page d'accueil montre ce qui est à traiter en priorité, les indicateurs clés et les volumes d'argent par devise.
keywords: accueil, vue d'ensemble, tableau de bord, dashboard, page d'accueil, à traiter, en attente, tout est à jour, indicateurs, chiffres clés, KPI, litiges ouverts, no-shows à arbitrer, alertes non résolues, identités à vérifier, versements retenus, escrow J+48, volumes par devise, séquestre, commission, santé de la plateforme
permission: METRICS_VIEW
routes:
  - path: /
    label: Ouvrir la Vue d'ensemble
---
## À quoi ça sert

**Vue d'ensemble** (sous-titre « Santé de la plateforme ») est la page qui s'ouvre après la connexion. Elle répond à deux questions : qu'est-ce qui m'attend, et comment va la plateforme. On y revient avec **Accueil** en haut du menu, le logo Yadony ou le raccourci « g » puis « h ».

## Étapes

1. Lisez d'abord la section **À traiter**. La phrase à droite du titre donne le total en attente, ou **Rien en attente, tout est à jour**.
2. Cliquez sur une carte pour ouvrir directement la liste concernée.
3. Parcourez ensuite **Indicateurs** et **Volumes par devise** pour suivre l'activité.

## Comprendre la section « À traiter »

Chaque carte affiche un nombre. Les cartes qui contiennent du travail passent devant, les plus graves en tête puis les plus remplies. Les cartes à zéro restent visibles, grisées, pour montrer que la file est vide.

- **Litiges ouverts** (rouge) : désaccords entre expéditeur et voyageur à trancher. Ouvre **Incidents**.
- **No-shows à arbitrer** (orange) : absences au rendez-vous déclarées, en attente de décision. Ouvre **Incidents**.
- **Alertes non résolues** (rouge) : anomalies détectées par la plateforme. Ouvre **Alertes**.
- **Identités à vérifier** (bleu) : vérifications d'identité (KYC) qui attendent une décision. Ouvre **Vérifications d'identité**.
- **Versements retenus** (rouge) : argent bloqué chez Yadony, par exemple pour un voyageur banni ou dont l'identité a été révoquée. Ouvre **Transactions** sur les versements retenus.
- **Escrow J+48** (orange) : paiements restés en séquestre depuis plus de 48 heures. Ouvre **Transactions** sur cette alerte.

Selon la version du serveur, les cartes **Identités à vérifier** et **Versements retenus** peuvent ne pas apparaître.

## Comprendre la section « Indicateurs »

- **Utilisateurs** : nombre total de comptes, avec le nombre d'actifs dessous.
- **Nouveaux (30j)** : inscriptions des 30 derniers jours, avec celles des 7 derniers jours dessous.
- **Comptes PRO** : comptes qui ont l'offre PRO.
- **Annonces actives** : trajets publiés par les voyageurs, avec ceux dont le voyage est en cours dessous.
- **Bids en attente** : colis (« bid » est le nom interne d'un colis) dont la demande attend encore une réponse, avec le nombre total de colis dessous.
- **Livraisons** : colis livrés.

## Comprendre « Volumes par devise »

Un tableau avec une ligne par devise (euro, franc CFA…). Les devises ne sont jamais additionnées entre elles.

- **Devise** : la monnaie de la ligne.
- **Escrow détenu** : argent payé par les expéditeurs et gardé en séquestre en attendant la livraison.
- **Libéré** : argent versé aux voyageurs après livraison.
- **Remboursé** : argent rendu aux expéditeurs.
- **Commission** : part gagnée par Yadony.

**Aucun paiement enregistré** s'affiche quand il n'y a encore aucune donnée.

## Bon à savoir

> **Bon à savoir :** les chiffres sont lus à l'ouverture de la page. Rechargez la page pour les mettre à jour. Les pastilles du menu, elles, se mettent à jour seules.

> **Bon à savoir :** si un message d'erreur remplace la page (par exemple « Impossible de charger la vue d'ensemble »), rechargez la page.

## Qui peut le faire

Il faut la permission METRICS_VIEW, que possèdent les trois rôles : Super admin, Admin et Support.

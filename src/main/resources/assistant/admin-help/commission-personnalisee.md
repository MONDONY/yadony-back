---
id: commission-personnalisee
title: Appliquer une commission personnalisée à un utilisateur
category: comptes
order: 46
summary: Remplacer le taux de commission de la plateforme pour un seul compte, ou revenir au taux global.
permission: USER_COMMISSION
keywords: commission personnalisée, dérogation de commission, taux de commission, commission réduite, commission spéciale, geste commercial, partenaire, ambassadeur, réinitialiser la commission, taux global, frais de service, pourcentage de commission, commission d'un voyageur, onglet gestion
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /tarification
    label: Ouvrir la Tarification
---
## À quoi ça sert

Par défaut, chaque utilisateur paie le taux de commission global de la plateforme, réglé dans la page [Tarification](/tarification). Une **dérogation** remplace ce taux pour un seul compte : par exemple pour un partenaire ou un geste commercial.

## Étapes

### Appliquer une dérogation

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. Dans le bloc **Commission**, saisissez le taux en pourcentage dans le champ (par exemple 8 pour 8 %). Une décimale est acceptée.
4. Cliquez sur **Appliquer**.
5. La fenêtre **Appliquer la dérogation de commission** rappelle le taux choisi. Cliquez sur **Appliquer**.

### Revenir au taux global

1. Dans le bloc **Commission**, cliquez sur **Réinitialiser** (visible seulement s'il existe une dérogation).
2. La fenêtre **Réinitialiser la commission** explique que l'utilisateur repassera au taux global de la plateforme. Cliquez sur **Réinitialiser**.

## Comprendre ce que vous voyez

- « Taux global appliqué » : aucune dérogation, la personne paie le taux de la plateforme.
- « Dérogation actuelle : X % » : le taux personnalisé en vigueur.
- L'en-tête de la fiche affiche aussi **Commission** : « taux global » ou le taux personnalisé.
- « Saisissez un taux entre 0 et 99,9 % » : la valeur saisie n'est pas acceptée.

## Bon à savoir

- Aucun motif n'est demandé, mais le changement est enregistré dans le journal du compte (action **Commission personnalisée modifiée**).
- Le taux global lui-même ne se change pas ici : il se règle dans [Tarification](/tarification).
- Dans l'onglet **Argent**, la colonne **Commission** des paiements montre la commission réellement prélevée sur chaque paiement.

## Qui peut le faire

Super admin et Admin. Le Support ne voit pas l'onglet **Gestion**.

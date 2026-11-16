---
id: limite-mensuelle-trajets
title: Limiter le nombre de trajets qu'un utilisateur publie par mois
category: comptes
order: 47
summary: Fixer ou retirer une limite mensuelle de trajets publiés pour un voyageur, depuis l'onglet Gestion.
permission: USER_COMMISSION
keywords: limite de trajets, limite mensuelle, nombre de trajets par mois, plafond de trajets, trop de trajets, limiter les annonces, quota de trajets, voyageur trop actif, trajets publiés ce mois-ci, aucune limite, retirer la limite, mois civil, particulier qui publie beaucoup, onglet gestion
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

La **Limite mensuelle de trajets** fixe combien de trajets un voyageur peut publier par mois civil (du 1er au dernier jour du mois). Elle permet de freiner un compte sans lui interdire de publier.

## Étapes

### Fixer une limite

1. Ouvrez la fiche du voyageur depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. Dans le bloc **Limite mensuelle de trajets**, saisissez un nombre entier dans le champ.
4. Cliquez sur **Enregistrer**.

Le message « Limite enregistrée. » confirme le changement.

### Retirer la limite

1. Videz complètement le champ (il affiche alors « Aucune limite » en gris).
2. Cliquez sur **Enregistrer**.

## Comprendre ce que vous voyez

- « Aucune limite » ou « Limite actuelle : N trajets par mois » : la règle en vigueur.
- « N trajets publiés ce mois-ci » : ce que la personne a déjà publié ce mois.
- Le texte d'aide sous le bloc rappelle les valeurs acceptées. Si vous saisissez autre chose, le message « Saisissez un nombre entier entre 1 et 100, ou laissez vide pour ne pas limiter. » s'affiche et rien n'est enregistré.

## Bon à savoir

- Aucune fenêtre de confirmation ni motif : l'enregistrement est immédiat. Il est noté dans le journal du compte (action **Limite mensuelle de trajets modifiée**).
- Un GP Pro validé n'est jamais limité, même si une limite est saisie.
- Le bloc n'apparaît pas si le serveur ne gère pas encore cette limite.
- Pour interdire complètement la publication, voir [Suspendre la publication](/aide/suspendre-publication).

## Qui peut le faire

Super admin et Admin (même droit que la commission). Le Support ne voit pas l'onglet **Gestion**.

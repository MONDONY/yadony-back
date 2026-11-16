---
id: alertes
title: "La page Alertes : traiter une alerte (résoudre, reporter, rouvrir)"
category: activite
order: 60
summary: "Les alertes signalent automatiquement un problème d'argent ou de colis. Ouvrez l'alerte, lisez Que faire, corrigez, puis résolvez-la ou reportez-la."
permission: ALERT_VIEW
keywords: alertes, alerte, alerte critique, attention, info, anomalie, problème de paiement, séquestre, versement bloqué, versement retenu, solde pawaPay bas, rapprochement Stripe, résoudre une alerte, marquer comme résolue, faux positif, reporter une alerte, rouvrir une alerte, recalculer, forcer le versement, alertes ouvertes, résolues cette semaine
routes:
  - path: /alertes
    label: Ouvrir les alertes
---
## À quoi ça sert

La plateforme surveille en continu l'argent et les colis. Quand une règle détecte une anomalie (un paiement bloqué, un versement qui a échoué, un solde d'opérateur trop bas...), elle crée une **alerte** dans la page **Alertes** (menu **Activité**).

## Comprendre la page

En haut, une synthèse : **Critiques ouvertes**, **Attention ouvertes**, **Info ouvertes** et **Résolues cette semaine**.

Ensuite trois colonnes :

1. La **file** : filtres **Ouvertes**, **Reportées**, **Résolues**, **Toutes**, un filtre de catégorie (**Toutes catégories**) et une recherche (paiement, colis, code). Les alertes du même type sont regroupées. La gravité est indiquée : **Critique**, **Attention** ou **Info**.
2. Le **dossier** : **Que faire** (la marche à suivre), **Ce que vérifie la règle**, **Données de l'alerte**, **Historique**, et les **Autres alertes sur ce paiement ou ce colis**. Les liens **Voir le colis**, **Voir le paiement** ou la fiche de l'utilisateur ouvrent l'élément concerné.
3. La **décision**.

## Étapes : traiter une alerte

1. Ouvrez [Alertes](/alertes). Commencez par les **Critiques**.
2. Cliquez sur une alerte et lisez **Que faire**.
3. Faites la correction indiquée (souvent dans la fiche du colis, du paiement ou de l'utilisateur).
4. Pour certaines règles, **Recalculer** vérifie si le problème existe toujours (**Lignes en faute actuellement**).
5. Choisissez une décision :
   - **Résoudre : corrigé** : le problème est réglé.
   - **Résoudre : faux positif** : l'alerte n'était pas justifiée.
   - **Reporter** (**4 h**, **24 h**, **3 jours** ou **7 jours**) : l'alerte reste ouverte mais quitte la file jusqu'à la date choisie.
6. Écrivez **Ce qui a été fait (obligatoire)** pour une résolution, puis confirmez. La note est conservée dans l'audit.

Une alerte résolue par erreur peut être rouverte avec **Rouvrir l'alerte**.

## Cas particulier : paiement à libérer

Sur certaines alertes de paiement, un bloc **Corriger** permet de **Forcer le versement au voyageur**.

> **Attention :** ce geste verse l'argent au voyageur et il est définitif. Vérifiez d'abord que le colis a bien été livré. Il est réservé aux super-administrateurs.

## Qui peut le faire

- Voir les alertes : permission ALERT_VIEW (Support compris).
- Résoudre, reporter, rouvrir : permission ALERT_RESOLVE (Support compris).

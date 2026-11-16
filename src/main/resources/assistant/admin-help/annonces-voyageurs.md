---
id: annonces-voyageurs
title: Consulter les annonces des voyageurs et les colis d'un trajet
category: activite
order: 20
summary: L'onglet Annonces liste les trajets publiés par les voyageurs. Depuis un colis, voir son annonce ou les autres colis du même trajet.
permission: BID_VIEW
keywords: annonces, annonce voyageur, trajets publiés, trajet, voyage, voir l'annonce, autres colis sur ce trajet, colis du même trajet, capacité, kilos disponibles, prix au kilo, date de départ, voyage à étapes, étape, annonce complète, annonce publiée, annonce retirée, paiements de ce trajet, liste des voyages, offre de transport, bateau, avion
routes:
  - path: /colis?tab=announcements
    label: Voir les annonces
---
## À quoi ça sert

Un voyageur publie une annonce pour chaque trajet sur lequel il peut emporter des colis. L'onglet **Annonces** de la page **Colis** liste toutes ces annonces. Il sert à vérifier un trajet, son prix, la place restante, et à modérer une annonce problématique.

## Étapes

1. Ouvrez **Colis**, puis cliquez sur l'onglet **Annonces**.
2. Parcourez la liste avec les flèches de pagination en bas.
3. Pour voir les paiements liés à un trajet, cliquez sur **Paiements de ce trajet** sous le nom du trajet.

Depuis la fiche d'un colis (onglet **Résumé**, section **Trajet**) :

1. Cliquez sur **Voir l'annonce**. La console passe à l'onglet **Annonces**, réduit à l'annonce de ce colis. Un bandeau « Annonce du colis » le rappelle ; cliquez sur **Voir toutes les annonces** pour revenir à la liste complète.
2. Ou cliquez sur **1 autre colis sur ce trajet** (ou **... autres colis sur ce trajet**). La console revient à l'onglet **Colis**, vue **Tous**, avec seulement les colis de ce trajet. Un bandeau « Colis d'un même trajet » le rappelle ; cliquez sur **Voir tous les colis** pour enlever ce filtre. Si le colis est seul, la fiche indique « Seul colis sur ce trajet ».

## Comprendre ce que vous voyez

Les colonnes du tableau :

- **Trajet** : villes de départ et d'arrivée. Pour un voyage à étapes, une mention « Étape 1/3 · voyage ... » s'ajoute.
- **Voyageur** : le nom du voyageur.
- **Départ** : la date de départ.
- **Capacité** : les kilos encore disponibles.
- **Prix/kg** : le prix demandé par kilo.
- **Statut** : affiché sous forme de code. **ACTIVE** = publiée, **FULL** = complète, **IN_PROGRESS** = voyage en cours, **COMPLETED** = terminée, **CANCELLED** = annulée, **REMOVED_BY_ADMIN** = retirée par un admin.
- Une dernière colonne avec **Retirer** ou **Restaurer**, visible seulement si vous avez le droit de retirer du contenu.

Dans la fiche d'un colis, le statut de l'annonce s'affiche en clair : **Brouillon**, **Publiée**, **Complète**, **Voyage en cours**, **Terminée**, **Annulée** ou **Retirée par un admin**. Si l'annonce n'existe plus, la fiche affiche « L'annonce de ce colis n'existe plus. ».

## Bon à savoir

- Retirer ou restaurer une annonce est expliqué dans [Retirer ou restaurer une annonce](/aide/retirer-une-annonce).
- Le lien **Paiements de ce trajet** n'apparaît que si vous avez accès aux transactions.
- Les filtres « trajet » et « annonce » restent dans l'adresse de la page : le lien peut être partagé.
- S'il n'y a aucune annonce, le tableau affiche « Aucune annonce ».

## Qui peut le faire

Tous les rôles qui voient la page Colis peuvent consulter les annonces. Le retrait et la restauration demandent le droit de retirer du contenu, que les rôles Super admin et Admin ont, mais pas le rôle Support.

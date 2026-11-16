---
id: tarification
title: "Tarification : la grille de commission, le simulateur de marge et l'historique"
category: argent
order: 130
summary: "La page Tarification règle la commission de Yadony par tranches de prix, simule la marge par moyen de paiement et garde l'historique des grilles."
permission: CONFIG_MANAGE
keywords: tarification, commission, taux de commission, grille de commission, paliers, tranches, taux marginal, commission minimum, programmer une grille, nouvelle grille, hausse de commission, préavis, simulateur, marge, marge HT, coût prestataire, frais de paiement, TVA sur la commission, historique des grilles, prix, frais Yadony
routes:
  - path: /tarification
    label: Ouvrir la grille de commission
  - path: /tarification?tab=simulateur
    label: Ouvrir le simulateur de marge
  - path: /tarification?tab=historique
    label: Voir l'historique des grilles
---
## À quoi ça sert

Yadony prend une **commission** sur chaque envoi. La page **Tarification** (menu **Argent**) décide de cette commission. Elle a trois onglets : **Grille**, **Simulateur** et **Historique**.

## Comprendre la grille

La commission est calculée par **tranches** (taux marginal) : un taux pour la partie du prix jusqu'à un premier palier, un autre taux pour la partie au-delà, etc. Une **commission minimum** par devise s'applique aux petits envois.

Tant qu'aucune grille n'est en vigueur, un **taux unique** s'applique à tout (il se règle dans les [Paramètres](/aide/parametres)).

L'onglet **Grille** montre :

- la **grille en vigueur** (lecture seule) ;
- la **grille programmée**, s'il y en a une, avec sa date d'effet ;
- le bouton **Préparer une nouvelle grille**.

## Étapes : programmer une nouvelle grille

1. Ouvrez [Tarification](/tarification), onglet **Grille**, et cliquez sur **Préparer une nouvelle grille**. Elle est préremplie avec la grille actuelle.
2. Réglez les tranches : pour chacune, **À partir de (€)** et **Taux (%)**. **Ajouter une tranche** en crée une ; l'icône de suppression en retire une.
3. Réglez la **Commission minimum par devise** et, si besoin, la **TVA sur la commission (%)**.
4. Choisissez la **Date et heure d'effet** (ou **Immédiat**) et ajoutez une **Note** pour expliquer le changement.
5. Regardez le tableau **Comparaison avec la grille en vigueur** : commission actuelle, nouvelle commission et écart pour plusieurs montants.
6. Cliquez sur **Programmer cette grille**, puis confirmez avec **Programmer**.

> **Attention :** si la nouvelle grille **augmente** la commission et prend effet dans moins de 30 jours, la console demande de recopier la phrase « HAUSSE SANS PRÉAVIS ». Les utilisateurs doivent normalement être prévenus avant une hausse.

Pour annuler une grille programmée avant sa date : **Annuler la programmation**, puis **Confirmer l'annulation**.

## Le simulateur

L'onglet [Simulateur](/tarification?tab=simulateur) calcule, pour un montant net, ce que paie l'expéditeur et ce que garde Yadony selon le **moyen de paiement** (carte européenne, carte premium, carte hors Europe, mobile money). Colonnes : **Montant net du voyageur**, **Commission**, **Total payé**, **Coût prestataire** et **Marge HT**. Les chiffres sont indicatifs.

## L'historique

L'onglet [Historique](/tarification?tab=historique) liste toutes les grilles avec leur état : **En vigueur**, **Programmée**, **Passée** ou **Annulée**, leur date d'effet, leur auteur et leur note.

## Qui peut le faire

Il faut la permission CONFIG_MANAGE (Super admin et Admin).

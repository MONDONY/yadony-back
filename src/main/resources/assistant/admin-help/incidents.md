---
id: incidents
title: "La page Incidents : litiges et absences déclarées (no-shows)"
category: activite
order: 50
summary: "La page Incidents réunit les litiges sur les colis et les absences déclarées (no-show). Une file à gauche, le dossier au centre, la décision à droite."
permission: DISPUTE_VIEW
keywords: incidents, litige, litiges, dispute, réclamation, problème de colis, colis perdu, colis abîmé, no-show, absence, absent, voyageur absent, expéditeur absent, destinataire absent, déclaration d'absence, remboursement gelé, en retard, échéance proche, à traiter, traités, file des incidents
routes:
  - path: /incidents
    label: Ouvrir les incidents à traiter
  - path: /incidents?tab=disputes
    label: Voir seulement les litiges
  - path: /incidents?tab=noshows
    label: Voir seulement les absences
---
## À quoi ça sert

Quand un envoi se passe mal, deux types d'**incidents** arrivent dans la page **Incidents** (menu **Activité**) :

- les **litiges** : une partie conteste (colis perdu, colis abîmé, désaccord...). L'argent du colis reste bloqué tant que l'équipe n'a pas tranché ;
- les **absences déclarées** (no-show) : quelqu'un n'est pas venu au rendez-vous, à la remise du colis (**Départ**) ou à la livraison (**Arrivée**).

## Comprendre la page

En haut, une synthèse :

- **À traiter** : le nombre de litiges et d'absences en attente.
- **En retard** : plus de 7 jours ou délai échu.
- **Remboursements gelés** : l'argent bloqué par des litiges, par devise.
- **Échéance proche** : les absences à trancher dans les 2 heures.

Ensuite trois colonnes :

1. La **file** : filtres **À traiter**, **Traités**, **Tous**, et **Tous**, **Litiges**, **Absences**. Les incidents sont regroupés par nature (**Litiges**, **Absences déclarées**), avec leur âge.
2. Le **dossier** : le colis et le paiement, le trajet, l'historique, et les **Autres incidents des deux parties**. **Voir la fiche** et **Voir le paiement de ce colis** ouvrent les pages liées.
3. La **décision** : les choix possibles, chacun avec une explication.

## Étapes

1. Ouvrez [Incidents](/incidents). La vue **À traiter** est affichée.
2. Cliquez sur un incident dans la file. Commencez par ceux **En retard** ou à **Échéance proche**.
3. Lisez le dossier, et si besoin les échanges entre les parties.
4. Choisissez une décision, écrivez ce que vous avez vérifié (obligatoire), puis confirmez. Le bouton propose aussi de **passer au suivant**.

Pour le détail des décisions :

- [Résoudre un litige](/aide/resoudre-un-litige)
- [Traiter une absence déclarée](/aide/traiter-une-absence)

> **Attention :** un incident traité ne se rouvre pas. Une nouvelle décision passe par un nouvel incident.

## Qui peut le faire

- Voir la page : permission DISPUTE_VIEW (Support compris).
- Trancher : permission DISPUTE_RESOLVE (Super admin et Admin). Sinon, le panneau affiche « Votre rôle ne permet pas de trancher les incidents ».

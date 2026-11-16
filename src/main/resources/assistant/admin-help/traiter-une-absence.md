---
id: traiter-une-absence
title: "Traiter une absence déclarée (no-show) : confirmer ou rejeter"
category: activite
order: 52
summary: "Un voyageur, un expéditeur ou un destinataire ne s'est pas présenté. Vérifiez, puis confirmez l'absence ou rejetez la déclaration."
permission: DISPUTE_RESOLVE
keywords: no-show, absence, absent, ne s'est pas présenté, lapin, rendez-vous manqué, voyageur absent, expéditeur absent, destinataire absent, absence à la remise, absence à la livraison, confirmer l'absence, rejeter la déclaration, absence contestée, contesté, en attente, départ, arrivée, échéance
routes:
  - path: /incidents?tab=noshows
    label: Ouvrir les absences déclarées
---
## À quoi ça sert

Quand une personne ne vient pas au rendez-vous, l'autre peut **déclarer une absence** (no-show) dans l'application :

- à la **remise** du colis (étiquette **Départ**) : par exemple le voyageur attend un expéditeur qui ne vient pas ;
- à la **livraison** (étiquette **Arrivée**) : par exemple le destinataire n'est pas là.

La personne accusée peut contester. L'équipe tranche dans la page [Incidents](/aide/incidents).

## Comprendre les statuts

- **En attente** : la déclaration attend une réponse ou une décision.
- **Contesté** : la personne accusée affirme qu'elle était présente. Un litige peut en découler.
- **Absence confirmée** : l'absence est établie.

## Étapes

1. Ouvrez [les absences déclarées](/incidents?tab=noshows). Traitez d'abord celles en **Échéance proche** (à trancher sous 2 heures).
2. Cliquez sur une absence. Le dossier indique qui déclare, qui est accusé, le moment (à la remise ou à la livraison), la **Remise prévue**, la **Réponse attendue**, le colis et le paiement.
3. Vérifiez : appelez si besoin, regardez la conversation, les preuves de présence.
4. Choisissez :
   - **Confirmer l'absence** : l'absence est établie.
   - **Rejeter la déclaration** : la déclaration est classée, le colis continue.
5. Écrivez ce que vous avez vérifié. Des notes rapides sont proposées : **Appel au voyageur sans réponse**, **Preuve de présence fournie**, **Aucune contestation reçue**.
6. Confirmez.

> **Attention :** la décision est définitive : un incident traité ne se rouvre pas. Seules les décisions possibles pour cette absence sont proposées.

## Qui peut le faire

Il faut la permission DISPUTE_RESOLVE (Super admin et Admin). Le Support voit les absences mais ne tranche pas.

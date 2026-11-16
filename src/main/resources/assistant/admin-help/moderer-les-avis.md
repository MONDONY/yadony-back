---
id: moderer-les-avis
title: "Modérer les avis et les notes des voyageurs"
category: activite
order: 71
summary: "L'onglet Avis de la page Signalements : exclure un avis de la moyenne, le réintégrer, le supprimer ou le restaurer."
permission: RATING_MODERATE
keywords: avis, notes, note moyenne, étoiles, faux avis, avis injurieux, représailles, modérer un avis, exclure de la moyenne, réintégrer dans la moyenne, supprimer un avis, restaurer un avis, avis anonyme, destinataire, évaluation, commentaire, recalcul de la note
routes:
  - path: /signalements
    label: Ouvrir Signalements puis Avis
---
## À quoi ça sert

Après une livraison, les utilisateurs notent le voyageur et laissent un avis. Un avis peut être faux, injurieux ou hors sujet. L'onglet **Avis** de la page [Signalements](/aide/signalements) permet de le modérer.

## Étapes

1. Ouvrez [Signalements](/signalements), puis l'onglet **Avis**.
2. Cliquez sur un avis. Le dossier montre la note, le commentaire, son auteur (ou « Avis anonyme » quand il vient du destinataire du colis) et les signalements qui le visent.
3. Choisissez une action :
   - **Exclure de la moyenne** : l'avis reste visible mais ne compte plus dans la note.
   - **Réintégrer dans la moyenne** : l'avis compte de nouveau.
   - **Supprimer l'avis** : l'avis disparaît et la note est recalculée. Il se restaure depuis le filtre **Supprimés**.
   - **Restaurer l'avis** : l'avis redevient public et la note est recalculée.
4. Choisissez ou écrivez le motif (par exemple **Faux avis**, **Propos injurieux**, **Hors sujet**, **Représailles**, **Doublon**, **Contenu illicite**), puis confirmez.

> **Bon à savoir :** aucune notification n'est envoyée à l'auteur de l'avis.

> **Bon à savoir :** si l'auteur a noté de nouveau la même livraison, l'ancien avis ne peut plus être restauré.

## Qui peut le faire

Il faut la permission RATING_MODERATE (Support compris) pour exclure et réintégrer. La suppression définitive d'un avis demande RATING_DELETE (Super admin et Admin). Si aucune action n'apparaît, c'est que votre rôle ne le permet pas.

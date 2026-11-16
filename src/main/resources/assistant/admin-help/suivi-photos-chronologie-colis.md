---
id: suivi-photos-chronologie-colis
title: Voir les photos et la chronologie d'un colis
category: activite
order: 16
summary: Dans l'onglet Suivi de la fiche colis, voir les photos par étape et l'historique complet (scans, paiements, gestes des parties et des admins).
permission: BID_VIEW
keywords: chronologie, historique du colis, suivi du colis, photos du colis, photo de remise, photo de livraison, preuve de livraison, scan, scan de départ, scan d'arrivée, scan en transit, GPS, position, qui a fait quoi, journal, événements, preuves du litige, refus du colis, photo indisponible, visionneuse, code de remise, retour à l'expéditeur
routes:
  - path: /colis
    label: Ouvrir Colis
---
## À quoi ça sert

L'onglet **Suivi** de la fiche colis répond à deux questions : « qu'a-t-on vu du colis ? » (les photos) et « que s'est-il passé, quand et par qui ? » (la chronologie). C'est l'onglet à ouvrir pour vérifier une livraison, préparer un litige ou répondre à « où est mon colis ? ».

## Étapes

1. Ouvrez **Colis**, cliquez sur la ligne du colis.
2. Dans la fiche, cliquez sur l'onglet **Suivi**.
3. En haut, parcourez les photos par étape. Cliquez sur une vignette pour l'agrandir. Dans la visionneuse, passez d'une photo à l'autre sur toutes les étapes.
4. En dessous, lisez la **Chronologie**, du plus ancien au plus récent.

## Comprendre ce que vous voyez

### Les photos

Quatre étapes sont toujours affichées, même vides : **Photos du colis** (prises à la création), **Remise du colis**, **En transit** et **Livraison**. Deux étapes n'apparaissent que si elles ont des photos : **Refus du colis** et **Preuves du litige**. Chaque étape indique le nombre de photos, la date et le lieu de la première. Une étape vide affiche « Aucune photo pour cette étape ». Une photo qui ne se charge plus affiche « Photo indisponible ».

### La chronologie

Chaque ligne donne ce qui s'est passé, la date et l'heure, et qui l'a fait : « par » une personne, « par l'admin ... » pour un geste d'administrateur, ou « automatique » pour une action du système. Selon les cas, une ligne ajoute un détail, une position GPS ou une pastille **Photo**.

Exemples de lignes : « Demande de colis créée », « Accepté par le voyageur », « Scan de départ », « Scan en transit », « Scan d'arrivée », « Livraison confirmée (code de remise) », « Livré », « Code de remise généré », « Destinataire modifié », « Colis rendu à l'expéditeur », « Litige ouvert », « Colis annulé par un admin », « Absence confirmée par un admin », « Notation laissée ». Les notes s'affichent en étoiles (par exemple « 4 sur 5 étoiles »).

Le point de couleur de chaque ligne aide à lire vite : rouge pour les problèmes (litige, absence, annulation, refus, expiration), vert pour l'argent, bleu pour les scans et la livraison, gris pour le reste.

## Bon à savoir

- Si la chronologie est vide : « Aucun évènement enregistré pour ce colis : ni scan, ni trace dans le journal, ni paiement. ».
- Les liens des photos expirent au bout d'un moment. Si une photo ne s'affiche plus dans la visionneuse, elle se recharge et revient sur la même image.
- Les photos et la chronologie sont en lecture seule : rien ne peut être modifié ici.
- Pour un litige, ces éléments sont les preuves à examiner avant de trancher dans [Résoudre un litige](/aide/resoudre-un-litige).

## Qui peut le faire

Tous les rôles qui voient la page Colis (Super admin, Admin, Support).

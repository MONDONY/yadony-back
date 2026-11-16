---
id: voir-colis-en-cours
title: Voir les colis en cours de transport
category: activite
order: 11
summary: Afficher tous les colis pas encore terminés (en attente de paiement, acceptés, remis, en transit ou arrivés) avec la vue En cours.
permission: BID_VIEW
keywords: colis en cours, colis en transit, colis en route, envois en cours, transport en cours, colis actifs, colis pas encore livrés, colis remis, colis arrivés, en attente de remise, suivre un envoi, où est le colis, colis chez le voyageur, colis dans les bagages, colis en voyage, vue en cours, livraison en attente, colis non livrés, colis en retard, suivi des envois
routes:
  - path: /colis?view=ACTIVE
    label: Voir les colis en cours
  - path: /colis?view=ACTIVE&status=IN_TRANSIT
    label: Voir les colis en transit
  - path: /colis?view=ACTIVE&status=ARRIVED
    label: Voir les colis arrivés
---
## À quoi ça sert

La vue **En cours** de la page Colis montre tous les envois qui ne sont pas encore terminés. C'est la liste à consulter pour savoir quels colis voyagent en ce moment, lesquels attendent d'être remis au voyageur et lesquels sont arrivés mais pas encore livrés au destinataire.

## Étapes

1. Ouvrez **Colis** dans le menu, onglet **Colis**.
2. Cliquez sur la vue **En cours** sous les onglets. Le nombre à côté indique combien de colis sont concernés.
3. Pour ne garder qu'une étape, choisissez-la dans le filtre **Statut précis**, par exemple **En transit** ou **Arrivé**.
4. Pour cibler un trajet ou une personne, utilisez la recherche (voir [Rechercher et filtrer les colis](/aide/rechercher-un-colis)).
5. Cliquez sur une ligne pour ouvrir la fiche du colis et voir où il en est.

## Comprendre ce que vous voyez

La vue **En cours** contient les colis aux statuts suivants :

- **Attente paiement** : le voyageur a accepté, l'expéditeur n'a pas encore payé.
- **En attente** : la demande attend la réponse du voyageur.
- **Payé (séquestre)** : l'expéditeur a payé, l'argent est bloqué chez Yadony en attendant la décision du voyageur.
- **Accepté** : le voyageur a accepté, le colis doit encore lui être remis.
- **Remis** : le colis est dans les mains du voyageur, en attente du départ.
- **En transit** : le colis voyage avec le voyageur.
- **Arrivé** : le colis est à destination, il reste à le livrer au destinataire.

Dans la fiche, la frise d'étapes (Créé, Payé, Accepté, Remis, En transit, Livré) montre en un coup d'œil l'étape atteinte. La phrase sous le titre dit ce qu'on attend, par exemple « Colis en route avec le voyageur, en attente de la livraison. ».

Une étiquette **Retard** dans la colonne Statut signale un colis encore en route alors que la date d'arrivée prévue du trajet est dépassée.

## Bon à savoir

- Un colis en transit qui a aussi une alerte apparaît à la fois dans **En cours** et dans **À traiter**.
- Pour savoir précisément où se trouve un colis, ouvrez l'onglet **Suivi** de sa fiche : les scans de départ, de transit et d'arrivée y sont datés, parfois avec une position GPS et une photo. Voir [Voir les photos et la chronologie d'un colis](/aide/suivi-photos-chronologie-colis).
- Les colis livrés ne sont plus dans **En cours** : ils passent dans **Livrés depuis 3 jours** puis restent dans **Terminés**.
- Les colis annulés, refusés, expirés ou marqués absents sont dans **Annulés / refusés**.

## Qui peut le faire

Tous les rôles qui voient la page Colis (Super admin, Admin, Support).

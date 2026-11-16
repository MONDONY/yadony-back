---
id: gp-pro
title: "GP Pro : examiner les demandes de badge, accorder ou retirer le badge"
category: comptes
order: 60
summary: La page GP Pro traite les demandes du badge des transporteurs professionnels, les titulaires à revoir et les particuliers à confirmer.
permission: GP_PRO_REVIEW
keywords: GP Pro, badge GP Pro, transporteur professionnel, badge pro, demande de badge, accepter la demande, refuser la demande, accorder GP Pro, retirer GP Pro, retirer le badge, titulaires à revoir, justificatifs, numéro d'entreprise, nom commercial, motif de refus, badge de confiance, professionnel, GP
routes:
  - path: /gp-pro
    label: Ouvrir les demandes GP Pro
  - path: /gp-pro?tab=revue
    label: Voir les titulaires à revoir
---
## À quoi ça sert

Le badge **GP Pro** signale aux expéditeurs un voyageur qui transporte des colis à titre professionnel. Il s'affiche sur son profil et ses trajets. La page **GP Pro** (menu **Comptes**) sert à examiner les demandes de badge, à surveiller les titulaires et à retirer le badge quand il le faut.

La page a deux onglets :

- **Demandes à examiner** : les personnes qui ont demandé le badge, les plus anciennes d'abord.
- **Titulaires à revoir** : les personnes qui ont déjà le badge et pour lesquelles une alerte automatique est ouverte.

Sous ces onglets se trouve la file [Particuliers à confirmer](/aide/particuliers-a-confirmer).

## Étapes : examiner une demande

1. Ouvrez [GP Pro](/gp-pro), onglet **Demandes à examiner**.
2. Cliquez sur une demande dans la file à gauche. Le détail s'ouvre à droite.
3. Lisez **La demande** (nom commercial, numéro d'entreprise, pays de l'entreprise), les **Justificatifs** et le **Profil du demandeur** (identité, membre depuis, trajets publiés, livraisons, note moyenne, litiges et signalements ouverts).
4. Cliquez sur **Accepter** ou **Refuser**.
5. Pour un refus, écrivez le **motif communiqué à l'utilisateur** (obligatoire), puis confirmez.

> **Bon à savoir :** **Accepter** est impossible tant que l'identité de la personne n'est pas vérifiée. Voir [Vérifications d'identité](/aide/verifications-identite).

> **Bon à savoir :** les liens vers les justificatifs ne restent valables que quelques minutes. Cliquez sur **Nouveaux liens** s'ils ne s'ouvrent plus. Les fichiers sont supprimés dès la décision.

## Étapes : retirer ou accorder le badge

- **Retirer** : onglet **Titulaires à revoir**, choisissez le titulaire, cliquez sur **Retirer GP Pro**, écrivez le motif (obligatoire, lu par la personne), puis **Retirer le badge**.
- **Accorder sans demande** : depuis la fiche de l'utilisateur, onglet **Gestion**, bouton **Accorder GP Pro**. Une note interne facultative peut être ajoutée, elle n'est jamais envoyée à la personne. Voir [l'onglet Gestion de la fiche](/aide/fiche-utilisateur-gestion).

Sur un titulaire à revoir, chaque alerte s'écarte à part avec **Écarter**. Une alerte ne retire jamais le badge : c'est toujours une décision de l'équipe.

## Ce qui se passe après une décision

- **Accepter** ou **Accorder** : le badge s'affiche aussitôt. La personne est prévenue par notification et par e-mail.
- **Refuser** : la personne reçoit le motif et peut refaire une demande tout de suite.
- **Retirer** : le badge disparaît aussitôt. La personne reçoit le motif et peut refaire une demande.

## Qui peut le faire

Il faut la permission GP_PRO_REVIEW (Super admin et Admin). Le Support n'a pas accès à cette page.

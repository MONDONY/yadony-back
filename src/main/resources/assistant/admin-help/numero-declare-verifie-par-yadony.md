---
id: numero-declare-verifie-par-yadony
title: Marquer un numéro déclaré « Vérifié par Yadony »
category: comptes
order: 53
summary: Confirmer à la main le numéro qu'un utilisateur a déclaré sans SMS, pour qu'il puisse rattacher ses colis.
permission: USER_PHONE_VERIFY
keywords: numéro déclaré, téléphone déclaré, sans SMS, pays bloqué, SMS non reçu, Vérifié par Yadony, numéro validé par Yadony, rattacher les colis, invitation destinataire
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

Dans certains pays, les SMS n'arrivent pas : l'utilisateur déclare alors son numéro sans code. Ce numéro reste « non vérifié ». Quand vous avez un moyen sûr de confirmer qu'il lui appartient, vous pouvez le marquer **Vérifié par Yadony**.

## Ce que ça change

- Les colis envoyés à ce numéro se rattachent au compte, et une invitation de destinataire peut être acceptée.
- Rien d'autre : ce numéro ne sert **jamais** à la connexion ni aux paiements.

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Repérez le numéro déclaré et son niveau dans l'identité.
3. Lancez la validation du numéro déclaré : elle porte sur le numéro affiché à l'écran.

## Bon à savoir

- Si la personne déclare ensuite un autre numéro, la validation tombe et le numéro redevient « déclaré ».
- Si le numéro a changé depuis l'ouverture de la fiche, la validation est refusée : rouvrez la fiche et vérifiez le nouveau numéro.
- Refusé si le compte a déjà un numéro vérifié par SMS, si aucun numéro déclaré n'est confirmé, ou si le numéro est déjà vérifié par SMS ou validé sur un autre compte.
- Un numéro revendiqué par plusieurs comptes validés ne rattache aucun colis automatiquement.
- L'action est journalisée avec votre identifiant, sans le numéro.

## Qui peut le faire

Super admin et Admin.

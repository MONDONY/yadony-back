---
id: fiche-utilisateur-identite
title: Voir la vérification d'identité (KYC) d'un utilisateur
category: comptes
order: 35
summary: "L'onglet Identité (KYC) de la fiche : statut, fournisseur, motif de refus, décision admin, historique et gestes possibles."
permission: USER_KYC
keywords: KYC d'un utilisateur, vérification d'identité, identité vérifiée, pièce d'identité, selfie, statut KYC, motif de rejet, code de rejet, fournisseur, Didit, Stripe Identity, session de vérification, décision admin, historique KYC, identité refusée, identité en cours, onglet identité, réinitialiser le KYC, révoquer l'identité
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /kyc
    label: Ouvrir les vérifications d'identité
---
## À quoi ça sert

L'onglet **Identité (KYC)** de la fiche utilisateur montre où en est la vérification d'identité d'une personne : ce que dit le fournisseur de vérification, les décisions prises par un administrateur, et l'historique. On y fait aussi les mêmes gestes que sur la page [Vérifications d'identité](/kyc).

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Identité (KYC)**. Le dossier se charge (« Chargement du KYC... »).
3. Lisez les informations, puis la **Décision admin** et l'**Historique**.
4. Pour voir les pièces et le selfie, cliquez sur **Voir la session chez le fournisseur** (le lien s'ouvre dans un nouvel onglet).
5. Pour agir, utilisez les boutons du bloc **Décision** en bas.

## Comprendre ce que vous voyez

- **Statut du compte** et **Statut de vérification** : l'état de la vérification, sous forme de code (par exemple VERIFIED pour vérifiée, PENDING pour en cours, REJECTED pour refusée).
- **Motif de rejet** et **Code de rejet** : la raison d'un refus, ou « Aucun ».
- **Fournisseur** : **Didit** ou **Stripe Identity**, le service qui contrôle les pièces.
- **Session courante** : la référence de la vérification chez le fournisseur. « Aucune session : vérification jamais démarrée » veut dire que la personne n'a encore rien envoyé.
- **Statut chez le fournisseur**, **Créée le** et **Dernière erreur du fournisseur**.
- **Décision admin** : **Validée par un admin**, **Refusée par un admin** ou **Révoquée par un admin**, avec l'administrateur, la date et le motif. Sinon « Aucune décision manuelle ».
- **Historique** : chaque étape avec la date et l'auteur (**Utilisateur**, **Administrateur**, **Système** ou **Fournisseur**). Exemples : **Parcours de vérification commencé**, **Identité validée par le fournisseur**, **Mise en revue manuelle par le fournisseur**, **Session expirée chez le fournisseur**, **Vérification réinitialisée par un admin**, **Identité révoquée par un admin**.

Si le fournisseur ne répond pas, un bandeau l'indique : seules les données enregistrées chez Yadony sont affichées.

## Les gestes possibles

- **Valider l'identité** et **Refuser** : quand la vérification n'est pas encore validée. Voir [Valider une identité](/aide/verifications-identite) et [Refuser une vérification](/aide/verifications-identite).
- **Révoquer** : quand l'identité est déjà validée. Voir [Révoquer une identité](/aide/verifications-identite).
- **Réinitialiser le KYC** : pour faire recommencer la vérification. Voir [Réinitialiser le KYC](/aide/verifications-identite).

> **Bon à savoir :** les pièces d'identité sont détenues par le fournisseur et ne sont pas stockées par Yadony. Valider ou refuser reste impossible tant qu'aucune pièce n'a été envoyée.

## Qui peut le faire

Lire l'onglet et réinitialiser : Super admin, Admin et Support. Valider, refuser et révoquer : Super admin et Admin seulement.

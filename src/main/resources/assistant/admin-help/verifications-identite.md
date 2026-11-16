---
id: verifications-identite
title: "Vérifications d'identité (KYC) : valider, refuser, révoquer, réinitialiser"
category: comptes
order: 30
summary: La file des vérifications d'identité. Ouvrez un dossier, contrôlez les pièces chez le fournisseur, puis validez, refusez, révoquez ou réinitialisez.
permission: USER_KYC
keywords: KYC, vérification d'identité, vérifier l'identité, pièce d'identité, selfie, valider l'identité, refuser la vérification, révoquer l'identité, réinitialiser le KYC, refaire la vérification, en attente de décision, à décider, en retard, 48 h, revue manuelle, fournisseur, Didit, Stripe Identity, code de refus, motif, identité validée, compte de démo, valider sans pièce, file KYC
routes:
  - path: /kyc
    label: Ouvrir la file À décider
  - path: /kyc?status=REJECTED
    label: Voir les vérifications refusées
  - path: /kyc?status=VERIFIED
    label: Voir les identités validées
---
## À quoi ça sert

Avant de publier un trajet, de payer ou d'être payé, un utilisateur fait vérifier son identité (pièce d'identité et selfie) chez un fournisseur spécialisé. Le plus souvent, le fournisseur décide seul. Quand il hésite, il demande une **revue manuelle** : le dossier arrive alors dans la page **Vérifications d'identité** (menu **Comptes**), et c'est à l'équipe de trancher.

## Comprendre la page

La page a trois colonnes : la **file** à gauche, le **dossier** au centre, la **décision** à droite.

Les onglets de la file :

- **À décider** (en attente de décision) : les dossiers à traiter. Ceux **en retard (plus de 48 h)** passent en premier.
- **En cours** : l'utilisateur est en train de faire son parcours chez le fournisseur.
- **Refusées**, **Validées**, **Non commencées**.

Vous pouvez aussi chercher un utilisateur (nom, identifiant, téléphone), et dans **Plus de filtres** choisir le fournisseur ou une période de soumission.

Le dossier montre **Ce que dit le fournisseur**, le **Statut chez le fournisseur**, le **Dernier motif signalé** et l'**Historique**.

> **Bon à savoir :** les pièces et le selfie ne sont pas stockés chez Yadony. Cliquez sur **Ouvrir la session chez le fournisseur** pour les consulter.

## Étapes : valider ou refuser

1. Ouvrez [Vérifications d'identité](/kyc), onglet **À décider**.
2. Cliquez sur un dossier dans la file.
3. Cliquez sur **Ouvrir la session chez le fournisseur** et contrôlez les pièces et le selfie.
4. Pour accepter : **Valider l'identité**, cochez « J'ai contrôlé les pièces d'identité et le selfie chez le fournisseur », écrivez ce que vous avez contrôlé, puis confirmez.
5. Pour refuser : **Refuser**, choisissez un **Code de refus** (le message envoyé à l'utilisateur s'affiche dessous), écrivez un motif interne, puis confirmez.

> **Bon à savoir :** valider ou refuser reste verrouillé tant que l'utilisateur n'a envoyé aucune pièce.

Le motif est toujours journalisé avec votre identifiant.

## Révoquer une identité déjà validée

Dans l'onglet **Validées**, ouvrez le dossier puis cliquez sur **Révoquer**. Choisissez un code, écrivez le motif, puis ressaisissez le nom de l'utilisateur pour confirmer.

> **Attention :** geste lourd, à réserver à une fraude ou une erreur avérée. Les nouvelles publications, les paiements et les versements de l'utilisateur sont bloqués jusqu'à une nouvelle vérification validée. Les envois en cours ne sont pas annulés : suivez-les à part.

## Réinitialiser le KYC

**Réinitialiser le KYC** annule la session en cours chez le fournisseur. L'utilisateur devra refaire toute sa vérification. C'est utile quand un parcours est bloqué.

## Qui peut le faire

- Voir la file et réinitialiser : permission USER_KYC (Support compris).
- Valider, refuser, révoquer : permission KYC_DECIDE (Super admin et Admin, jamais le Support).
- **Valider sans pièce (compte de démo)** : Super admin seulement, et uniquement pour le compte de démonstration remis aux équipes de review des stores.

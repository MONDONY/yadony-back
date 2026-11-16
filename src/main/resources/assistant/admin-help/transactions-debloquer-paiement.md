---
id: transactions-debloquer-paiement
title: Débloquer un paiement et payer le voyageur
category: argent
order: 40
summary: Libérer à la main l'argent en séquestre vers le voyageur, avec un motif, ou par dérogation quand le versement est bloqué.
permission: PAYMENT_RELEASE
keywords: débloquer paiement, libérer paiement, force release, forcer le versement, payer le voyageur, verser au voyageur, séquestre bloqué, séquestre plus de 48 h, colis livré mais pas payé, dérogation, payer malgré le blocage, voyageur banni, identité révoquée, litige bancaire, compte stripe inutilisable, transfert déjà tenté, versement manuel
routes:
  - path: /transactions?view=ESCROW
    label: Paiements en séquestre
  - path: /transactions
    label: Paiements à traiter
---
## À quoi ça sert

Quand l'expéditeur paie, l'argent reste en séquestre chez Yadony jusqu'à la livraison. Normalement il part tout seul vers le voyageur. **Débloquer** sert à forcer ce versement à la main, par exemple quand un colis est bien livré mais que le paiement est resté en séquestre (alerte **En séquestre depuis plus de 48 h**).

> **Attention :** débloquer verse l'argent au voyageur. Ce geste ne peut pas être annulé depuis l'admin. Vérifiez d'abord la livraison (photos, statut du colis). En cas de doute sur la livraison, remboursez plutôt l'expéditeur.

## Étapes

### Cas normal

1. Ouvrez [Transactions](/transactions?view=ESCROW), vue **En séquestre**, puis cliquez sur le paiement.
2. Vérifiez l'onglet **Résumé** (colis livré ?) et l'onglet **Références** (**Photos de remise et de livraison**).
3. Cliquez sur **Actions**, puis **Débloquer (force-release)**.
4. Dans la fenêtre **Débloquer le paiement**, écrivez le motif (obligatoire).
5. Cliquez sur **Débloquer**.

### Paiement bloqué : la dérogation

Si le voyageur est banni, si son identité a été révoquée ou si le paiement a un litige bancaire ouvert, le versement est bloqué. Le menu affiche alors **Payer le voyageur (dérogation)** (ou le serveur refuse le déblocage et la dérogation s'ouvre).

1. Cliquez sur **Payer le voyageur (dérogation)**.
2. Lisez le cadre rouge : il dit pourquoi le voyageur est bloqué.
3. Cochez **Je confirme payer ce voyageur malgré le blocage.**
4. Écrivez le **Motif de la dérogation (obligatoire)**. Le nombre de caractères minimum et maximum s'affiche sous le champ. Le motif est enregistré avec votre identifiant.
5. Cliquez sur **Payer malgré le blocage**.

## Comprendre ce que vous voyez

- Le bouton est grisé avec une raison écrite dessous quand le geste est impossible : « Seul un paiement en séquestre peut être débloqué. » ou « Permission manquante ».
- Après un succès, la liste, les compteurs et la fiche se rechargent. Le statut passe à **Libéré** et l'onglet **Chronologie** montre l'étape, par exemple **Versement forcé par un admin** ou **Retenue levée par dérogation admin**.

## Bon à savoir

- Si le message parle d'un compte Stripe du voyageur inutilisable, aucun versement ne peut lui arriver : remboursez l'expéditeur ou demandez au voyageur de régulariser son compte.
- Si le message dit qu'un transfert Stripe a déjà été tenté, ne relancez rien : vérifiez dans Stripe ce qu'il est devenu, sinon le voyageur risque d'être payé deux fois.
- Si l'encaissement de la carte est refusé, rien n'est versé et le paiement reste en séquestre. Essayez [Resynchroniser avec Stripe](/aide/transactions-resynchroniser-stripe), ou remboursez si l'autorisation a expiré.
- Pour un versement retenu, lisez aussi [Traiter un versement retenu](/aide/transactions-versement-retenu).

## Qui peut le faire

Super admin et Admin (permission de débloquer les paiements). Le rôle Support voit le bouton grisé.

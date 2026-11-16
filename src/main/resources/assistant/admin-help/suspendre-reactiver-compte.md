---
id: suspendre-reactiver-compte
title: Suspendre un compte ou le réactiver
category: comptes
order: 40
summary: Bloquer temporairement l'accès d'un utilisateur avec un motif, puis lui rendre l'accès avec Réactiver.
permission: USER_SUSPEND
keywords: suspendre un compte, suspension, bloquer un utilisateur, bloquer l'accès, désactiver un compte, geler un compte, sanction temporaire, réactiver un compte, lever la suspension, débloquer un utilisateur, rendre l'accès, compte suspendu, motif de suspension, fraude suspectée, comportement abusif, actions du compte
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /users?status=SUSPENDED
    label: Voir les comptes suspendus
---
## À quoi ça sert

**Suspendre** bloque l'accès d'un utilisateur à son compte, de façon réversible. C'est la sanction à utiliser quand il faut arrêter une personne le temps de vérifier une situation (fraude suspectée, plainte grave, comportement à éclaircir). **Réactiver** lui rend l'accès.

Si la personne doit être exclue pour de bon, utilisez plutôt [Bannir un compte](/aide/bannir-compte).

## Étapes

### Suspendre

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur **Actions du compte** en haut à droite.
3. Dans la famille **Compte**, cliquez sur **Suspendre** (« Bloque l'accès, réversible »).
4. La fenêtre **Suspendre ce compte** s'ouvre. Écrivez le motif dans le champ **Motif (obligatoire)**.
5. Cliquez sur **Suspendre**.

Le statut du compte passe à **Suspendu** et le bandeau **À surveiller** affiche « Compte suspendu ».

### Réactiver

1. Ouvrez la fiche du compte suspendu. Pour les retrouver tous, utilisez le filtre **Suspendus** de la page [Utilisateurs](/users?status=SUSPENDED).
2. Cliquez sur **Actions du compte**.
3. Cliquez sur **Réactiver** (« Rend l'accès au compte »).

La réactivation est immédiate, sans fenêtre de confirmation. Le statut repasse à **Actif**.

## Comprendre ce que vous voyez

- **Suspendre** n'apparaît que pour un compte **Actif**.
- **Réactiver** n'apparaît que pour un compte **Suspendu**.
- Pour un compte **Banni**, le geste s'appelle **Lever le bannissement**. Voir [Bannir un compte](/aide/bannir-compte).
- Le bouton de confirmation reste grisé tant que le motif est vide.

## Bon à savoir

- Le motif est enregistré dans le journal du compte (onglet **Support et journal**, action **Compte suspendu**). Écrivez-le de façon claire et factuelle pour vos collègues.
- Suspendre le compte entier n'est pas toujours nécessaire. Pour seulement empêcher la publication de trajets et de colis, voir [Suspendre la publication](/aide/suspendre-publication). Pour seulement empêcher d'écrire dans la messagerie, voir [Couper la messagerie](/aide/couper-messagerie).
- Un compte peut aussi être suspendu automatiquement par le système. Le journal l'indique alors avec l'action **Compte suspendu automatiquement**.
- Si une erreur s'affiche en rouge sous l'en-tête, le geste n'a pas été appliqué : lisez le message et réessayez.

## Qui peut le faire

Super admin et Admin. Le Support ne peut ni suspendre ni réactiver un compte.

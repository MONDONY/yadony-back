---
id: activer-double-authentification
title: Activer la double authentification
category: premiers-pas
order: 2
summary: Relier votre compte à une appli d'authentification avec un QR code, obligatoire pour accéder au back-office.
keywords: double authentification, 2FA, MFA, authentification à deux facteurs, QR code, scanner, clé secrète, Google Authenticator, Microsoft Authenticator, 1Password, code à 6 chiffres, activer, vérifier adresse email, lien de vérification, connexion trop ancienne, se reconnecter, nouveau téléphone, sécurité du compte
routes:
  - path: /mfa-setup
    label: Ouvrir la double authentification
---
## À quoi ça sert

La double authentification est **obligatoire** pour le back-office : à chaque connexion, en plus du mot de passe, vous tapez un code à 6 chiffres affiché par une appli de votre téléphone. Tant qu'elle n'est pas activée, la console vous ramène sur l'écran **Double authentification** et vous ne pouvez pas ouvrir les autres pages.

## Avant de commencer

Installez sur votre téléphone une appli d'authentification, par exemple Google Authenticator, Microsoft Authenticator ou 1Password.

## Étapes

1. Connectez-vous avec votre email et votre mot de passe. L'écran **Double authentification** s'ouvre de lui-même. Patientez pendant **Préparation…**.
2. Ouvrez votre appli d'authentification et ajoutez un compte.
3. Scannez le QR code affiché. Si le scan ne fonctionne pas, tapez à la main la clé affichée sous le QR code (elle est découpée en groupes de 4 caractères).
4. L'appli affiche un compte nommé Yadony Admin avec un code à 6 chiffres. Recopiez ce code dans le champ **Code**.
5. Cliquez sur **Activer**.
6. La console vous déconnecte et vous ramène sur **Connexion** avec le message **Double authentification activée**. Reconnectez-vous avec votre mot de passe, puis avec le code de l'appli.

## Si l'activation est bloquée

L'écran peut afficher un message à la place du QR code :

- **Tu dois d'abord vérifier ton adresse e-mail.** Cliquez sur **Envoyer le lien**, ouvrez l'email reçu et cliquez sur le lien, puis rechargez la page. Le message **E-mail envoyé.** confirme l'envoi.
- **Ta connexion est trop ancienne pour activer la double authentification.** Cliquez sur **Se reconnecter**, puis recommencez juste après la connexion.
- **La double authentification n'est pas disponible sur cet environnement.** Cliquez sur **Continuer** pour accéder à la console sans elle. Ce cas ne concerne que des environnements de test.
- **Impossible de préparer l'activation.** Cliquez sur **Réessayer**.

En bas de l'écran, **Se déconnecter** permet de quitter sans activer.

## Messages d'erreur au moment d'activer

- **Code incorrect. Vérifie l'heure de ton téléphone et réessaie.** : le code ne correspond pas. Vérifiez que l'heure du téléphone est réglée en automatique et tapez le code en cours (il change régulièrement).
- **L'activation a échoué. Réessaie.** : recommencez.

## Bon à savoir

> **Bon à savoir :** le code se tape avec ou sans espace au milieu, par exemple « 123 456 » ou « 123456 ». Le bouton **Activer** reste grisé tant que les 6 chiffres ne sont pas saisis.

> **Attention :** si vous changez de téléphone ou perdez celui qui porte l'appli, vous ne pourrez plus produire de code. La console ne propose pas de bouton pour retirer la double authentification : prévenez votre responsable avant de changer d'appareil.

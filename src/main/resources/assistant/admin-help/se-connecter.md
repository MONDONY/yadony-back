---
id: se-connecter
title: Se connecter à la console Yadony ADMIN
category: premiers-pas
order: 1
summary: Ouvrir une session avec votre email, votre mot de passe puis le code à 6 chiffres de votre appli d'authentification.
keywords: connexion, se connecter, login, identifiants, email, mot de passe, code à 6 chiffres, code de vérification, appli d'authentification, identifiants incorrects, trop de tentatives, code incorrect, code expiré, première connexion, se déconnecter, déconnexion, ouvrir une session, accès back-office, impossible de se connecter
routes:
  - path: /change-password
    label: Changer mon mot de passe
---
## À quoi ça sert

La page **Connexion** ouvre votre session dans la console Yadony ADMIN. Elle se fait en deux temps : d'abord votre email et votre mot de passe, puis, si la double authentification est activée sur votre compte, un code à 6 chiffres affiché par votre appli d'authentification.

## Étapes

1. Ouvrez la page **Connexion**. En haut à droite, un bouton permet de passer en mode clair ou sombre.
2. Saisissez votre adresse dans le champ **Email**.
3. Saisissez votre mot de passe dans le champ **Mot de passe**. L'icône en forme d'œil permet de l'afficher ou de le masquer pour vérifier la saisie.
4. Cliquez sur **Se connecter**. Le bouton reste grisé tant que l'un des deux champs est vide.
5. Si l'écran **Code de vérification** apparaît, ouvrez votre appli d'authentification (Google Authenticator, Microsoft Authenticator, 1Password…) et recopiez le code à 6 chiffres affiché pour Yadony Admin dans le champ **Code**.
6. Cliquez sur **Valider le code**. Si vous vous êtes trompé de compte, cliquez sur **Retour au mot de passe**.

Selon votre situation, la console vous emmène ensuite :

- sur l'écran **Changer le mot de passe** si c'est votre première connexion avec un mot de passe temporaire (voir [Changer son mot de passe](/aide/changer-mot-de-passe)) ;
- sur l'écran **Double authentification** si vous ne l'avez pas encore activée (voir [Activer la double authentification](/aide/activer-double-authentification)) ;
- sinon sur la page d'accueil **Vue d'ensemble**.

## Comprendre les messages d'erreur

- **Identifiants incorrects** : l'email ou le mot de passe ne correspond pas. Vérifiez les majuscules et l'adresse.
- **Trop de tentatives. Réessaie dans quelques minutes.** : trop d'essais ratés à la suite. Attendez un peu avant de recommencer.
- **Code incorrect. Vérifie l'heure de ton téléphone et réessaie.** : le code tapé n'est pas le bon. Un téléphone dont l'heure est décalée produit des codes faux : réglez l'heure en automatique.
- **Le code a expiré. Reconnecte-toi.** : la console vous ramène à l'étape du mot de passe. Recommencez depuis le début.
- **Erreur de connexion. Réessaie.** : problème passager. Réessayez.

Après une activation de la double authentification, un bandeau vert indique **Double authentification activée. Reconnecte-toi avec ton mot de passe, puis ton code.**

## Se déconnecter

Cliquez sur l'icône de sortie tout à droite de la barre du haut (bouton **Se déconnecter**). Vous revenez sur la page **Connexion**.

## Bon à savoir

> **Bon à savoir :** l'email est accepté quelle que soit la casse : « Admin@… » et « admin@… » mènent au même compte.

> **Bon à savoir :** si la page **Accès refusé** s'affiche après la connexion, votre compte n'est pas un compte administrateur ou n'a pas le droit d'ouvrir cette page. Voir [Page Accès refusé](/aide/acces-refuse).

> **Attention :** ne partagez jamais votre mot de passe ni vos codes. Si vous avez perdu votre mot de passe, demandez à un Super admin de le réinitialiser depuis la page Administrateurs.

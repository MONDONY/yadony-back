---
id: acces-refuse
title: Comprendre la page « Accès refusé »
category: premiers-pas
order: 4
summary: Pourquoi la console affiche Accès refusé et que faire quand une page ou un menu vous manque.
keywords: accès refusé, accès interdit, pas le droit, permission manquante, page bloquée, refusé, denied, réservé aux administrateurs, retour connexion, rôle insuffisant, menu absent, page invisible, je ne vois pas, droits, autorisation, compte non administrateur, support, bloqué
routes:
  - path: /
    label: Revenir à l'accueil
---
## À quoi ça sert

La page **Accès refusé** s'affiche quand vous essayez d'ouvrir une page de la console que votre compte n'a pas le droit de voir. Elle indique : **Ce back-office est réservé aux administrateurs Yadony.** et propose le lien **Retour connexion**.

## Pourquoi elle apparaît

Deux situations mènent à cette page :

- **Votre compte n'est pas un compte administrateur.** Seuls les rôles Super admin, Admin et Support peuvent entrer dans la console. Un compte utilisateur de l'application Yadony ne suffit pas.
- **Votre rôle n'a pas la permission de cette page.** Chaque page demande une permission précise. Par exemple, la page Administrateurs n'est ouverte qu'au Super admin, et un compte Support n'ouvre pas les Paramètres. Cela arrive surtout quand vous ouvrez un lien partagé par un collègue qui a plus de droits que vous, ou un ancien favori.

## Que faire

1. Si vous avez suivi un lien, revenez à la page précédente avec le bouton retour de votre navigateur, ou cliquez sur le logo Yadony en haut du menu de gauche pour retourner à l'accueil.
2. Vérifiez votre rôle : cliquez sur le rond à vos initiales en bas du menu de gauche. Le menu **Mon compte** affiche votre email et votre rôle (SUPER_ADMIN, ADMIN ou SUPPORT).
3. Si vous pensez avoir besoin de cette page pour votre travail, demandez à un Super admin de vérifier votre rôle dans la page Administrateurs.
4. Si vous n'arrivez plus à rien ouvrir, cliquez sur **Retour connexion** et reconnectez-vous avec votre compte administrateur.

## Comprendre ce que vous voyez dans le menu

Le menu de gauche ne montre que les pages autorisées pour votre rôle. Un domaine entier (par exemple **Système**) disparaît si vous n'avez accès à aucune de ses pages. La recherche de la barre du haut et les raccourcis clavier suivent la même règle : une page interdite n'y apparaît pas. Vous ne tombez donc sur **Accès refusé** qu'en ouvrant une adresse directement.

## Bon à savoir

> **Bon à savoir :** dans une page autorisée, certains boutons peuvent aussi manquer. Par exemple, un compte Support voit les paiements mais pas les boutons qui déplacent de l'argent. C'est normal : chaque action a sa propre permission. Voir [Rôles et permissions](/aide/roles-et-permissions).

## Qui peut le faire

Seul un Super admin peut changer le rôle d'un administrateur, depuis la page Administrateurs.

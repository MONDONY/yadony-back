---
id: application-mobile
title: L'application mobile de la console (section /m)
category: premiers-pas
order: 9
summary: Installer la console sur le téléphone, les 5 onglets et la permission de chacun, ce qui se fait ou non depuis le mobile, le verrouillage et les notifications push.
keywords: application mobile, mobile, téléphone, iPhone, Android, PWA, installer, écran d'accueil, /m, notifications push, push, verrouillage, inactivité, 30 minutes, onglets, Alertes, Ensemble, Support, Messages, Diffuser, J'ai vu je m'en occupe, lecture seule, Prendre en charge, Libérer, Autres nouveautés, Ensuite sur l'ordinateur, Colis en cours, Identités en cours, Inscrits, ALERT_VIEW, METRICS_VIEW, SUPPORT_TICKET_VIEW, MODERATION_VIEW, NOTIFICATION_SEND
routes:
  - path: /m
    label: Ouvrir la version mobile
---
## À quoi ça sert

La section **/m** de la console est une version pensée pour le téléphone. Elle sert à suivre l'essentiel quand vous n'êtes pas devant un ordinateur : voir les alertes, répondre au support, prévenir une personne. Les actions lourdes ou risquées restent réservées à la console complète.

## L'installer et s'y connecter

1. Ouvrez l'adresse de la console dans le navigateur du téléphone, puis ajoutez la section mobile à l'écran d'accueil (menu de partage sur iPhone, menu du navigateur sur Android).
2. Sur iPhone, cette installation est obligatoire pour recevoir les notifications : sans elle, le téléphone n'en affiche pas.
3. Connectez-vous avec les mêmes identifiants que sur ordinateur : l'adresse e-mail et le mot de passe, puis le code à 6 chiffres de la double authentification.

## Les 5 onglets et la permission qui les affiche

Chaque onglet n'apparaît que si votre compte a la permission correspondante, comme sur ordinateur.

| Onglet | Permission nécessaire |
|---|---|
| **Alertes** | ALERT_VIEW |
| **Ensemble** | METRICS_VIEW |
| **Support** | SUPPORT_TICKET_VIEW |
| **Messages** | MODERATION_VIEW |
| **Diffuser** | NOTIFICATION_SEND |

## Alertes

- La liste est triée par gravité. Trois filtres : **À traiter**, **En attente** (alertes reportées) et **Traitées**.
- La section **Autres nouveautés** regroupe les autres nouveautés de la cloche qui ne sont pas des alertes.
- La page d'une alerte montre les faits, une explication, puis un bloc **Ensuite, sur l'ordinateur** qui dit quoi faire sur la console complète.
- Le bouton **J'ai vu, je m'en occupe** demande la permission ALERT_RESOLVE. Il signale à l'équipe que l'alerte est prise en charge : le nom de l'administrateur qui l'a prise s'affiche pour les autres.
- **Libérer** rend l'alerte aux autres. Seule la personne qui l'a prise, ou un administrateur qui gère les administrateurs, peut le faire.

## Ensemble

Choisissez la période avec les filtres **Aujourd'hui**, **7 jours** ou **30 jours**. Ce que comptent les tuiles :

- **Colis en cours** : les offres acceptées plus les colis en transit.
- **Inscrits** : inclut les comptes supprimés depuis.
- **Identités en cours** : le nombre actuel de vérifications d'identité en cours, sans limite à la période choisie.

## Support

- Vous voyez la liste des tickets, puis le fil d'un ticket.
- Pour répondre à un ticket non assigné, touchez d'abord **Prendre en charge**.
- Un ticket pris par un collègue reste en lecture seule.
- Résoudre, réassigner et joindre des fichiers ne se font pas depuis le téléphone.

## Messages

- Ce sont les conversations entre utilisateurs, celles qui ont été signalées en premier.
- Tout est en lecture seule : les messages supprimés ne s'affichent jamais, et les photos s'ouvrent dans la page.
- La suppression de messages et les sanctions se font uniquement sur l'ordinateur.

## Diffuser

- Une notification part vers une seule personne. Cherchez-la, puis une confirmation affiche son nom, son e-mail masqué et la fin de son identifiant.
- Le statut du compte est vérifié avant l'envoi : un compte banni, ou avec une demande de suppression, ne peut pas être notifié.
- Si l'envoi échoue sans qu'on sache s'il est parti, une étape dédiée apparaît et impose une attente de 10 secondes avant de renvoyer, pour éviter un doublon.

## Ce qui reste sur la console complète

Pas possible depuis le téléphone : supprimer, bannir, débloquer ou libérer un paiement, restaurer, résoudre un ticket. Ces actions se font sur l'ordinateur.

## Verrouillage après 30 minutes

Après 30 minutes sans utilisation, la session mobile se ferme d'elle-même : vous êtes déconnecté. Il suffit de vous reconnecter avec votre mot de passe puis votre code. C'est une protection au cas où le téléphone serait perdu ou prêté.

## Les notifications push

- Le titre est générique, par exemple « Nouvelle alerte », et ne contient aucune donnée personnelle. Le détail s'affiche une fois l'application ouverte.
- Vous ne recevez que les types de notifications que vos permissions autorisent.
- Vous les activez depuis l'application, sur chaque téléphone. Sur iPhone, il faut d'abord ajouter l'application à l'écran d'accueil.
- Toucher une notification ouvre l'élément : l'alerte, le ticket du support, ou la liste des alertes pour les autres types.
- Seules les nouveautés de moins d'un quart d'heure sont envoyées : un téléphone resté éteint ne reçoit pas tout l'historique d'un coup. La cloche, elle, garde tout.
- Si un téléphone refuse les notifications pendant une dizaine de minutes, il est désabonné : réactivez-les depuis l'application mobile.

> **Bon à savoir :** pour les notifications de la cloche sur ordinateur, voir [Cloche de notifications et pastilles](/aide/notifications-et-pastilles).

---
id: roles-et-permissions
title: Comprendre les rôles et les permissions (Super admin, Admin, Support)
category: premiers-pas
order: 12
summary: Ce que chaque rôle peut voir et faire dans la console, et quelle permission ouvre chaque page.
keywords: rôle, rôles, permissions, droits, Super admin, SUPER_ADMIN, Admin, ADMIN, Support, SUPPORT, que puis-je faire, accès, autorisations, lecture seule, bouton absent, page absente, ADMIN_MANAGE, CONFIG_MANAGE, quel rôle, niveau d'accès, équipe support, opérations
routes:
  - path: /administrateurs
    label: Voir les rôles des administrateurs
---
## À quoi ça sert

Chaque compte de la console a un rôle. Le rôle donne un ensemble de permissions, et chaque page ou bouton sensible demande une permission précise. Votre rôle s'affiche dans le menu **Mon compte** (rond à vos initiales en bas du menu de gauche).

## Les trois rôles

- **Super admin** (SUPER_ADMIN) : a toutes les permissions. C'est le seul rôle qui gère les comptes administrateurs (page **Administrateurs**). Ce compte ne peut être ni modifié ni supprimé depuis la console.
- **Admin** (ADMIN) : a toutes les permissions sauf la gestion des administrateurs.
- **Support** (SUPPORT) : rôle de lecture avec quelques actions de suivi. Il ne déplace pas d'argent, ne suspend pas, ne bannit pas et ne change aucun réglage.

## Ce que fait un compte Support

Un compte Support peut :

- voir la **Vue d'ensemble** ;
- consulter les **Utilisateurs** et la file des **Vérifications d'identité** (consulter, réinitialiser une vérification, sans valider ni refuser une identité) ;
- consulter les **Transactions**, les **Colis**, les **Incidents** ;
- voir et résoudre les **Alertes** ;
- voir la **Modération** et supprimer un message ;
- voir et traiter les **Signalements**, et modérer les avis ;
- voir et traiter les tickets du **Support** (répondre, assigner, résoudre).

Il ne voit pas, entre autres : GP Pro, Demandes RGPD, Codes promo, Codes de recharge, Corrections à approuver, Tarification, Déclaration DAC7, Assistant IA, Communications, Modals, Audit, Exports, Paramètres, Administrateurs.

## Quelle permission ouvre quelle page

- **Vue d'ensemble** : METRICS_VIEW
- **Utilisateurs** : USER_VIEW ; **Vérifications d'identité** : USER_KYC ; **GP Pro** : GP_PRO_REVIEW ; **Demandes RGPD** : USER_GDPR_DELETE
- **Transactions** : PAYMENT_VIEW ; **Codes promo** : PROMO_MANAGE ; **Codes de recharge** : TOPUP_CODE_MANAGE ; **Corrections à approuver** : WALLET_ADJUST ; **Tarification** : CONFIG_MANAGE ; **Déclaration DAC7** : DAC7_REPORT
- **Colis** : BID_VIEW ; **Incidents** : DISPUTE_VIEW ; **Alertes** : ALERT_VIEW ; **Signalements** : REPORT_VIEW
- **Support** : SUPPORT_TICKET_VIEW ; **Modération** : MODERATION_VIEW ; **Assistant IA** : ASSISTANT_MANAGE ; **Communications** et **Modals** : NOTIFICATION_SEND
- **Audit** : AUDIT_VIEW ; **Exports** : EXPORT_RUN ; **Paramètres** : CONFIG_MANAGE ; **Administrateurs** : ADMIN_MANAGE

À l'intérieur d'une page, les gestes sensibles ont leur propre permission : par exemple libérer ou rembourser un paiement, suspendre ou bannir un compte, valider une identité, corriger un solde.

## Comment la console applique les rôles

- Le menu, la recherche et les raccourcis ne montrent que les pages autorisées.
- Un bouton non autorisé n'apparaît pas, ou reste grisé.
- Ouvrir directement l'adresse d'une page interdite mène à **Accès refusé** (voir [Page Accès refusé](/aide/acces-refuse)).

## Bon à savoir

> **Bon à savoir :** un compte peut exceptionnellement avoir une permission ajoutée ou retirée à titre individuel. La console n'a pas d'écran pour régler ces exceptions.

> **Bon à savoir :** un changement de rôle s'applique immédiatement au compte concerné. Voir [Changer le rôle d'un administrateur](/aide/administrateurs).

## Qui peut le faire

Seul le Super admin attribue les rôles, depuis la page **Administrateurs** (permission ADMIN_MANAGE).

---
id: glossaire
title: Glossaire des mots du métier Yadony
category: premiers-pas
order: 13
summary: "Les mots utilisés dans la console expliqués simplement : colis, trajet, demande d'envoi, KYC, litige, no-show, séquestre, solde, GP Pro, DAC7…"
keywords: glossaire, définition, vocabulaire, lexique, que veut dire, colis, bid, annonce, trajet, demande d'envoi, voyageur, expéditeur, destinataire, KYC, litige, no-show, séquestre, escrow, versement retenu, solde, wallet, portefeuille, mobile money, GP Pro, DAC7, RGPD, commission
routes:
  - path: /
    label: Ouvrir la console
---
## Les personnes

- **Voyageur** : personne qui part en voyage et transporte des colis dans ses bagages contre rémunération.
- **Expéditeur** : personne qui confie un colis à un voyageur et paie le transport.
- **Destinataire** : personne qui reçoit le colis à l'arrivée.
- **Particulier** : statut déclaré par un voyageur qui ne transporte pas à titre professionnel. Un particulier qui publie beaucoup de trajets dans le mois est signalé dans **Particuliers à confirmer** (page GP Pro), sans blocage.

## Les trajets et les colis

- **Annonce** ou **trajet** : voyage publié par un voyageur (ville de départ, ville d'arrivée, date, moyen de transport : avion, voiture, bus, bateau, train ou autre). Statuts : Brouillon, Publiée, Complète, Voyage en cours, Terminée, Annulée, Retirée par un admin.
- **Demande d'envoi** : besoin publié par un expéditeur pour trouver un voyageur. Les voyageurs y répondent et le prix peut se négocier. Statuts : Brouillon, Ouverte, En négociation, Acceptée, Expirée, Annulée, Terminée, Retirée.
- **Colis** (nom interne « bid ») : un envoi précis entre un expéditeur et un voyageur, avec un numéro de suivi qui commence par DON-. Statuts : En attente, Attente paiement, Payé (séquestre), Accepté, Remis, En transit, Arrivé, Livré, Refusé, Annulé, Absence (no-show), Colis refusé, Expiré.
- **Remise** : le moment où l'expéditeur donne le colis au voyageur, avant le départ.
- **Code de remise** : code qui confirme la livraison au destinataire.

## Les incidents

- **Litige** : désaccord entre les parties sur un colis, qu'un administrateur tranche dans **Incidents**.
- **No-show** (absence au rendez-vous) : une partie déclare l'autre absente, soit à la remise (**Départ**), soit à la livraison (**Arrivée**). Statuts : En attente, Contesté, Absence confirmée, Rejetée, Résolu.
- **Alerte** : anomalie détectée par la plateforme, à traiter dans **Alertes**.
- **Signalement** : contenu ou comportement signalé par un utilisateur, à traiter dans **Signalements**.

## L'argent

- **Séquestre** (en anglais « escrow ») : l'argent payé par l'expéditeur est gardé par la plateforme jusqu'à la livraison. Statuts d'un paiement : En attente, Sous séquestre, Libéré, Échoué, Remboursé, Annulé.
- **Libéré** : l'argent a été versé au voyageur.
- **Escrow J+48** : paiement resté en séquestre depuis plus de 48 heures, à vérifier.
- **Versement retenu** (ou paiement bloqué) : versement d'un voyageur bloqué chez Yadony, par exemple parce qu'il est banni ou que son identité a été révoquée. Il ne repart pas tout seul : un administrateur doit le débloquer depuis **Transactions**.
- **Litige bancaire** (chargeback) : contestation d'un paiement par carte auprès de la banque.
- **Commission** : part gagnée par Yadony sur un transport. Elle se règle dans **Tarification**.
- **Solde**, **portefeuille** ou **wallet** : argent disponible sur le compte d'un utilisateur dans l'application. Une **correction de solde** est un crédit ou un débit motivé fait par un administrateur. Un **remboursement wallet** rend le solde à l'utilisateur, par exemple à la suppression de son compte.
- **Code de recharge** : code qui crédite le solde, réservé au paiement des commissions.
- **Mobile money** : paiement par téléphone mobile, comme Wave ou Orange Money.
- **Espèces à la remise** (cash) : l'expéditeur paie le voyageur en liquide ; la commission est alors suivie à part.

## Les comptes et la conformité

- **KYC** : vérification d'identité d'un utilisateur, suivie dans **Vérifications d'identité**.
- **Suspendu**, **Banni**, **Suppression demandée** : états d'un compte utilisateur.
- **RGPD** : demandes de suppression de compte, dans **Demandes RGPD**.
- **GP Pro** : badge de confiance accordé à des voyageurs réguliers et montré aux expéditeurs.
- **Offre PRO** (comptes PRO) : abonnement de l'application qui lève certains quotas. À ne pas confondre avec le badge GP Pro.
- **DAC7** : déclaration fiscale annuelle, à l'administration fiscale française, des revenus des voyageurs qui habitent dans l'Union européenne. Elle demande leur numéro fiscal.
- **Mode maintenance** : coupure volontaire de l'application pendant une intervention.

> **Bon à savoir :** pour retrouver un élément à partir de ces mots, utilisez la [recherche globale](/aide/recherche-globale).

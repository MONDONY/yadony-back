---
id: parametres
title: "Paramètres plateforme : tous les réglages de l'application"
category: systeme
order: 30
summary: "La page Paramètres règle la marketplace : maintenance, mise à jour de l'app, SMS, commission, seuils, offre PRO, DAC7, photos, WhatsApp du support, taux de change."
permission: CONFIG_MANAGE
keywords: paramètres, assistant de la console, couper l'assistant, quota de l'assistant de la console, réglages, configuration, paramètres plateforme, SMS, activer les SMS, pays sans SMS, plafond de SMS, taux de commission, seuil d'urgence, plafond de remboursement, offre PRO, collecte DAC7, messages DAC7, billets d'avion, suppression des photos, purge des photos, numéro WhatsApp du support, questions à l'assistant, quota assistant, trajets par mois avant alerte, taux de change, BCE, build recommandé, mise à jour de l'app
routes:
  - path: /parametres
    label: Ouvrir les paramètres
---
## À quoi ça sert

La page **Paramètres plateforme** (menu **Système**) regroupe les réglages globaux de l'application mobile. Chaque changement s'applique à tous les utilisateurs : agissez avec prudence.

## Les blocs de la page

- **Maintenance** : fermer l'application pour tout le monde pendant une intervention. Voir [Mettre l'application en maintenance](/aide/mettre-en-maintenance).
- **Mise à jour de l'app** : le **Build recommandé actuel**. Les utilisateurs dont l'application est plus ancienne voient un bandeau les invitant à mettre à jour. Le **Build minimal supporté** est affiché en lecture seule.
- **Réglages** : une liste de réglages, chacun avec son bouton **Enregistrer**, la date de dernière modification ou **Jamais modifié**.
- **Stockage photos** : la purge des photos de suivi.
- **Taux de change** : les taux utilisés pour convertir les devises.

## Les principaux réglages

- **SMS activés (authentification par code)** : couper les SMS coupe aussi la connexion par code SMS.
- **Code SMS réservé aux numéros déjà inscrits**, **Pays sans SMS (indicatifs bloqués)**, plafonds de SMS par heure et par 24 h, **Numéros différents avant suspension ou alerte**, **SMS de repli**.
- **Taux de commission** : le taux unique, utilisé tant qu'aucune grille n'est en vigueur. Une grille par paliers se règle dans l'[onglet Tarification](/aide/tarification).
- **Seuil d'urgence** et **Plafond de remboursement**.
- **Offre PRO ouverte**, **Billets d'avion** (onglet Billets dans l'application).
- **Collecte DAC7 ouverte** et **Messages DAC7**. Voir [Déclaration DAC7](/aide/declaration-dac7).
- **Trajets publiés par mois avant alerte (voyageur Particulier)**. Voir [Particuliers à confirmer](/aide/particuliers-a-confirmer).
- **Questions à l'assistant d'aide par utilisateur et par jour** et **Tours de remplissage par l'IA** (assistant de l'application mobile).
- **Assistant de la console** (allume ou coupe la bulle « Assistant » de cette console) et **Questions à l'assistant de la console par administrateur et par jour**. Voir [Utiliser le centre d'aide](/aide/centre-aide).
- **Numéro WhatsApp de l'équipe** (écran Support de l'application).
- Photos : **Photos de suivi par colis**, **Photos envoyées par utilisateur et par jour**, **Suppression des photos activée** et **Délai de suppression des photos après livraison**.

## Étapes : changer un réglage

1. Ouvrez [Paramètres](/parametres).
2. Trouvez le réglage, changez la valeur ou l'interrupteur (**Activé** ou **Désactivé**).
3. Cliquez sur **Enregistrer**.
4. Lisez la fenêtre de confirmation, qui explique la conséquence, puis confirmez. Pour les réglages sensibles, il faut recopier une phrase.

> **Attention :** désactiver les SMS empêche les utilisateurs de se connecter par code SMS. Bloquer un pays coupe aussi ses notifications SMS.

## Stockage photos

Tant que la suppression n'est pas activée, la purge tourne **À blanc** : elle compte sans rien supprimer. **Lancer la purge maintenant** et **Lancer le contrôle complet** déclenchent un passage tout de suite. L'historique des **Purges** et **Rapprochements** se déplie avec **Afficher l'historique**.

> **Attention :** les photos supprimées ne peuvent pas être récupérées.

## Taux de change

Chaque devise affiche un nombre d'**unités pour 1 €**. **Synchroniser avec la BCE** reprend les taux officiels. Vous pouvez aussi changer un taux à la main puis **Enregistrer** ; un écart important demande une confirmation.

## Qui peut le faire

Il faut la permission CONFIG_MANAGE (Super admin et Admin, jamais le Support).

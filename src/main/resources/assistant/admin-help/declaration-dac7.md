---
id: declaration-dac7
title: "Déclaration DAC7 : préparer, vérifier et exporter la déclaration annuelle"
category: argent
order: 140
summary: "Chaque année, Yadony déclare aux impôts les revenus des voyageurs. La page DAC7 aide à compléter les dossiers, à exporter le fichier et à envoyer les récapitulatifs."
permission: DAC7_REPORT
keywords: DAC7, déclaration DAC7, déclaration fiscale, impôts, DGFiP, Télé-TD, impots.gouv, numéro fiscal, NIF, revenus des voyageurs, voyageurs déclarables, anomalies, remboursements à réconcilier, registre des diligences, relancer un voyageur, export partiel, fichier pour la DGFiP, CSV de contrôle, récapitulatifs, déclaration annuelle, 31 janvier
routes:
  - path: /dac7
    label: Ouvrir la déclaration DAC7
---
## À quoi ça sert

**DAC7** est une obligation européenne : chaque année, les plateformes comme Yadony déclarent aux impôts les sommes gagnées par leurs vendeurs (ici, les voyageurs). La déclaration de l'année écoulée est due fin janvier. La page **Déclaration DAC7** (menu **Argent**) prépare cette déclaration.

En haut de la page, choisissez l'**année**. Une synthèse résume la situation, puis quatre onglets.

## Les onglets

- **Voyageurs** : tous les voyageurs concernés. Filtres : **Voyageurs déclarables**, **Numéro fiscal manquant**, **Identité à revoir**, **Résidence inconnue**, **Non déclarables**, etc. Cliquez sur un voyageur pour ouvrir son dossier : identité, résidence, numéro fiscal, opérations de l'année et **Registre des diligences**.
- **Remboursements à réconcilier** : les remboursements qui modifient les montants déjà comptés.
- **Anomalies** : les points à corriger avant l'export.
- **Fichier et récapitulatifs** : l'export et l'envoi des récapitulatifs.

## Étapes : compléter les dossiers

1. Onglet **Voyageurs**, filtre **Numéro fiscal manquant**.
2. Ouvrez un voyageur. Cliquez sur **Relancer** pour lui envoyer une notification lui rappelant de renseigner son numéro fiscal. Un seul rappel manuel est permis par semaine.
3. Quand l'équipe prend une décision sur un dossier, écrivez-la dans **Décision de l'équipe**, puis **Enregistrer la décision**. La note est inscrite au registre avec votre nom et ne peut plus être modifiée.

## Étapes : exporter le fichier

1. Onglet **Fichier et récapitulatifs**. Si **L'export complet est bloqué**, la liste des points à régler s'affiche avec les voyageurs concernés.
2. Réglez ces points, ou choisissez le mode **Partiel** : seuls les voyageurs complets sont alors déclarés.
3. Laissez le **Numéro d'ordre de la déclaration** à 0, sauf pour un nouveau dépôt de la même année.
4. Cliquez sur **Exporter le fichier**. Le **Fichier pour la DGFiP** se télécharge. Un **CSV de contrôle**, sans donnée sensible, permet de relire les montants.
5. Déposez vous-même le fichier sur impots.gouv.fr (Télé-TD). Yadony ne l'envoie jamais.
6. Revenez cliquer sur **J'ai déposé ce fichier sur Télé-TD**, puis confirmez avec **Marquer comme déposé**. Ces voyageurs seront exclus des prochains exports.

> **Attention :** le fichier contient des données personnelles en clair. Supprimez-le de votre ordinateur après le dépôt. Ne déposez jamais un fichier marqué « non chiffré ».

## Envoyer les récapitulatifs aux voyageurs

Chaque voyageur déclaré doit recevoir un récapitulatif de ses montants. Cliquez sur **Envoyer les récapitulatifs aux voyageurs**, puis saisissez « ENVOYER » pour confirmer.

> **Attention :** cet envoi part à chaque voyageur déclaré qui ne l'a pas déjà reçu et ne peut pas être rappelé.

Les réglages de collecte DAC7 (collecte et messages) se trouvent dans les [Paramètres](/aide/parametres).

## Qui peut le faire

Il faut la permission DAC7_REPORT (Super admin et Admin, jamais le Support : l'export contient les numéros fiscaux).

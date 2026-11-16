---
id: modals
title: "Modals : créer une fenêtre vidéo ou d'information dans l'application"
category: support
order: 70
summary: "La page Modals crée les fenêtres qui s'ouvrent dans l'application (vidéo YouTube ou information) : moment d'affichage, public ciblé, fréquence et résultats."
permission: NOTIFICATION_SEND
keywords: modals, modal, fenêtre, popup, pop-up, vidéo, tutoriel, vidéo YouTube, information, annonce dans l'app, campagne, déclencheur, moment d'affichage, ouverture de l'app, ciblage, pour qui, fréquence, une seule fois, à chaque ouverture, tous les N jours, ne plus afficher, nouvelle version, priorité, résultats, statistiques, activer un modal
routes:
  - path: /modals-video
    label: Ouvrir la liste des modals
  - path: /modals-video/nouveau
    label: Créer un modal
---
## À quoi ça sert

Un **modal** est une fenêtre qui s'ouvre par-dessus l'application mobile, à un moment choisi. La page **Modals** (menu **Support et communauté**) permet d'en créer deux types :

- **Vidéo** : une vidéo YouTube (tutoriel, présentation d'une fonctionnalité) ;
- **Information** : un titre, un texte, une image ou des points forts, et des boutons.

## Comprendre la liste

Colonnes : **Nom**, **Type**, **Format**, **Déclencheurs**, **Fréquence**, **Priorité**, **Résultats**, **Actif** et **Actions** (**Modifier**, **Nouvelle version**, **Supprimer**). Les résultats indiquent combien de personnes ont vu le modal, ouvert la vidéo ou touché le bouton, l'ont ignoré ou ont choisi « ne plus afficher ».

## Étapes : créer un modal

1. Ouvrez [Modals](/modals-video) et cliquez sur **Nouveau modal**, ou ouvrez directement [la création](/modals-video/nouveau).
2. Choisissez le type : **Vidéo** ou **Information**. Il ne pourra plus être changé ensuite.
3. Remplissez le contenu : le lien YouTube (vidéo publique ou non répertoriée) ou le titre, le texte et l'image. Les textes existent en français et en anglais ; un texte anglais vide est remplacé par le français.
4. **Quand s'ouvre-t-il ?** : choisissez un ou plusieurs déclencheurs (ouverture de l'app, arrivée sur un écran, après une action réussie comme un trajet publié ou un paiement, recherche avec peu de résultats...) et un éventuel **Délai d'ouverture**.
5. **Pour qui ?** : tout le monde ou **Seulement les personnes connectées**, avec des conditions sur le compte, la **Version de l'application**, le système, des trajets (corridors) ou un **Tirage au sort** sur un pourcentage des personnes.
6. **À quelle fréquence ?** : **Une seule fois**, **À chaque ouverture de l'app**, **Au plus une fois tous les ... jours**, **Jusqu'à ... fois**, ou **À chaque fois que l'action se produit** (réservé aux moments d'action).
7. Réglez la **Priorité** (plus le chiffre est haut, plus la campagne passe en premier), les dates de début et de fin, et la case **Ne plus afficher**.
8. Créez le modal, puis activez-le avec l'interrupteur **Actif**.

> **Bon à savoir :** un seul modal s'affiche à chaque ouverture de l'application. Pour une promotion, des dates de début et de fin sont conseillées.

## Modifier ou remontrer un modal

- **Enregistrer** corrige le modal sans le remontrer aux personnes qui l'ont déjà vu.
- **Nouvelle version** le remontre au public que vous choisissez (par exemple après un changement de vidéo).

> **Attention :** un modal actif s'affiche immédiatement chez les utilisateurs ciblés. Vérifiez l'aperçu avant de l'activer.

## Qui peut le faire

Il faut la permission NOTIFICATION_SEND (Super admin et Admin).

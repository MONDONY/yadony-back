# Messagerie support Yadony

Date: 2026-09-05

## Objectif

Ajouter un canal de support asynchrone entre les utilisateurs et l'administration Yadony.
Chaque probleme utilisateur cree un ticket distinct. Le fil de messages du ticket reste
consultable dans l'historique, mais un ticket resolu ne se rouvre pas: un nouveau probleme
cree un nouveau ticket.

## Decisions validees

- Un ticket par probleme.
- Chat permanent dans chaque ticket, sans temps reel obligatoire.
- Reponses automatiques predifinies avant contact support.
- Si l'utilisateur n'est pas satisfait, il cree un ticket.
- Creation d'un ticket: alerte Telegram via le mecanisme backend existant.
- Reponse, assignation, reassignment et resolution: uniquement depuis le panel admin.
- Assignation admin pour eviter que plusieurs admins se marchent dessus.
- Un admin autorise peut reasssigner un ticket.

## Backend

Creer un module `support` separe de la messagerie P2P existante. La messagerie P2P reste
liee aux bids et a Firestore; le support est un workflow admin auditable, stocke en
PostgreSQL et expose en REST.

Tables:

- `support_tickets`: proprietaire utilisateur, categorie, sujet, statut, priorite, admin
  assigne, date de resolution.
- `support_messages`: messages du fil, auteur utilisateur ou admin, contenu, date.
- `support_predefined_replies`: catalogue versionne de questions/reponses predifinies.

Statuts:

- `NEW`: cree par l'utilisateur, non assigne.
- `ASSIGNED`: pris par un admin/support.
- `WAITING_USER`: l'admin a repondu et attend un retour.
- `WAITING_SUPPORT`: l'utilisateur a repondu et attend le support.
- `RESOLVED`: ferme. Pas de reouverture.

Permissions admin:

- `SUPPORT_TICKET_VIEW`
- `SUPPORT_TICKET_MANAGE`

Le role `SUPPORT` recoit ces deux permissions de base. `ADMIN` et `SUPER_ADMIN` y ont aussi
acces via leurs regles existantes.

API utilisateur:

- `GET /api/v1/support/replies`
- `GET /api/v1/support/tickets`
- `POST /api/v1/support/tickets`
- `GET /api/v1/support/tickets/{ticketId}`
- `POST /api/v1/support/tickets/{ticketId}/messages`

API admin:

- `GET /api/v1/admin/support/tickets?scope=unassigned|mine|all&status=...`
- `GET /api/v1/admin/support/tickets/{ticketId}`
- `POST /api/v1/admin/support/tickets/{ticketId}/assign`
- `POST /api/v1/admin/support/tickets/{ticketId}/reassign`
- `POST /api/v1/admin/support/tickets/{ticketId}/messages`
- `POST /api/v1/admin/support/tickets/{ticketId}/resolve`

Regles:

- Un utilisateur ne voit que ses tickets.
- Un utilisateur ne peut plus ecrire dans un ticket `RESOLVED`.
- Un admin non assigne ne peut pas repondre ni resoudre, sauf s'il s'assigne ou reassigne
  d'abord le ticket.
- Les actions importantes generent un audit log.
- L'alerte Telegram a la creation ne doit jamais bloquer la creation du ticket.

## Mobile

Ajouter une experience support dans la zone Messages / Support:

- Page assistant avec questions/reponses predifinies.
- Bouton de contact support depuis une reponse predifinie.
- Formulaire creation ticket: categorie, sujet, message.
- Liste des tickets utilisateur.
- Detail ticket avec fil de messages et champ de reponse si non resolu.

Pas de WebSocket ni Firestore pour le support. L'app recharge la liste ou le detail par REST
quand l'ecran s'ouvre ou apres envoi.

## Admin

Ajouter une entree de navigation `Support` dans le panel admin.

Vue support:

- Onglets: `Non assignes`, `Mes tickets`, `Tous`.
- Liste dense: statut, categorie, sujet, utilisateur, admin assigne, derniere activite.
- Detail: fil de messages, actions assigner, reasssigner, repondre, resoudre.

## Hors scope initial

- Pieces jointes support.
- Notifications push utilisateur quand l'admin repond.
- Edition du catalogue de reponses predifinies depuis le panel admin.
- Reouverture de ticket resolu.

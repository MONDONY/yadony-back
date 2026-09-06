-- Messagerie support : un ticket par probleme, jamais rouvert une fois resolu.
-- Volontairement separee de la messagerie P2P (Firestore, adossee aux bids) :
-- le support est un workflow admin auditable, donc stocke en PostgreSQL.

CREATE TABLE support_tickets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    category VARCHAR(32) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NEW',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    -- Pas de cle etrangere vers admin_users : le back-office est un referentiel
    -- separe, et la suppression d'un compte admin ne doit pas rendre un ticket
    -- historique illisible.
    assigned_admin_id UUID,
    -- Distincte d'updated_at, qui bouge aussi sur une simple reassignation :
    -- c'est cette colonne qui trie la file du support.
    last_message_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_tickets_user ON support_tickets (user_id, last_message_at DESC);
CREATE INDEX idx_support_tickets_status ON support_tickets (status, last_message_at DESC);
CREATE INDEX idx_support_tickets_assigned ON support_tickets (assigned_admin_id, last_message_at DESC);

CREATE TABLE support_messages (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets (id),
    -- USER pointe vers users, ADMIN vers admin_users : deux referentiels, donc
    -- le type doit accompagner l'identifiant.
    author_type VARCHAR(16) NOT NULL,
    author_id UUID NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_messages_ticket ON support_messages (ticket_id, created_at);

CREATE TABLE support_predefined_replies (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    category VARCHAR(32) NOT NULL,
    question VARCHAR(300) NOT NULL,
    answer TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_predefined_replies_active ON support_predefined_replies (active, sort_order);

-- Catalogue initial. L'edition depuis le back-office est hors scope : une
-- reponse se modifie par migration, ce qui garde une trace de ce qui a ete dit
-- aux utilisateurs a chaque periode.
INSERT INTO support_predefined_replies
    (id, code, category, question, answer, sort_order, active, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'account-verification', 'KYC',
     'Pourquoi mon compte doit-il etre verifie ?',
     'La verification d''identite protege les deux parties : elle garantit que la personne a qui vous confiez un colis, ou qui vous en confie un, est bien celle qu''elle pretend etre. Elle prend quelques minutes et n''est demandee qu''une seule fois.',
     10, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'kyc-rejected', 'KYC',
     'Ma verification d''identite a echoue, que faire ?',
     'Reprenez la verification depuis votre profil, en veillant a photographier un document en cours de validite, a plat, entierement dans le cadre et sans reflet. Si l''echec se repete, ouvrez un ticket : nous verifierons manuellement.',
     20, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'payment-when-charged', 'PAYMENT',
     'A quel moment suis-je debite ?',
     'Vous etes debite quand le voyageur accepte votre offre. La somme reste bloquee chez notre prestataire de paiement : le voyageur n''est paye qu''une fois la livraison confirmee par le destinataire.',
     30, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'payment-refund-delay', 'PAYMENT',
     'Quand arrive mon remboursement ?',
     'Un remboursement est renvoye sur le moyen de paiement d''origine et apparait en general sous 5 a 10 jours ouvres, selon votre banque.',
     40, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'payout-delay', 'PAYMENT',
     'Je suis voyageur, quand suis-je paye ?',
     'Le virement part des que la livraison est confirmee par le destinataire, puis suit les delais bancaires habituels. Si personne ne confirme, le versement est declenche automatiquement 48 heures apres la date de livraison prevue.',
     50, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'delivery-qr-scan', 'DELIVERY',
     'Comment se passe la remise du colis ?',
     'A la remise comme a la livraison, un QR code est scanne par les deux parties. Ce scan horodate l''echange et sert de preuve en cas de litige : ne remettez jamais un colis sans scanner.',
     60, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'delivery-late', 'DELIVERY',
     'Mon colis n''est pas arrive a la date prevue.',
     'Contactez d''abord le voyageur depuis la conversation liee au colis : un vol decale ou un controle douanier explique la plupart des retards. Sans reponse sous 48 heures, ouvrez un ticket, nous interviendrons.',
     70, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'trip-cancelled', 'TRIP',
     'Le voyageur a annule son trajet, que se passe-t-il ?',
     'Vous etes rembourse integralement et nous vous proposons automatiquement d''autres voyageurs sur le meme trajet.',
     80, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'package-forbidden-items', 'PACKAGE',
     'Que puis-je envoyer ?',
     'Sont interdits : especes, bijoux et objets de valeur, medicaments sur ordonnance, produits perissables, batteries au lithium hors appareil, armes et tout produit illegal. La valeur declaree d''un colis est plafonnee a 500 euros.',
     90, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),

    (gen_random_uuid(), 'account-delete', 'ACCOUNT',
     'Comment supprimer mon compte ?',
     'Depuis Profil, puis Parametres, puis Supprimer mon compte. La suppression est definitive une fois vos colis et trajets en cours termines.',
     100, TRUE, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC');

-- Outils du support : notes internes sur un ticket (jamais montrées à l'utilisateur) et réponses
-- types que les agents insèrent dans leurs réponses. Les réponses types ne sont PAS le catalogue
-- support_predefined_replies, qui est la FAQ montrée à l'utilisateur avant d'ouvrir un ticket.

CREATE TABLE support_ticket_notes (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets (id),
    -- Pas de clé étrangère vers admin_users, comme support_tickets.assigned_admin_id : la suppression
    -- d'un compte admin ne doit pas rendre l'historique d'un ticket illisible.
    admin_id UUID NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_ticket_notes_ticket ON support_ticket_notes (ticket_id, created_at);

CREATE TABLE support_reply_templates (
    id UUID PRIMARY KEY,
    label VARCHAR(60) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_support_reply_templates_order ON support_reply_templates (sort_order);

INSERT INTO support_reply_templates (id, label, content, sort_order, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'Demande de précisions',
     'Bonjour, pouvez-vous nous donner plus de détails (numéro de colis, date, capture d''écran si possible) afin que nous puissions vous aider ?',
     10, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),
    (gen_random_uuid(), 'Suivi du colis',
     'Bonjour, nous vérifions le suivi de votre colis auprès du voyageur et nous revenons vers vous dès que nous avons une réponse.',
     20, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),
    (gen_random_uuid(), 'Vérification d''identité',
     'Bonjour, pour vérifier votre identité, ouvrez l''application et suivez les étapes de la section « Vérification d''identité » de votre profil. Dites-nous si une étape vous bloque.',
     30, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),
    (gen_random_uuid(), 'Pris en charge',
     'Bonjour, nous avons bien reçu votre demande et nous nous en occupons.',
     40, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC'),
    (gen_random_uuid(), 'Clôture',
     'Bonjour, votre demande est traitée. Si besoin, vous pouvez nous écrire à nouveau depuis l''application. Bonne journée.',
     50, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC');

-- V274 : répondre au signalant d'un bug du scarabée dans une conversation support.
--
-- POST /admin/reports/{id}/reply ouvre (ou complète) un ticket support avec le
-- signalant ; reports.support_ticket_id garde le lien. Il sert à réutiliser le
-- ticket tant qu'il n'est pas résolu, et au lien inverse « Issu du signalement … »
-- sur la page Support.
--
-- Colonne nullable : les signalements existants n'ont pas de conversation.
-- Clé étrangère vers support_tickets, comme support_messages.ticket_id (V244) :
-- les deux tables ne connaissent que la suppression douce, la contrainte ne peut
-- donc jamais bloquer une suppression, et elle empêche un lien vers un ticket
-- inexistant. Aucune cascade.

ALTER TABLE reports
    ADD COLUMN support_ticket_id UUID;

ALTER TABLE reports
    ADD CONSTRAINT fk_reports_support_ticket
        FOREIGN KEY (support_ticket_id) REFERENCES support_tickets (id);

-- Lien inverse (ticket → signalement) lu par la file et le détail Support.
CREATE INDEX IF NOT EXISTS idx_reports_support_ticket
    ON reports (support_ticket_id)
    WHERE support_ticket_id IS NOT NULL;

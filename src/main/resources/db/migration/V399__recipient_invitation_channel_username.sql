-- Étape 1 de l'identifiant Yadony : le canal USERNAME existe dans le code (V391 n'a pas élargi la
-- contrainte posée par V284). Sans cet élargissement, l'insertion d'une invitation par identifiant
-- violait chk_recipient_invitations_channel et l'envoi répondait « envoyé » sans rien créer.
ALTER TABLE recipient_invitations DROP CONSTRAINT IF EXISTS chk_recipient_invitations_channel;
ALTER TABLE recipient_invitations
    ADD CONSTRAINT chk_recipient_invitations_channel CHECK (channel IN ('PHONE', 'EMAIL', 'USERNAME'));

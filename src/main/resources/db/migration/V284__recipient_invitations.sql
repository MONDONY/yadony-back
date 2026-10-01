-- Lot 4 : destinataire Yadony dans le carnet, par invitation.
--
-- L'expéditeur invite un destinataire par son numéro ou son email, sans jamais savoir
-- si un compte existe : la cible n'est conservée qu'en empreinte SHA-256 (rattrapage
-- à l'inscription, anti-doublon) et sous forme masquée pour la liste « Invitations
-- envoyées ». Une fois l'invitation acceptée, l'entrée du carnet de l'inviteur est liée
-- (recipient_id) et ses colis sont rattachés directement au compte de l'invité.

CREATE TABLE recipient_invitations (
    id               UUID PRIMARY KEY,
    inviter_user_id  UUID NOT NULL REFERENCES users(id),
    invitee_user_id  UUID REFERENCES users(id),
    channel          VARCHAR(10) NOT NULL,
    target_hash      VARCHAR(64) NOT NULL,
    masked_target    VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    responded_at     TIMESTAMPTZ,
    recipient_id     UUID REFERENCES recipients(id),
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    deleted_at       TIMESTAMP,
    CONSTRAINT chk_recipient_invitations_channel CHECK (channel IN ('PHONE', 'EMAIL')),
    CONSTRAINT chk_recipient_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED'))
);

CREATE INDEX idx_recipient_invitations_inviter ON recipient_invitations (inviter_user_id);
CREATE INDEX idx_recipient_invitations_invitee ON recipient_invitations (invitee_user_id);
CREATE INDEX idx_recipient_invitations_target ON recipient_invitations (target_hash);

-- Une seule invitation vivante (PENDING ou ACCEPTED) par inviteur et par cible.
CREATE UNIQUE INDEX uq_recipient_invitations_live
    ON recipient_invitations (inviter_user_id, target_hash)
    WHERE status IN ('PENDING', 'ACCEPTED') AND deleted_at IS NULL;

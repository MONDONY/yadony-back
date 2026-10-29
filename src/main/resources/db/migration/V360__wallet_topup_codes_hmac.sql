-- Codes de recharge stockés par empreinte (audit sécurité du portefeuille, 28/10) : la table ne
-- garde plus aucun code utilisable. code_hash = HMAC-SHA256 du code normalisé, avec une clé hors
-- base (TOPUP_CODE_HMAC_KEY) ; code_hint = fin du code, seule partie montrée après la création.
--
-- Les codes existants ne peuvent pas être hachés ici (la clé n'est pas en base) : au démarrage,
-- avant d'ouvrir le serveur, TopupCodeHashBackfill calcule leur empreinte puis efface le code en
-- clair. Ils restent utilisables (décision du propriétaire, 29/10).
ALTER TABLE wallet_topup_codes ADD COLUMN code_hash VARCHAR(64);
ALTER TABLE wallet_topup_codes ADD COLUMN code_hint VARCHAR(8);
ALTER TABLE wallet_topup_codes ALTER COLUMN code DROP NOT NULL;

ALTER TABLE wallet_topup_codes DROP CONSTRAINT chk_topup_code_format;
ALTER TABLE wallet_topup_codes ADD CONSTRAINT chk_topup_code_format
    CHECK (code IS NULL OR code ~ '^[A-Z0-9-]{4,32}$');
-- Toujours un moyen de retrouver le code : son empreinte, ou (avant le rattrapage) son texte.
ALTER TABLE wallet_topup_codes ADD CONSTRAINT chk_topup_code_lookup
    CHECK (code_hash IS NOT NULL OR code IS NOT NULL);
ALTER TABLE wallet_topup_codes ADD CONSTRAINT chk_topup_code_hash_format
    CHECK (code_hash IS NULL OR code_hash ~ '^[0-9a-f]{64}$');

-- Unicité y compris sur les lignes soft-deleted, comme uq_wallet_topup_codes_code.
CREATE UNIQUE INDEX uq_wallet_topup_codes_code_hash ON wallet_topup_codes (code_hash);

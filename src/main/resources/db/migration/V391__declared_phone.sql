-- Numéro déclaré sans SMS. Le numéro n'est jamais stocké ici (il vit dans un claim Firebase) :
-- seule son empreinte HMAC-SHA256 sert à retrouver les comptes qui le déclarent.
ALTER TABLE users ADD COLUMN declared_phone_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN declared_phone_level VARCHAR(20);
ALTER TABLE users ADD COLUMN declared_phone_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN declared_phone_verified_by UUID;

CREATE INDEX idx_users_declared_phone_hash ON users (declared_phone_hash);

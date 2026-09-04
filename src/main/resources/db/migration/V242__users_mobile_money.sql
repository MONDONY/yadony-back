-- V242: compte de versement mobile money (rail pawaPay), porté par users comme le compte
-- Stripe Connect. Seule mobile_money_status est NOT NULL, avec DEFAULT : les lignes
-- existantes ne bougent pas.
ALTER TABLE users
    ADD COLUMN mobile_money_status        VARCHAR(32)  NOT NULL DEFAULT 'NOT_CONFIGURED',
    ADD COLUMN mobile_money_msisdn        VARCHAR(255),
    ADD COLUMN mobile_money_msisdn_masked VARCHAR(32),
    ADD COLUMN mobile_money_provider      VARCHAR(30),
    ADD COLUMN mobile_money_country       VARCHAR(2),
    ADD COLUMN mobile_money_currency      VARCHAR(3),
    ADD COLUMN mobile_money_verified_at   TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT users_mobile_money_status_check
    CHECK (mobile_money_status IN ('NOT_CONFIGURED', 'ACTIVE', 'DISABLED'));

-- Comptes de versement mobile money : un compte actif par utilisateur ET par code opérateur pawaPay
-- (opérateur + pays, ex. ORANGE_CIV et ORANGE_SEN sont deux comptes distincts).
--
-- Reprise : seul le compte unique de users.mobile_money_* devient une ligne (migrated = true, c'est le
-- compte de repli d'un versement dont le code n'a pas de compte propre). Les réseaux cochés EN PLUS
-- (users.mobile_money_providers) ne deviennent PAS des comptes : ils partageaient le numéro du compte
-- unique, qui n'a été vérifié que pour son propre opérateur, donc un compte MTN sur un numéro Orange
-- serait faux (spec §6.3). On les garde seulement en trace dans legacy_accepted_providers.
--
-- Colonnes de BaseEntity à l'identique de leur déclaration Java (LocalDateTime -> TIMESTAMP, UTC).
-- msisdn reçoit tel quel le texte chiffré de users.mobile_money_msisdn (même EncryptedStringConverter).

CREATE TABLE mobile_money_payout_accounts (
    id                        UUID PRIMARY KEY,
    user_id                   UUID         NOT NULL REFERENCES users(id),
    provider                  VARCHAR(30)  NOT NULL,
    country                   VARCHAR(2)   NOT NULL,
    currency                  VARCHAR(3)   NOT NULL,
    msisdn                    VARCHAR(255) NOT NULL,
    msisdn_masked             VARCHAR(32)  NOT NULL,
    migrated                  BOOLEAN      NOT NULL DEFAULT FALSE,
    legacy_accepted_providers VARCHAR(255),
    verified_at               TIMESTAMPTZ  NOT NULL,
    created_at                TIMESTAMP    NOT NULL,
    updated_at                TIMESTAMP    NOT NULL,
    deleted_at                TIMESTAMP
);

CREATE UNIQUE INDEX uq_mm_payout_accounts_user_provider_live
    ON mobile_money_payout_accounts (user_id, provider)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_mm_payout_accounts_user_live
    ON mobile_money_payout_accounts (user_id)
    WHERE deleted_at IS NULL;

-- Résumé des devises couvertes par les comptes actifs (CSV, comme mobile_money_providers).
ALTER TABLE users ADD COLUMN mobile_money_currencies VARCHAR(64);

INSERT INTO mobile_money_payout_accounts
    (id, user_id, provider, country, currency, msisdn, msisdn_masked, migrated,
     legacy_accepted_providers, verified_at, created_at, updated_at)
SELECT gen_random_uuid(), u.id, u.mobile_money_provider, u.mobile_money_country, u.mobile_money_currency,
       u.mobile_money_msisdn, COALESCE(u.mobile_money_msisdn_masked, ''), TRUE,
       u.mobile_money_providers, COALESCE(u.mobile_money_verified_at, now()),
       now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC'
FROM users u
WHERE u.mobile_money_status = 'ACTIVE'
  AND u.deleted_at IS NULL
  AND u.mobile_money_msisdn IS NOT NULL
  AND u.mobile_money_provider IS NOT NULL
  AND u.mobile_money_country IS NOT NULL
  AND u.mobile_money_currency IS NOT NULL;

UPDATE users
SET mobile_money_currencies = mobile_money_currency
WHERE mobile_money_status = 'ACTIVE'
  AND deleted_at IS NULL
  AND mobile_money_msisdn IS NOT NULL
  AND mobile_money_provider IS NOT NULL
  AND mobile_money_country IS NOT NULL
  AND mobile_money_currency IS NOT NULL;

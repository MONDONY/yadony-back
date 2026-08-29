-- V232__automation_rules_disabled_by_downgrade.sql
-- Distingue une règle désactivée par la perte du statut PRO d'une règle que le
-- voyageur a lui-même désactivée. Sans ce marqueur, un réabonnement
-- réactiverait en masse des règles volontairement éteintes.
ALTER TABLE automation_rules
    ADD COLUMN disabled_by_downgrade BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_automation_rules_disabled_by_downgrade
    ON automation_rules (traveler_id)
    WHERE disabled_by_downgrade = TRUE;

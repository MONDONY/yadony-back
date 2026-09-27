-- V269 : provenance de chaque étape de suivi.
--
-- scan_method dit comment le voyageur a identifié le colis : QR (scan du QR code)
-- ou MANUAL (saisie du numéro de suivi). NULL = inconnu : lignes antérieures à
-- cette migration et apps déjà installées qui n'envoient pas le champ.

ALTER TABLE tracking_events ADD COLUMN IF NOT EXISTS scan_method VARCHAR(10) NULL;
ALTER TABLE tracking_events ADD CONSTRAINT tracking_events_scan_method_check CHECK (
    scan_method IS NULL OR scan_method IN ('QR','MANUAL')
);

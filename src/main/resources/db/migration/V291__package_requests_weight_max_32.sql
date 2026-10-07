-- V291 : aligne le plafond de poids d'une demande de colis sur l'API et l'app (STAGING-M).
-- V57 bornait weight_kg à 30 kg alors que PackageRequestCreateRequest accepte
-- @DecimalMax("32.0") depuis le 06/06 (commit c913782d) : un poids dans ]30;32]
-- passait la validation puis faisait échouer l'INSERT/UPDATE en 500.
-- Élargir la borne ne rend aucune ligne existante invalide.

ALTER TABLE package_requests
    DROP CONSTRAINT chk_pkg_req_weight,
    ADD CONSTRAINT chk_pkg_req_weight CHECK (weight_kg BETWEEN 0.5 AND 32);

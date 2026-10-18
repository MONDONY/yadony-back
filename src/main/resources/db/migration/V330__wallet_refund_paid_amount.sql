-- Montant réellement versé hors app quand l'admin clôt à la main une demande de remboursement de
-- solde (canal MANUAL_ADMIN), et sa note. Il peut être inférieur au montant demandé : frais
-- d'opérateur à la charge de l'utilisateur (cas réel : 500 XOF demandés, 487 XOF versés, ce que le
-- wallet pawaPay avait encaissé). Le débit du solde, lui, ne change pas.
-- Colonnes facultatives : nulles pour les demandes déjà résolues, pour les remboursements
-- automatiques, et quand l'admin résout sans préciser de montant (contrat d'avant).

ALTER TABLE wallet_refund_requests ADD COLUMN paid_amount NUMERIC(10,2) NULL;
ALTER TABLE wallet_refund_requests ADD COLUMN resolution_note TEXT NULL;

ALTER TABLE wallet_refund_requests
    ADD CONSTRAINT chk_wallet_refund_requests_paid_amount_positive
        CHECK (paid_amount IS NULL OR paid_amount > 0);

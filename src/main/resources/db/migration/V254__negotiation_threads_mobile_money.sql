-- Mobile money sur la négociation d'un colis (lot 1). AWAITING_DEPOSIT : dépôt pawaPay en
-- cours, rien n'est scellé. Sans cette extension de la contrainte, toute transition vers ce
-- statut échoue en PostgreSQL alors que les tests H2 (profil test, Flyway désactivé) restent
-- verts (même leçon que V211 pour AWAITING_COMMISSION).
ALTER TABLE negotiation_threads DROP CONSTRAINT chk_neg_thread_status;
ALTER TABLE negotiation_threads ADD CONSTRAINT chk_neg_thread_status CHECK (
  status IN ('OPEN','AWAITING_TRIP','AWAITING_PAYMENT','AWAITING_COMMISSION','AWAITING_DEPOSIT',
             'ACCEPTED','REJECTED','AUTO_REJECTED','EXPIRED','CANCELLED')
);

-- Échéance du dépôt, lue par le balayage d'expiration. Nulle hors AWAITING_DEPOSIT.
ALTER TABLE negotiation_threads ADD COLUMN IF NOT EXISTS deposit_expires_at TIMESTAMP NULL;

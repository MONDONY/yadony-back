-- AWAITING_COMMISSION : un accord en espèces est conclu par l'expéditeur mais reste
-- suspendu tant que le voyageur n'a pas réglé la commission Yadony. Sans cette
-- extension de la contrainte, toute transition vers ce statut échoue en PostgreSQL
-- alors que les tests H2 (profil test, Flyway désactivé) restent verts.
ALTER TABLE negotiation_threads DROP CONSTRAINT chk_neg_thread_status;
ALTER TABLE negotiation_threads ADD CONSTRAINT chk_neg_thread_status CHECK (
  status IN ('OPEN','AWAITING_TRIP','AWAITING_PAYMENT','AWAITING_COMMISSION','ACCEPTED','REJECTED','AUTO_REJECTED','EXPIRED','CANCELLED')
);

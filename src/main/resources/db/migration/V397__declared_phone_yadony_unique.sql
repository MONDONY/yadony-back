-- Un numéro déclaré sans SMS ne peut être « Vérifié par Yadony » que sur un seul compte vivant.
--
-- AdminDeclaredPhoneService contrôle d'abord qu'aucun autre compte n'est déjà validé (existsBy...), puis
-- écrit : deux administrateurs qui valident au même moment deux comptes déclarant le même numéro passent
-- tous les deux ce contrôle (aucun ne voit l'écriture non validée de l'autre), et le numéro devenait
-- ambigu pour tout le monde. Cet index tranche : la seconde écriture échoue (23505), traduite en 409
-- declared-phone-conflict. Les simples déclarants (DECLARED) restent libres de partager un numéro, et
-- un compte supprimé (deleted_at) ne bloque plus personne.
--
-- Index partiel : la colonne declared_phone_level n'existe qu'à partir de V396. Sous H2 (profil test,
-- schéma créé par Hibernate), la migration ne tourne pas : DeclaredPhoneSchemaPostgresIT et
-- AdminDeclaredPhoneRacePostgresIT la vérifient sur PostgreSQL.
CREATE UNIQUE INDEX uq_users_declared_phone_yadony
    ON users (declared_phone_hash)
    WHERE declared_phone_level = 'YADONY_VERIFIED' AND deleted_at IS NULL;

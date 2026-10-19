-- Ordre d'insertion du grand livre wallet (INV-02, MoneyInvariants).
--
-- La chaîne des soldes (ancien solde + mouvement = nouveau solde) se lisait dans l'ordre
-- (created_at, id). Deux lignes d'un même utilisateur et d'une même devise écrites dans la
-- même transaction partagent created_at (now() est figé pour la transaction), et id est un
-- UUID aléatoire : l'ordre entre elles était tiré au sort, et INV-02 signalait une fausse
-- rupture une fois sur deux.
--
-- seq suit l'ordre réel d'insertion. Les lignes existantes sont numérotées pendant la
-- réécriture de la table par l'ALTER TABLE, qui ne déclenche aucun trigger de ligne : le
-- trigger d'ajout seul (trg_wallet_transactions_append_only, V309, BEFORE UPDATE OR DELETE)
-- n'est pas sollicité. Pour ces lignes historiques, l'ordre est celui du stockage, le plus
-- proche disponible de l'ordre d'insertion.
ALTER TABLE wallet_transactions
    ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX idx_wallet_transactions_chain ON wallet_transactions (user_id, currency, created_at, seq);

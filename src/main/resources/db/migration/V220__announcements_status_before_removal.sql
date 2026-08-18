-- Lot C — reprise de dette Lot B.
-- restoreByAdmin forçait ACTIVE : restaurer une annonce COMPLETED/CANCELLED la remettait
-- sur le marché avec une date de départ passée. On mémorise donc le statut d'avant-retrait.
-- NULL pour les lignes retirées avant cette migration : la restauration retombe alors sur ACTIVE.
ALTER TABLE announcements
    ADD COLUMN status_before_removal VARCHAR(20);

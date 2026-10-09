-- FLUTTER-FT : la carte devient décochable sur un trajet récurrent, comme sur un trajet simple
-- (V307, announcements.card_declined). La récurrence retient le refus et le recopie sur chaque
-- occurrence générée (TripRecurrenceService#buildRequest), pour que la réouverture automatique
-- à la carte après un onboarding Stripe (AnnouncementService#enableCardOnOpenAnnouncements,
-- FLUTTER-DH) ne passe jamais outre.
-- FALSE pour toutes les récurrences existantes : aucune n'a pu refuser la carte jusqu'ici.
ALTER TABLE trip_recurrences ADD COLUMN IF NOT EXISTS card_declined BOOLEAN NOT NULL DEFAULT FALSE;

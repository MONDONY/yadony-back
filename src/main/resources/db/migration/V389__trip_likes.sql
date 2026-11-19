CREATE TABLE trip_likes (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    trip_id     UUID NOT NULL REFERENCES announcements(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_trip_likes_active
    ON trip_likes (user_id, trip_id)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_trip_likes_trip
    ON trip_likes (trip_id)
    WHERE deleted_at IS NULL;

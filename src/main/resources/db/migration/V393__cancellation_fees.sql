CREATE TABLE cancellation_fees (
    id            UUID PRIMARY KEY,
    bid_id        UUID NOT NULL REFERENCES bids(id),
    sender_id     UUID NOT NULL REFERENCES users(id),
    traveler_id   UUID NOT NULL REFERENCES users(id),
    rate          NUMERIC(5,4) NOT NULL,
    reason        VARCHAR(40) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    gross_fee     NUMERIC(12,2) NOT NULL,
    refund_fees   NUMERIC(12,2) NOT NULL,
    commission    NUMERIC(12,2) NOT NULL,
    traveler_net  NUMERIC(12,2) NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    payout_ref    VARCHAR(100),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_cancellation_fees_bid
    ON cancellation_fees (bid_id)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_cancellation_fees_status
    ON cancellation_fees (status)
    WHERE deleted_at IS NULL;

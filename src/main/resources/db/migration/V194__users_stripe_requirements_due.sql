CREATE TABLE user_stripe_requirements_due (
    user_id     uuid NOT NULL REFERENCES users(id),
    requirement varchar(64) NOT NULL,
    PRIMARY KEY (user_id, requirement)
);

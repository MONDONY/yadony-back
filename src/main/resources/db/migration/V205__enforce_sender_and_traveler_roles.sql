-- Every application account must keep both functional roles.  The constraint
-- trigger is deferred so Hibernate can insert the two user_roles rows in one
-- transaction without the first insert failing prematurely.

INSERT INTO user_roles (user_id, role)
SELECT id, 'SENDER'
FROM users
ON CONFLICT (user_id, role) DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT id, 'TRAVELER'
FROM users
ON CONFLICT (user_id, role) DO NOTHING;

CREATE OR REPLACE FUNCTION enforce_required_user_roles_for_user()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_user_id uuid;
BEGIN
    target_user_id := COALESCE(NEW.id, OLD.id);

    IF EXISTS (SELECT 1 FROM users WHERE id = target_user_id)
       AND (
           NOT EXISTS (
               SELECT 1 FROM user_roles
               WHERE user_id = target_user_id AND role = 'SENDER'
           )
           OR NOT EXISTS (
               SELECT 1 FROM user_roles
               WHERE user_id = target_user_id AND role = 'TRAVELER'
           )
       ) THEN
        RAISE EXCEPTION 'User % must have both SENDER and TRAVELER roles', target_user_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_required_user_roles_for_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_user_id uuid;
BEGIN
    target_user_id := COALESCE(NEW.user_id, OLD.user_id);

    IF EXISTS (SELECT 1 FROM users WHERE id = target_user_id)
       AND (
           NOT EXISTS (
               SELECT 1 FROM user_roles
               WHERE user_id = target_user_id AND role = 'SENDER'
           )
           OR NOT EXISTS (
               SELECT 1 FROM user_roles
               WHERE user_id = target_user_id AND role = 'TRAVELER'
           )
       ) THEN
        RAISE EXCEPTION 'User % must have both SENDER and TRAVELER roles', target_user_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER user_roles_require_sender_and_traveler
AFTER INSERT OR UPDATE OR DELETE ON user_roles
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_required_user_roles_for_role();

CREATE CONSTRAINT TRIGGER users_require_sender_and_traveler
AFTER INSERT OR UPDATE ON users
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_required_user_roles_for_user();

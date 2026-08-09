-- Voyageur universel, ré-appliqué : la fonctionnalité d'auto-désactivation
-- (DELETE /users/me/roles/traveler, UserRoleService.deactivateTravelerRole)
-- a permis à des comptes de perdre TRAVELER après le backfill V176. Cette
-- fonctionnalité est retirée (un utilisateur porte toujours les deux rôles,
-- sans jamais pouvoir en retirer un) — ce backfill répare les comptes qui
-- l'avaient utilisée.
INSERT INTO user_roles (user_id, role)
SELECT id, 'TRAVELER'
FROM users
ON CONFLICT (user_id, role) DO NOTHING;

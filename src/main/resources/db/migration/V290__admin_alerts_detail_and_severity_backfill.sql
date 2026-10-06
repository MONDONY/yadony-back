-- V290 : les alertes du back-office deviennent lisibles.
--
-- 1. Colonne detail : la phrase de l'incident (celle envoyée sur Telegram) n'était
--    persistée nulle part ; l'écran Alertes n'affichait que le code technique.
-- 2. Rattrapage de severity : AdminAlertEscalator et les schedulers n'écrivaient jamais
--    la colonne, toutes les alertes restaient au défaut INFO — même un séquestre bloqué
--    ou une incohérence d'argent CRITIQUE.
ALTER TABLE admin_alerts ADD COLUMN IF NOT EXISTS detail TEXT;

-- Sondes de cohérence de l'argent : la gravité de la règle est dans le payload.
UPDATE admin_alerts
SET severity = CASE payload ->> 'gravite' WHEN 'CRITIQUE' THEN 'CRITICAL' ELSE 'WARN' END,
    detail   = COALESCE(detail,
                 'Incohérence d''argent ' || COALESCE(payload ->> 'invariant', substring(type FROM 17))
                 || ' : ' || COALESCE(payload ->> 'regle', 'règle inconnue')
                 || ' — ' || COALESCE(payload ->> 'lignesEnFaute', '?') || ' ligne(s) en faute')
WHERE type LIKE 'MONEY\_INVARIANT\_%' AND severity = 'INFO';

-- Schedulers : à vérifier, pas un incident en soi.
UPDATE admin_alerts
SET severity = 'WARN'
WHERE type IN ('ESCROW_J48_TIMEOUT', 'RETURN_DEADLINE_EXPIRED') AND severity = 'INFO';

-- Tous les autres types sont levés par AdminAlertEscalator, dont la gravité Telegram/Sentry
-- est « incident » (AdminAlertService#graviteDe : aucun type suffixé d'un identifiant n'est
-- rangé en information ou avertissement).
UPDATE admin_alerts
SET severity = 'CRITICAL'
WHERE severity = 'INFO'
  AND type NOT LIKE 'MONEY\_INVARIANT\_%'
  AND type NOT IN ('ESCROW_J48_TIMEOUT', 'RETURN_DEADLINE_EXPIRED');

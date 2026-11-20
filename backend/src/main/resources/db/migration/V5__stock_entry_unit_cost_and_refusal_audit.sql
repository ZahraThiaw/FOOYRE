-- MVP-3 (finalisation, 2026-11-19) : coût unitaire facultatif des ENTRÉES de stock et audit des
-- sorties refusées pour stock insuffisant. Aucune donnée existante n'est modifiée ni supprimée.

-- 1. Coût unitaire d'acquisition d'une entrée (facultatif). Échelle 4 = capacité de stockage pour
--    les futurs calculs internes (CMP, D1) ; la SAISIE est limitée aux décimales de la devise par le
--    service. Ajouter une colonne nullable ne réécrit aucune ligne : le déclencheur d'insertion seule
--    (stock_movement_reject_change) n'est pas sollicité et les mouvements existants gardent NULL.
ALTER TABLE stock_movement ADD COLUMN unit_cost NUMERIC(19,4);

ALTER TABLE stock_movement ADD CONSTRAINT ck_stock_movement_unit_cost CHECK (
    unit_cost IS NULL OR (unit_cost > 0 AND movement_type = 'ENTRY'));

-- 2. Nouvelle action d'audit : tentative de sortie refusée (stock insuffisant).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_action;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_action CHECK (action IN (
    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
    'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED',
    'CATEGORY_CREATED', 'CATEGORY_UPDATED', 'CATEGORY_ARCHIVED',
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_ARCHIVED',
    'STOCK_INITIALIZED', 'STOCK_ENTRY_RECORDED', 'STOCK_EXIT_RECORDED', 'STOCK_ADJUSTED',
    'STOCK_MINIMUM_CHANGED', 'SETTINGS_CHANGED', 'STOCK_EXIT_REJECTED_INSUFFICIENT'));

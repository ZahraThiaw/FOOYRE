-- =====================================================================================
-- FOOYRE — MVP-1.1 : limitation des tentatives de connexion et journal d'audit.
-- Aucune donnée personnelle en clair : e-mails et adresses IP ne sont stockés que sous forme
-- d'empreinte HMAC-SHA256 (clé applicative), jamais de mot de passe, de jeton ni de cookie.
-- =====================================================================================

-- --- Compteurs d'échecs de connexion (identity.application.LoginThrottleService) ----------
-- Une ligne par clé : couple e-mail normalisé + IP, e-mail normalisé seul, IP seule.
CREATE TABLE login_throttle (
    key_hash          CHAR(64)    NOT NULL,
    key_type          VARCHAR(20) NOT NULL,
    failure_count     INTEGER     NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    blocked_until     TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_login_throttle PRIMARY KEY (key_hash),
    CONSTRAINT ck_login_throttle_key_type CHECK (key_type IN ('ACCOUNT_IP', 'ACCOUNT', 'IP')),
    CONSTRAINT ck_login_throttle_key_hash CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_login_throttle_failure_count CHECK (failure_count >= 0)
);

-- Nettoyage périodique des compteurs expirés.
CREATE INDEX ix_login_throttle_updated_at ON login_throttle (updated_at);

-- --- Journal d'audit (shared.audit.AuditService) : insertion seule ------------------------
CREATE TABLE audit_log (
    id            UUID        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    -- Entreprise concernée (NULL : événement d'authentification hors entreprise ou plateforme).
    business_id   UUID,
    -- Utilisateur acteur (NULL : acteur inconnu, ex. échec de connexion, ou initialisation système).
    actor_user_id UUID,
    action        VARCHAR(60) NOT NULL,
    outcome       VARCHAR(20) NOT NULL,
    target_type   VARCHAR(40),
    target_id     UUID,
    trace_id      VARCHAR(64),
    ip_hash       CHAR(64),
    -- Métadonnées : uniquement les clés de la liste blanche shared.audit.AuditDetail.
    details       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_audit_log_action CHECK (action IN (
        'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
        'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED')),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_log_target CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CONSTRAINT ck_audit_log_ip_hash CHECK (ip_hash IS NULL OR ip_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_log_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_audit_log_business_occurred ON audit_log (business_id, occurred_at DESC);
CREATE INDEX ix_audit_log_actor_occurred ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_log_action_occurred ON audit_log (action, occurred_at DESC);

-- Insertion seule, quel que soit le rôle (y compris le propriétaire du schéma) : toute
-- modification ou suppression ligne à ligne est refusée.
CREATE FUNCTION audit_log_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log est en insertion seule' USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tr_audit_log_insert_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

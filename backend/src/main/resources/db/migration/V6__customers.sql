-- =====================================================================================
-- FOOYRE — MVP-4A : clients (module gestion).
-- Modèle d'isolation obligatoire (docs/architecture.md §5.2) : business_id NOT NULL + FK,
-- UNIQUE (business_id, id) pour les futures FK composites (ventes), index commençant par
-- business_id, RLS par commande, AUCUNE politique DELETE (archivage uniquement).
--
-- Unicité : aucune sur le nom ni sur le téléphone. Homonymes fréquents ; un même numéro peut être
-- partagé (famille, boutique, standard) : bloquer la saisie empêcherait d'enregistrer un client
-- réel. Seule unicité : la clé d'idempotence d'une création (unicité durable, ADR-09/ADR-14).
-- =====================================================================================

CREATE TABLE customer (
    id              UUID          NOT NULL,
    business_id     UUID          NOT NULL,
    -- Nom complet ou raison sociale ; facultatif si un téléphone permet d'identifier le client.
    name            VARCHAR(150),
    -- Téléphone TEL QUE SAISI (espaces, +, indicatif…), normalisé pour l'affichage uniquement.
    phone           VARCHAR(30),
    -- Chiffres seuls, calculés par la base : recherche indépendante de la mise en forme
    -- (« 77 123 45 67 », « +221 77 123 45 67 » et « 00221771234567 » se retrouvent).
    phone_digits    VARCHAR(30)   GENERATED ALWAYS AS (regexp_replace(phone, '[^0-9]', '', 'g')) STORED,
    email           VARCHAR(254),
    address         VARCHAR(500),
    notes           VARCHAR(2000),
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    archived_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    version         INTEGER       NOT NULL DEFAULT 0,
    -- Création idempotente facultative (obligatoire depuis le futur mode hors ligne, O10).
    idempotency_key UUID,
    request_hash    CHAR(64),
    CONSTRAINT pk_customer PRIMARY KEY (id),
    CONSTRAINT uq_customer_business_id_id UNIQUE (business_id, id),
    CONSTRAINT fk_customer_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_customer_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    -- Au moins un moyen d'identifier le client.
    CONSTRAINT ck_customer_identity CHECK (name IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_customer_name CHECK (name IS NULL OR (length(name) > 0 AND name = btrim(name))),
    CONSTRAINT ck_customer_phone CHECK (phone IS NULL OR length(btrim(phone)) > 0),
    CONSTRAINT ck_customer_email CHECK (email IS NULL OR (email = lower(email) AND position('@' IN email) > 1)),
    CONSTRAINT ck_customer_address CHECK (address IS NULL OR length(btrim(address)) > 0),
    CONSTRAINT ck_customer_notes CHECK (notes IS NULL OR length(btrim(notes)) > 0),
    CONSTRAINT ck_customer_idempotency CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    CONSTRAINT ck_customer_request_hash CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX ux_customer_business_idempotency ON customer (business_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- Liste paginée triée par nom (filtre de statut) et recherches par téléphone / e-mail.
CREATE INDEX ix_customer_business_status_name ON customer (business_id, status, lower(name));
CREATE INDEX ix_customer_business_phone_digits ON customer (business_id, phone_digits);
CREATE INDEX ix_customer_business_email ON customer (business_id, email);

-- --- Row Level Security : une politique par commande, aucune pour DELETE ----------------------
ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY customer_tenant_select ON customer FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY customer_tenant_insert ON customer FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY customer_tenant_update ON customer FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- --- Journal d'audit : actions sur les clients (aucune donnée personnelle dans l'événement) ----
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_action;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_action CHECK (action IN (
    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
    'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED',
    'CATEGORY_CREATED', 'CATEGORY_UPDATED', 'CATEGORY_ARCHIVED',
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_ARCHIVED',
    'STOCK_INITIALIZED', 'STOCK_ENTRY_RECORDED', 'STOCK_EXIT_RECORDED', 'STOCK_ADJUSTED',
    'STOCK_MINIMUM_CHANGED', 'SETTINGS_CHANGED', 'STOCK_EXIT_REJECTED_INSUFFICIENT',
    'CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED'));

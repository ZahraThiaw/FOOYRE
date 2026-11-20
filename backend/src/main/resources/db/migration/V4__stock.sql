-- =====================================================================================
-- FOOYRE — MVP-3 : stock (état courant + journal immuable des mouvements) et paramètre
-- « stock négatif » de l'entreprise (D5). Modèle d'isolation obligatoire (architecture.md §5.2).
-- Quantités : NUMERIC(19,3) ; précision effective selon l'unité du produit (contrôlée par
-- l'application : unités entières ou 3 décimales).
-- =====================================================================================

-- --- Paramètre de l'entreprise : stock négatif autorisé (désactivé par défaut, D5) --------------
ALTER TABLE business ADD COLUMN allow_negative_stock BOOLEAN NOT NULL DEFAULT FALSE;

-- --- État courant du stock d'un produit (une ligne = stock initialisé) ---------------------------
CREATE TABLE product_stock (
    id               UUID           NOT NULL,
    business_id      UUID           NOT NULL,
    product_id       UUID           NOT NULL,
    -- Peut devenir négative uniquement si l'entreprise l'autorise (contrôle applicatif sous verrou).
    quantity         NUMERIC(19, 3) NOT NULL,
    -- Seuil d'alerte « stock faible » ; 0 = aucune alerte avant la rupture.
    minimum_quantity NUMERIC(19, 3) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          INTEGER        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_stock PRIMARY KEY (id),
    CONSTRAINT uq_product_stock_business_id_id UNIQUE (business_id, id),
    -- Un seul stock par produit et par entreprise (empêche une double initialisation).
    CONSTRAINT uq_product_stock_business_product UNIQUE (business_id, product_id),
    CONSTRAINT fk_product_stock_business FOREIGN KEY (business_id) REFERENCES business (id),
    -- FK COMPOSITE : le produit appartient forcément à la même entreprise.
    CONSTRAINT fk_product_stock_product FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id),
    CONSTRAINT ck_product_stock_minimum CHECK (minimum_quantity >= 0)
);

-- --- Journal des mouvements : insertion seule (historique de référence) ---------------------------
CREATE TABLE stock_movement (
    id               UUID           NOT NULL,
    business_id      UUID           NOT NULL,
    product_id       UUID           NOT NULL,
    movement_type    VARCHAR(20)    NOT NULL,
    -- Toujours strictement positive ; le sens est donné par le type.
    quantity         NUMERIC(19, 3) NOT NULL,
    quantity_before  NUMERIC(19, 3) NOT NULL,
    quantity_after   NUMERIC(19, 3) NOT NULL,
    reason           VARCHAR(255),
    reference        VARCHAR(100),
    created_by       UUID           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    -- Clé d'idempotence (unicité durable) et empreinte de la demande (rejeu identique ou non).
    idempotency_key  UUID           NOT NULL,
    request_hash     CHAR(64)       NOT NULL,
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT uq_stock_movement_business_id_id UNIQUE (business_id, id),
    CONSTRAINT uq_stock_movement_business_idempotency UNIQUE (business_id, idempotency_key),
    CONSTRAINT fk_stock_movement_business FOREIGN KEY (business_id) REFERENCES business (id),
    -- FK COMPOSITE vers le stock du produit (même entreprise ; stock initialisé).
    CONSTRAINT fk_stock_movement_stock FOREIGN KEY (business_id, product_id)
        REFERENCES product_stock (business_id, product_id),
    CONSTRAINT fk_stock_movement_author FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_stock_movement_type CHECK (movement_type IN
        ('INITIAL', 'ENTRY', 'EXIT', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0),
    -- Cohérence arithmétique garantie par la base : après = avant ± quantité selon le type.
    CONSTRAINT ck_stock_movement_balance CHECK (
        (movement_type IN ('INITIAL', 'ENTRY', 'ADJUSTMENT_IN') AND quantity_after = quantity_before + quantity)
        OR (movement_type IN ('EXIT', 'ADJUSTMENT_OUT') AND quantity_after = quantity_before - quantity)),
    CONSTRAINT ck_stock_movement_initial CHECK (movement_type <> 'INITIAL' OR quantity_before = 0),
    CONSTRAINT ck_stock_movement_reason CHECK (reason IS NULL OR length(btrim(reason)) > 0),
    -- Motif obligatoire pour les entrées, sorties et ajustements.
    CONSTRAINT ck_stock_movement_reason_required CHECK (movement_type = 'INITIAL' OR reason IS NOT NULL),
    CONSTRAINT ck_stock_movement_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_stock_movement_business_product_created ON stock_movement (business_id, product_id, created_at DESC);
CREATE INDEX ix_stock_movement_business_created ON stock_movement (business_id, created_at DESC);

-- Insertion seule, quel que soit le rôle : modification et suppression refusées.
CREATE FUNCTION stock_movement_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'stock_movement est en insertion seule' USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tr_stock_movement_insert_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_reject_change();

-- --- Row Level Security ------------------------------------------------------------------------------
ALTER TABLE product_stock ENABLE ROW LEVEL SECURITY;
CREATE POLICY product_stock_tenant_select ON product_stock FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY product_stock_tenant_insert ON product_stock FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY product_stock_tenant_update ON product_stock FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- Mouvements : lecture et insertion uniquement (aucune politique UPDATE ni DELETE).
ALTER TABLE stock_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY stock_movement_tenant_select ON stock_movement FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY stock_movement_tenant_insert ON stock_movement FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- --- Journal d'audit : actions du stock et des paramètres --------------------------------------------
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_action;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_action CHECK (action IN (
    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
    'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED',
    'CATEGORY_CREATED', 'CATEGORY_UPDATED', 'CATEGORY_ARCHIVED',
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_ARCHIVED',
    'STOCK_INITIALIZED', 'STOCK_ENTRY_RECORDED', 'STOCK_EXIT_RECORDED', 'STOCK_ADJUSTED',
    'STOCK_MINIMUM_CHANGED', 'SETTINGS_CHANGED'));

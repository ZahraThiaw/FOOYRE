-- =====================================================================================
-- FOOYRE — MVP de démonstration : ventes, coût moyen pondéré (CMP), paramètres fiscaux et de
-- remise, consentement IA, suivi d'utilisation de l'IA. Aucune donnée existante modifiée.
-- =====================================================================================

-- --- 1. Paramètres de l'entreprise (D4, D14/O11, D15) ----------------------------------------
ALTER TABLE business
    ADD COLUMN vat_enabled                BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN vat_rate                   NUMERIC(5, 2) NOT NULL DEFAULT 18.00,
    ADD COLUMN price_entry_mode           VARCHAR(3)    NOT NULL DEFAULT 'TTC',
    ADD COLUMN employee_max_discount_rate NUMERIC(5, 2) NOT NULL DEFAULT 0,
    ADD COLUMN ai_enabled                 BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN ai_consented_at            TIMESTAMPTZ,
    ADD COLUMN ai_consented_by            UUID,
    ADD COLUMN ai_terms_version           VARCHAR(20),
    ADD CONSTRAINT ck_business_vat_rate CHECK (vat_rate >= 0 AND vat_rate <= 100),
    ADD CONSTRAINT ck_business_price_entry_mode CHECK (price_entry_mode IN ('HT', 'TTC')),
    ADD CONSTRAINT ck_business_employee_discount CHECK (employee_max_discount_rate >= 0 AND employee_max_discount_rate <= 100),
    ADD CONSTRAINT fk_business_ai_consented_by FOREIGN KEY (ai_consented_by) REFERENCES app_user (id),
    ADD CONSTRAINT ck_business_ai_consent CHECK (NOT ai_enabled OR (ai_consented_at IS NOT NULL AND ai_consented_by IS NOT NULL));

-- --- 2. Coût moyen pondéré (D1) ----------------------------------------------------------------
-- NULL = coût INCONNU (reprise : tous les stocks existants, aucun coût historique fiable). Jamais
-- déduit du prix d'achat de référence du produit.
ALTER TABLE product_stock ADD COLUMN average_cost NUMERIC(19, 4);
ALTER TABLE product_stock ADD CONSTRAINT ck_product_stock_average_cost CHECK (average_cost IS NULL OR average_cost >= 0);

-- Sortie de stock d'une vente confirmée (même journal, mêmes verrous que le module Stock).
ALTER TABLE stock_movement DROP CONSTRAINT ck_stock_movement_type;
ALTER TABLE stock_movement ADD CONSTRAINT ck_stock_movement_type CHECK (movement_type IN
    ('INITIAL', 'ENTRY', 'EXIT', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'SALE_OUT'));
ALTER TABLE stock_movement DROP CONSTRAINT ck_stock_movement_balance;
ALTER TABLE stock_movement ADD CONSTRAINT ck_stock_movement_balance CHECK (
    (movement_type IN ('INITIAL', 'ENTRY', 'ADJUSTMENT_IN') AND quantity_after = quantity_before + quantity)
    OR (movement_type IN ('EXIT', 'ADJUSTMENT_OUT', 'SALE_OUT') AND quantity_after = quantity_before - quantity));

-- --- 3. Numérotation par entreprise (verrouillée à l'attribution) ------------------------------
CREATE TABLE business_sequence (
    business_id UUID        NOT NULL,
    name        VARCHAR(30) NOT NULL,
    next_value  BIGINT      NOT NULL,
    CONSTRAINT pk_business_sequence PRIMARY KEY (business_id, name),
    CONSTRAINT fk_business_sequence_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT ck_business_sequence_next CHECK (next_value >= 1)
);

-- --- 4. Ventes ---------------------------------------------------------------------------------
CREATE TABLE sale (
    id                      UUID           NOT NULL,
    business_id             UUID           NOT NULL,
    number                  BIGINT,
    status                  VARCHAR(20)    NOT NULL,
    customer_id             UUID,
    currency                CHAR(3)        NOT NULL,
    global_discount         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    -- Figés à la confirmation (D4) :
    vat_enabled             BOOLEAN,
    vat_rate                NUMERIC(5, 2),
    price_entry_mode        VARCHAR(3),
    gross_amount            NUMERIC(19, 2),
    discount_amount         NUMERIC(19, 2),
    total_excl_tax          NUMERIC(19, 2),
    tax_amount              NUMERIC(19, 2),
    total_incl_tax          NUMERIC(19, 2),
    created_by              UUID           NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL,
    updated_at              TIMESTAMPTZ    NOT NULL,
    confirmed_by            UUID,
    confirmed_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    version                 INTEGER        NOT NULL DEFAULT 0,
    idempotency_key         UUID,
    request_hash            CHAR(64),
    confirm_idempotency_key UUID,
    CONSTRAINT pk_sale PRIMARY KEY (id),
    CONSTRAINT uq_sale_business_id_id UNIQUE (business_id, id),
    CONSTRAINT uq_sale_business_number UNIQUE (business_id, number),
    CONSTRAINT fk_sale_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT fk_sale_customer FOREIGN KEY (business_id, customer_id) REFERENCES customer (business_id, id),
    CONSTRAINT fk_sale_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_sale_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES app_user (id),
    CONSTRAINT ck_sale_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_sale_global_discount CHECK (global_discount >= 0),
    CONSTRAINT ck_sale_confirmed CHECK (status <> 'CONFIRMED' OR (number IS NOT NULL AND confirmed_at IS NOT NULL
        AND confirmed_by IS NOT NULL AND total_incl_tax IS NOT NULL AND vat_enabled IS NOT NULL)),
    CONSTRAINT ck_sale_draft_number CHECK (status = 'CONFIRMED' OR number IS NULL),
    CONSTRAINT ck_sale_totals CHECK (total_incl_tax IS NULL OR (total_incl_tax >= 0 AND tax_amount >= 0
        AND total_incl_tax = total_excl_tax + tax_amount)),
    CONSTRAINT ck_sale_idempotency CHECK ((idempotency_key IS NULL) = (request_hash IS NULL))
);
CREATE UNIQUE INDEX ux_sale_business_idempotency ON sale (business_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX ux_sale_business_confirm_idempotency ON sale (business_id, confirm_idempotency_key)
    WHERE confirm_idempotency_key IS NOT NULL;
CREATE INDEX ix_sale_business_status_confirmed ON sale (business_id, status, confirmed_at DESC);
CREATE INDEX ix_sale_business_created ON sale (business_id, created_at DESC);
CREATE INDEX ix_sale_business_customer ON sale (business_id, customer_id, confirmed_at DESC);

CREATE TABLE sale_line (
    id               UUID           NOT NULL,
    business_id      UUID           NOT NULL,
    sale_id          UUID           NOT NULL,
    position         INTEGER        NOT NULL,
    product_id       UUID           NOT NULL,
    quantity         NUMERIC(19, 3) NOT NULL,
    line_discount    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    -- Photographie historique figée à la confirmation (jamais réécrite) :
    product_name     VARCHAR(150),
    product_sku      VARCHAR(64),
    unit             VARCHAR(20),
    unit_price       NUMERIC(19, 2),
    gross_amount     NUMERIC(19, 2),
    global_discount_share NUMERIC(19, 2),
    total_excl_tax   NUMERIC(19, 2),
    tax_amount       NUMERIC(19, 2),
    total_incl_tax   NUMERIC(19, 2),
    -- CMP au moment de la vente ; NULL = coût inconnu (marge non calculable).
    unit_cost        NUMERIC(19, 4),
    stock_movement_id UUID,
    CONSTRAINT pk_sale_line PRIMARY KEY (id),
    CONSTRAINT uq_sale_line_business_id_id UNIQUE (business_id, id),
    CONSTRAINT uq_sale_line_position UNIQUE (sale_id, position),
    CONSTRAINT fk_sale_line_sale FOREIGN KEY (business_id, sale_id) REFERENCES sale (business_id, id),
    CONSTRAINT fk_sale_line_product FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id),
    CONSTRAINT fk_sale_line_movement FOREIGN KEY (business_id, stock_movement_id) REFERENCES stock_movement (business_id, id),
    CONSTRAINT ck_sale_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_line_discount CHECK (line_discount >= 0),
    CONSTRAINT ck_sale_line_cost CHECK (unit_cost IS NULL OR unit_cost >= 0)
);
CREATE INDEX ix_sale_line_business_sale ON sale_line (business_id, sale_id);
CREATE INDEX ix_sale_line_business_product ON sale_line (business_id, product_id);

-- --- 5. Utilisation de l'IA (jetons, coût estimé, latence, statut — CLAUDE.md §9) ---------------
CREATE TABLE ai_usage (
    id                 UUID          NOT NULL,
    business_id        UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    model              VARCHAR(60)   NOT NULL,
    status             VARCHAR(30)   NOT NULL,
    input_tokens       BIGINT        NOT NULL DEFAULT 0,
    output_tokens      BIGINT        NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0,
    latency_ms         BIGINT        NOT NULL DEFAULT 0,
    stop_reason        VARCHAR(30),
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_ai_usage PRIMARY KEY (id),
    CONSTRAINT uq_ai_usage_business_id_id UNIQUE (business_id, id),
    CONSTRAINT fk_ai_usage_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT fk_ai_usage_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_ai_usage_business_created ON ai_usage (business_id, created_at);

-- --- 6. Row Level Security : une politique par commande, aucune pour DELETE ----------------------
ALTER TABLE business_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY business_sequence_tenant_select ON business_sequence FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY business_sequence_tenant_insert ON business_sequence FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY business_sequence_tenant_update ON business_sequence FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

ALTER TABLE sale ENABLE ROW LEVEL SECURITY;
CREATE POLICY sale_tenant_select ON sale FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY sale_tenant_insert ON sale FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY sale_tenant_update ON sale FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- Lignes : modifiables tant que la vente est un brouillon (contrôle applicatif), supprimables
-- uniquement pour remplacer les lignes d'un BROUILLON (politique DELETE limitée aux brouillons).
ALTER TABLE sale_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY sale_line_tenant_select ON sale_line FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY sale_line_tenant_insert ON sale_line FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY sale_line_tenant_update ON sale_line FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY sale_line_tenant_delete_draft ON sale_line FOR DELETE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid
        AND EXISTS (SELECT 1 FROM sale s WHERE s.id = sale_line.sale_id AND s.business_id = sale_line.business_id
                    AND s.status = 'DRAFT'));

ALTER TABLE ai_usage ENABLE ROW LEVEL SECURITY;
CREATE POLICY ai_usage_tenant_select ON ai_usage FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY ai_usage_tenant_insert ON ai_usage FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- Une vente confirmée et ses lignes ne sont plus modifiables (immutabilité garantie par la base).
CREATE FUNCTION sale_line_reject_confirmed_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM sale s WHERE s.id = COALESCE(OLD.sale_id, NEW.sale_id) AND s.status <> 'DRAFT') THEN
        RAISE EXCEPTION 'ligne d''une vente confirmée : modification interdite';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER sale_line_reject_confirmed_change BEFORE UPDATE OR DELETE ON sale_line
    FOR EACH ROW EXECUTE FUNCTION sale_line_reject_confirmed_change();

-- --- 7. Journal d'audit ---------------------------------------------------------------------------
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_action;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_action CHECK (action IN (
    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
    'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED',
    'CATEGORY_CREATED', 'CATEGORY_UPDATED', 'CATEGORY_ARCHIVED',
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_ARCHIVED',
    'STOCK_INITIALIZED', 'STOCK_ENTRY_RECORDED', 'STOCK_EXIT_RECORDED', 'STOCK_ADJUSTED',
    'STOCK_MINIMUM_CHANGED', 'SETTINGS_CHANGED', 'STOCK_EXIT_REJECTED_INSUFFICIENT',
    'CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED',
    'SALE_DRAFT_SAVED', 'SALE_CONFIRMED', 'SALE_DRAFT_CANCELLED', 'DISCOUNT_APPLIED', 'DISCOUNT_LIMIT_EXCEEDED',
    'SALES_POLICY_CHANGED', 'AI_CONSENT_CHANGED', 'AI_QUERY'));

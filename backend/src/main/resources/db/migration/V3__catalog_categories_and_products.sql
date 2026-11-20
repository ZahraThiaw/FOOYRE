-- =====================================================================================
-- FOOYRE — MVP-2 : catalogue (catégories et produits) — module gestion.
-- Modèle d'isolation obligatoire (docs/architecture.md §5.2) : business_id NOT NULL + FK,
-- UNIQUE (business_id, id), FK composites, index commençant par business_id, RLS par commande.
-- Aucune politique DELETE : aucune suppression physique possible pour le rôle applicatif
-- (archivage uniquement).
-- =====================================================================================

-- --- Catégories ------------------------------------------------------------------------------
CREATE TABLE category (
    id          UUID         NOT NULL,
    business_id UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uq_category_business_id_id UNIQUE (business_id, id),
    CONSTRAINT fk_category_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_category_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    -- Nom normalisé par l'application : sans espace superflu, jamais vide.
    CONSTRAINT ck_category_name CHECK (length(name) > 0 AND name = btrim(name)),
    CONSTRAINT ck_category_description CHECK (description IS NULL OR length(btrim(description)) > 0)
);

-- Nom unique (sans tenir compte de la casse) parmi les catégories ACTIVES d'une entreprise.
CREATE UNIQUE INDEX ux_category_business_active_name ON category (business_id, lower(name)) WHERE status = 'ACTIVE';
CREATE INDEX ix_category_business_status_name ON category (business_id, status, lower(name));

-- --- Produits --------------------------------------------------------------------------------
CREATE TABLE product (
    id             UUID          NOT NULL,
    business_id    UUID          NOT NULL,
    category_id    UUID,
    name           VARCHAR(150)  NOT NULL,
    description    VARCHAR(1000),
    sku            VARCHAR(64),
    barcode        VARCHAR(64),
    unit           VARCHAR(20)   NOT NULL,
    -- Prix d'achat de RÉFÉRENCE saisi par l'utilisatrice (coût unitaire, NUMERIC(19,4)).
    -- Ce n'est PAS le coût moyen pondéré (D1), qui sera géré par le module de stock.
    purchase_price NUMERIC(19, 4),
    -- Prix de vente, à l'échelle de la devise de l'entreprise.
    sale_price     NUMERIC(19, 2) NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    version        INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uq_product_business_id_id UNIQUE (business_id, id),
    CONSTRAINT fk_product_business FOREIGN KEY (business_id) REFERENCES business (id),
    -- FK COMPOSITE : un produit ne peut référencer qu'une catégorie de SA propre entreprise.
    CONSTRAINT fk_product_category FOREIGN KEY (business_id, category_id) REFERENCES category (business_id, id),
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_product_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT ck_product_name CHECK (length(name) > 0 AND name = btrim(name)),
    CONSTRAINT ck_product_description CHECK (description IS NULL OR length(btrim(description)) > 0),
    CONSTRAINT ck_product_sku CHECK (sku IS NULL OR (length(sku) > 0 AND sku = btrim(sku))),
    CONSTRAINT ck_product_barcode CHECK (barcode IS NULL OR barcode ~ '^[A-Za-z0-9-]{1,64}$'),
    CONSTRAINT ck_product_unit CHECK (unit IN ('PIECE', 'UNIT', 'KILOGRAM', 'GRAM', 'LITRE', 'MILLILITRE',
                                               'METRE', 'PACK', 'CARTON', 'LOT')),
    CONSTRAINT ck_product_purchase_price CHECK (purchase_price IS NULL OR purchase_price >= 0),
    CONSTRAINT ck_product_sale_price CHECK (sale_price >= 0)
);

-- SKU (sans tenir compte de la casse) et code-barres uniques parmi les produits ACTIFS d'une entreprise.
CREATE UNIQUE INDEX ux_product_business_active_sku ON product (business_id, lower(sku))
    WHERE sku IS NOT NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX ux_product_business_active_barcode ON product (business_id, barcode)
    WHERE barcode IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX ix_product_business_status_name ON product (business_id, status, lower(name));
CREATE INDEX ix_product_business_category ON product (business_id, category_id);
CREATE INDEX ix_product_business_created ON product (business_id, created_at);
CREATE INDEX ix_product_business_sale_price ON product (business_id, sale_price);

-- --- Row Level Security : une politique par commande, aucune pour DELETE ----------------------
ALTER TABLE category ENABLE ROW LEVEL SECURITY;
CREATE POLICY category_tenant_select ON category FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY category_tenant_insert ON category FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY category_tenant_update ON category FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
CREATE POLICY product_tenant_select ON product FOR SELECT
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY product_tenant_insert ON product FOR INSERT
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);
CREATE POLICY product_tenant_update ON product FOR UPDATE
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

-- --- Journal d'audit : actions du catalogue -------------------------------------------------
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_action;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_action CHECK (action IN (
    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGIN_RATE_LIMITED', 'LOGOUT', 'SESSION_REVOKED',
    'TOKEN_REUSE_DETECTED', 'ACCOUNT_CREATED', 'BUSINESS_CREATED',
    'CATEGORY_CREATED', 'CATEGORY_UPDATED', 'CATEGORY_ARCHIVED',
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_ARCHIVED'));

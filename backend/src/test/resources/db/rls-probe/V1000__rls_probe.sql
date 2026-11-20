-- RÉSERVÉ AUX TESTS (RowLevelSecurityIntegrationTest) : table « sonde » qui applique EXACTEMENT
-- le modèle imposé aux futures tables métier (docs/architecture.md, « Isolation par entreprise ») :
-- business_id NOT NULL + FK, UNIQUE (business_id, id), index, RLS activée avec USING et WITH CHECK.
CREATE TABLE rls_probe (
    id          UUID        NOT NULL,
    business_id UUID        NOT NULL,
    label       VARCHAR(50) NOT NULL,
    CONSTRAINT pk_rls_probe PRIMARY KEY (id),
    CONSTRAINT uq_rls_probe_business_id_id UNIQUE (business_id, id),
    CONSTRAINT fk_rls_probe_business FOREIGN KEY (business_id) REFERENCES business (id)
);

ALTER TABLE rls_probe ENABLE ROW LEVEL SECURITY;

CREATE POLICY rls_probe_tenant_isolation ON rls_probe
    USING (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = NULLIF(current_setting('app.business_id', true), '')::uuid);

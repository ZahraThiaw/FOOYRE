-- =====================================================================================
-- FOOYRE — MVP-1 : comptes, entreprises, appartenances, sessions (refresh tokens).
-- Référence : docs/database-schema.md §3.1, docs/architecture.md ADR-04 / ADR-05.
-- Exécutée par le rôle propriétaire du schéma (Flyway) ; le rôle applicatif reçoit les droits
-- sur les données via les privilèges par défaut (infra/postgres/init/01-roles.sh).
-- Aucune donnée n'est insérée ici (le compte de démonstration est créé par l'application,
-- profil dev uniquement).
-- =====================================================================================

-- --- Compte utilisateur (global, non rattaché à une entreprise) ----------------------
CREATE TABLE app_user (
    id               UUID         NOT NULL,
    email            VARCHAR(254) NOT NULL,
    password_hash    VARCHAR(255) NOT NULL,
    full_name        VARCHAR(150) NOT NULL,
    locale           VARCHAR(10)  NOT NULL DEFAULT 'fr',
    platform_admin   BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    security_version INTEGER      NOT NULL DEFAULT 0,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_app_user_locale CHECK (locale IN ('fr')),
    CONSTRAINT ck_app_user_email_not_blank CHECK (length(trim(email)) > 0),
    CONSTRAINT ck_app_user_full_name_not_blank CHECK (length(trim(full_name)) > 0),
    CONSTRAINT ck_app_user_security_version CHECK (security_version >= 0)
);

-- Adresse e-mail unique, insensible à la casse (sans extension citext : D19).
CREATE UNIQUE INDEX ux_app_user_email_lower ON app_user (lower(email));

-- --- Entreprise (le tenant) ---------------------------------------------------------------
CREATE TABLE business (
    id             UUID         NOT NULL,
    name           VARCHAR(150) NOT NULL,
    legal_name     VARCHAR(200),
    currency       CHAR(3)      NOT NULL DEFAULT 'XOF',
    timezone       VARCHAR(50)  NOT NULL DEFAULT 'Africa/Dakar',
    default_locale VARCHAR(10)  NOT NULL DEFAULT 'fr',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_business PRIMARY KEY (id),
    CONSTRAINT ck_business_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_business_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_business_default_locale CHECK (default_locale IN ('fr')),
    CONSTRAINT ck_business_name_not_blank CHECK (length(trim(name)) > 0)
);

-- --- Appartenance utilisateur ↔ entreprise, avec le rôle dans cette entreprise (ADR-05) ----
CREATE TABLE business_membership (
    id          UUID        NOT NULL,
    business_id UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    role        VARCHAR(30) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_business_membership PRIMARY KEY (id),
    CONSTRAINT uq_business_membership_business_id_id UNIQUE (business_id, id),
    CONSTRAINT uq_business_membership_business_user UNIQUE (business_id, user_id),
    CONSTRAINT fk_business_membership_business FOREIGN KEY (business_id) REFERENCES business (id),
    CONSTRAINT fk_business_membership_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_business_membership_role
        CHECK (role IN ('BUSINESS_OWNER', 'BUSINESS_MANAGER', 'EMPLOYEE')),
    CONSTRAINT ck_business_membership_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_business_membership_revocation
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- Appartenances d'un utilisateur (connexion, /me).
CREATE INDEX ix_business_membership_user_status ON business_membership (user_id, status);

-- --- Refresh tokens (ADR-04) : un enregistrement par jeton émis, jamais le jeton en clair ----
CREATE TABLE refresh_token (
    id                UUID        NOT NULL,
    user_id           UUID        NOT NULL,
    -- Entreprise active de la session (NULL si aucune entreprise active).
    business_id       UUID,
    membership_id     UUID,
    -- Identifiant de session (claim « sid ») = famille de jetons issus de la même connexion.
    family_id         UUID        NOT NULL,
    -- SHA-256 du jeton, en hexadécimal.
    token_hash        CHAR(64)    NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    replaced_by       UUID,
    replaced_at       TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revocation_reason VARCHAR(30),
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_refresh_token_business FOREIGN KEY (business_id) REFERENCES business (id),
    -- FK composite : l'appartenance appartient forcément à l'entreprise de la session.
    CONSTRAINT fk_refresh_token_membership FOREIGN KEY (business_id, membership_id)
        REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_refresh_token_replaced_by FOREIGN KEY (replaced_by) REFERENCES refresh_token (id),
    CONSTRAINT ck_refresh_token_business_context
        CHECK ((business_id IS NULL) = (membership_id IS NULL)),
    CONSTRAINT ck_refresh_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_token_revocation
        CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT ck_refresh_token_revocation_reason CHECK (revocation_reason IS NULL OR revocation_reason IN (
        'LOGOUT', 'LOGOUT_ALL', 'PASSWORD_CHANGED', 'REUSE_DETECTED', 'MEMBERSHIP_REVOKED',
        'ACCOUNT_DISABLED', 'BUSINESS_SUSPENDED')),
    CONSTRAINT ck_refresh_token_replacement CHECK ((replaced_by IS NULL) = (replaced_at IS NULL)),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_token_user_revoked ON refresh_token (user_id, revoked_at);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);

-- Migration de TEST uniquement (src/test) : vérifie le mécanisme Flyway sur PostgreSQL 18.
-- Elle n'est jamais livrée et ne s'applique que dans un schéma de test dédié.
CREATE TABLE flyway_mechanism_check (
    id     UUID PRIMARY KEY,
    amount NUMERIC(19, 2) NOT NULL
);

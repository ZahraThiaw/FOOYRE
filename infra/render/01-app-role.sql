-- FOOYRE — rôle applicatif sur Render Postgres (à exécuter UNE FOIS, manuellement, par l'utilisatrice).
--
-- Pourquoi : sur Render, l'utilisateur par défaut de la base en est le PROPRIÉTAIRE. Il sert aux
-- migrations Flyway. Un propriétaire contourne la Row Level Security (activée par ENABLE, sans
-- FORCE) : l'application doit donc se connecter avec un rôle DISTINCT, non propriétaire, sans
-- BYPASSRLS — exactement comme en local (infra/postgres/init/01-roles.sh).
--
-- Exécution (depuis un poste autorisé, avec l'URL EXTERNE de la base, connecté en utilisateur par
-- défaut) — le mot de passe est passé en variable, jamais écrit dans ce fichier ni dans Git :
--   psql "<External Database URL>" -v ON_ERROR_STOP=1 \
--        -v app=fooyre_app -v app_password='<mot de passe fort généré>' \
--        -f infra/render/01-app-role.sql
--
-- Rejouable : la création du rôle est ignorée s'il existe déjà (son mot de passe est alors
-- redéfini). Fonctionne avant OU après la première migration (droits sur les tables existantes et
-- droits par défaut sur les tables futures).

SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20', :'app')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app') \gexec

-- Mot de passe uniquement : depuis PostgreSQL 16, un rôle non superutilisateur (cas de Render) ne
-- peut pas modifier les attributs SUPERUSER / REPLICATION / BYPASSRLS, même pour les retirer. Ils
-- sont fixés à la création ci-dessus et contrôlés par la requête finale.
ALTER ROLE :"app" WITH LOGIN PASSWORD :'app_password';

-- Connexion à cette base uniquement.
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app') \gexec

-- Schéma public : seul le propriétaire crée des objets ; le rôle applicatif ne fait qu'utiliser.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app";

-- Tables et séquences DÉJÀ créées (si les migrations ont déjà tourné) : accès aux données seulement.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"app";
-- Tables et séquences FUTURES créées par le propriétaire (utilisateur courant = utilisateur par défaut).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app";

-- Contrôle : le rôle applicatif ne doit être ni superutilisateur, ni BYPASSRLS, ni propriétaire de la base.
SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolcreaterole,
       (SELECT d.datdba = r.oid FROM pg_database d WHERE d.datname = current_database()) AS is_database_owner
FROM pg_roles r WHERE r.rolname = :'app';

#!/bin/sh
# FOOYRE — initialisation PostgreSQL (exécutée UNE SEULE FOIS, au premier démarrage, volume vide).
#
# Rôles (docs/architecture.md §5.3) :
#   - superutilisateur de l'image ($POSTGRES_USER) : administration uniquement, jamais utilisé par l'application ;
#   - $FOOYRE_OWNER_USER : propriétaire de la base et des objets -> migrations Flyway ;
#   - $FOOYRE_APP_USER   : rôle applicatif SANS privilège d'administration ni de modification du schéma :
#                          lecture/écriture des données des tables créées par le propriétaire uniquement.
# Les mots de passe sont transmis à psql par variables (jamais écrits dans ce fichier ni affichés).
set -eu

: "${FOOYRE_DB_NAME:?FOOYRE_DB_NAME manquant}"
: "${FOOYRE_OWNER_USER:?FOOYRE_OWNER_USER manquant}"
: "${FOOYRE_OWNER_PASSWORD:?FOOYRE_OWNER_PASSWORD manquant}"
: "${FOOYRE_APP_USER:?FOOYRE_APP_USER manquant}"
: "${FOOYRE_APP_PASSWORD:?FOOYRE_APP_PASSWORD manquant}"

psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname postgres \
  -v db="$FOOYRE_DB_NAME" \
  -v owner="$FOOYRE_OWNER_USER" -v owner_password="$FOOYRE_OWNER_PASSWORD" \
  -v app="$FOOYRE_APP_USER" -v app_password="$FOOYRE_APP_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE :"app" LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20;

CREATE DATABASE :"db" OWNER :"owner";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"app";

\connect :"db"
-- Schéma public : seul le propriétaire de la base peut y créer des objets.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app";
-- Tables et séquences créées plus tard par le propriétaire (migrations) : accès aux données seulement.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"app";
SQL

echo "FOOYRE : base et rôles initialisés (propriétaire et applicatif)."

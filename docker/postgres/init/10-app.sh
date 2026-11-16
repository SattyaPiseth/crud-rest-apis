#!/usr/bin/env bash
set -euo pipefail

export PGPASSWORD="$(cat /run/secrets/postgres_password)"
export APP_PASSWORD="$(cat /run/secrets/app_password)"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_password APP_PASSWORD
CREATE ROLE crud_owner NOLOGIN;
CREATE ROLE crud_app LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE ALL ON DATABASE crud_rest_apis FROM PUBLIC;
GRANT CONNECT ON DATABASE crud_rest_apis TO crud_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA app AUTHORIZATION crud_owner;
GRANT USAGE ON SCHEMA app TO crud_app;
ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO crud_app;
ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app
    GRANT USAGE, SELECT ON SEQUENCES TO crud_app;
ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER ROLE crud_app IN DATABASE crud_rest_apis SET search_path = app;
ALTER ROLE crud_app SET statement_timeout = '30s';
ALTER ROLE crud_app SET lock_timeout = '5s';
ALTER ROLE crud_app SET idle_in_transaction_session_timeout = '60s';
SQL
unset APP_PASSWORD PGPASSWORD

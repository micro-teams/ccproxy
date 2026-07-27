-- Runs once on first Postgres init (docker-entrypoint-initdb.d): create the schema the backend
-- owns. Hibernate (ddl-auto=update) then creates its tables + sequences inside it on first boot.
CREATE SCHEMA IF NOT EXISTS ccproxy;

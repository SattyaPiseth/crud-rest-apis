-- Migration metadata is read and written by the separately provisioned migration role.
REVOKE ALL ON TABLE app.flyway_schema_history FROM crud_app;

-- Runtime tenant context is set by the application inside each transaction.
-- Missing context makes the policy expression NULL, which denies access.
DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'recipient',
        'notification',
        'notification_recipient',
        'delivery',
        'notification_preference',
        'outbox_event',
        'ingestion_request',
        'processed_event'
    ] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY app_tenant_isolation ON app.%I USING '
            || '(tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END
$$;

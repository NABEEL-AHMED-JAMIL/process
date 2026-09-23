-- Removes source_task_type.is_schema_register / schema_payload -- confirmed (grep across the
-- whole backend) to be pure display metadata: no schema-registry client (KafkaAvroSerializer,
-- schema.registry.url, ...) exists anywhere in this codebase, and is_schema_register was never
-- an independent flag -- SettingServiceImpl computed it as "!isNull(schemaPayload)" on every
-- save. Removing both has zero effect on message publishing or topic provisioning.
--
-- Also renames kafka_connection_profile.connection_active to is_default (same column, new
-- name/semantics -- see KafkaConnectionProfile's own javadoc): whichever single profile was
-- already the global "active" one becomes that row's is_default=true, with tenant_id still
-- null on every pre-existing row (added separately by Hibernate's ddl-auto=update, nullable),
-- so it becomes exactly the platform-wide shared default -- the same cluster the whole app was
-- already publishing to before this feature existed. No manual data backfill needed.
--
-- Every column this Kafka dynamic-configuration feature ADDS (tenant_id/environment_label/
-- ssl_*/additional_properties/connection_status/last_tested_at/last_test_message on
-- kafka_connection_profile, tenant_id/kafka_connection_profile_id on source_task_type, and the
-- new tenant_task_type_kafka_route table) is nullable/additive and is created automatically by
-- Hibernate's ddl-auto=update from the updated @Entity mappings -- this migration only handles
-- the destructive/rename steps ddl-auto=update can't do on its own (it never drops or renames
-- a column).
--
-- Guarded on each table existing: on a brand-new database this migration runs before Hibernate
-- (ddl-auto=update) has ever created source_task_type/kafka_connection_profile, so without the
-- guard this whole changeset fails outright (same reasoning as V8__add_is_encrypted_column.sql).
DO $$
BEGIN
    IF to_regclass('public.source_task_type') IS NOT NULL THEN
        ALTER TABLE source_task_type DROP COLUMN IF EXISTS is_schema_register;
        ALTER TABLE source_task_type DROP COLUMN IF EXISTS schema_payload;
    END IF;

    IF to_regclass('public.kafka_connection_profile') IS NOT NULL
        AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'kafka_connection_profile' AND column_name = 'connection_active') THEN
        ALTER TABLE kafka_connection_profile RENAME COLUMN connection_active TO is_default;
    END IF;
END $$;

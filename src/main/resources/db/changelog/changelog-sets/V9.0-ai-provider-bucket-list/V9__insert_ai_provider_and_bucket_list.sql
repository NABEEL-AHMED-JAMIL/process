-- Seeds the AI_PROVIDER lookup (parent + OpenAI/Anthropic/Ollama children, consumed by the
-- AI Agent screen's provider dropdown) and the BUCKET_LIST lookup (parent + the 3 buckets
-- Object Browser was configured with, consumed by StorageBrowserServiceImpl#listBuckets).
-- IDs 1020+ deliberately avoid V2__insert_lookup_setting.sql's hardcoded range (1001-1019).
--
-- Note: this restores the *lookup config* pointing at those buckets -- it does not restore
-- the MinIO server or the files that were in them, which live in a separate project/volume.

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1020, NOW(), 'Selectable AI providers for AI Agents -- add child entries here for each provider.', 'AI_PROVIDER', 'AI Provider', NULL);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1021, NOW(), 'Built-in -- calls the OpenAI chat completions API directly.', 'OpenAI', 'OpenAI', 1020);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1022, NOW(), 'Built-in -- calls the Anthropic Messages API directly.', 'Anthropic', 'Anthropic', 1020);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1023, NOW(), 'Built-in -- calls a local Ollama container, no API key needed.', 'Ollama', 'Ollama', 1020);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1024, NOW(), 'Configured storage buckets for the Object Browser', 'BUCKET_LIST', 'Bucket List', NULL);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1025, NOW(), 'MINIO', 'ETL Bucket', 'etl-bucket', 1024);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1026, NOW(), 'MINIO', 'Test Bucket', 'test-bucket', 1024);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1027, NOW(), 'MINIO', 'FormCraft', 'from-craft', 1024);

-- Advance the sequence past every hardcoded lookup_id seeded across V2/V3/V9 (raw INSERTs
-- never touch the sequence) so the next JPA-generated lookup_id (Settings > Lookup in the UI)
-- doesn't collide with one of these fixed IDs.
SELECT setval('lookup_id_seq', (SELECT MAX(lookup_id) FROM public.lookup_data), true);

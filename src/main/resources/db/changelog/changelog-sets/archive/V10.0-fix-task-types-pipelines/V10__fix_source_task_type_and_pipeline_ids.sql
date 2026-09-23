-- Reconciles source_task_type and the PIPELINE_IDS lookup against the *real* Kafka
-- listeners in the job-search project (etl/tpd/tpd_test_listener.py and
-- etl/tpd/tpd_scrapping_listener.py):
--
--   - tpd_test_listener.py consumes KAFKA_TEST_TOPIC ("test-topic") -- matches the
--     existing source_task_type 1000 ("Test"), which just needed its status set.
--   - tpd_scrapping_listener.py consumes KAFKA_SCRAPPING_TOPIC ("scrapping-topic") as a
--     single whole-topic consumer group and routes internally by the payload's
--     "pipelineId" field to one of 4 real handlers (F768924-F768927) -- it does NOT
--     route by Kafka partition number, so the old per-partition rows (1001/1002/1003)
--     encoded a routing mechanism the real consumer doesn't implement.
--
-- V3__insert_source_task_type.sql seeded 11 rows total:
--   1000 Test                -- topic=test-topic&partitions=[*]      -> REAL, kept
--   1001 Web Scrapping        -- topic=scrapping-topic&partitions=[0] -> wrong mechanism, replaced
--   1002 Data Scrapping       -- topic=scrapping-topic&partitions=[1] -> wrong mechanism, replaced
--   1003 Image Scraping       -- topic=scrapping-topic&partitions=[2] -> wrong mechanism, replaced
--   1004-1006 *Comparison     -- topic=comparison-topic&partitions=[*] -> topic doesn't exist anywhere, deleted
--   1007-1010 Data Extraction/Web Auto Bots/Mobile Auto Bots/Data Statistics Report
--             -- queue_topic_partition doesn't even match ProducerBulkEngine's required
--                "topic=X&partitions=[Y]" regex -- these could never have sent a single
--                real message (always "Broker configuration wrong"), deleted.

DELETE FROM source_task_type WHERE source_task_type_id IN (1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010);

UPDATE source_task_type SET task_type_status = 'Active' WHERE source_task_type_id = 1000;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES (1011,
  '[ETL scrapping pipeline -- routes by pipelineId (see PIPELINE_IDS lookup): F768924 PDF Highlighter Form Fill, F768925 PDF Highlighter Text Extraction, F768926 Hurricanes ETL, F768927 MP3 Noise Processing]',
  'topic=scrapping-topic&partitions=[*]', 'ETL Scrapping Pipeline', 'Active');

-- PIPELINE_IDS (parent lookup_id 1015, from V2) had a single stale child, F768922 (lookup_id
-- 1016) -- tpd_scrapping_listener.py's pipeline router doesn't recognize that ID at all
-- (falls into its "raise ValueError(Unknown pipeline)" branch), so it's replaced with the 4
-- IDs the router actually implements.
DELETE FROM lookup_data WHERE lookup_id = 1016;

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1028, NOW(), 'etl/tasks/pdf_highligter_form_fill_F768924.py', 'F768924', 'F768924', 1015);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1029, NOW(), 'etl/tasks/pdf_highlighter_f768925.py', 'F768925', 'F768925', 1015);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1030, NOW(), 'etl/tasks/etl_hurricanes_f768926.py', 'F768926', 'F768926', 1015);
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES (1031, NOW(), 'etl/tasks/mp3_noise_processing_extract_txt_f768927.py', 'F768927', 'F768927', 1015);

-- Advance the sequence again past this migration's hardcoded IDs.
SELECT setval('lookup_id_seq', (SELECT MAX(lookup_id) FROM public.lookup_data), true);

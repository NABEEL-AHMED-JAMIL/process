INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1001, '2021-03-31 22:09:43.244', 'This Scheduler use for send the sourceJob into the queue', 'SCHEDULER_LAST_RUN_TIME', '2025-09-20T13:15:01.941789200', NULL)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1002, '2021-03-31 23:06:48.744', 'This Queue fetch size use to fetch the limit of data from db', 'QUEUE_FETCH_LIMIT', '5000', NULL)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1015, '2025-09-17 17:29:35.903', 'Pipeline IDs', 'PIPELINE_IDS', 'Pipeline IDs', NULL)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1016, '2025-09-17 17:30:33.846', 'Seal ID For F768922 Project', 'F768922', 'F768922', 1015)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1017, '2025-09-17 17:31:36.728', 'Home Pages', 'PIPELINE_HOME_PAGES', 'Home Pages', NULL)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1018, '2025-09-17 17:32:20.724', 'Home Url For Seal Id', 'F768922 Seal Home', 'http://localhost:8080/', 1017)
ON CONFLICT (lookup_id) DO NOTHING;
INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, parent_lookup_id)
VALUES(1019, '2025-09-17 21:30:23.540', 'Receiver email', 'EMAIL_RECEIVER', 'nabeel.amd93@gmail.com', NULL)
ON CONFLICT (lookup_id) DO NOTHING;

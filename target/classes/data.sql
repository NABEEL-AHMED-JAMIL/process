-- DEFAULT ROLE
INSERT INTO public."role" (role_id, date_created, role_name, status)
VALUES(1000, now(), 'ROLE_MASTER_ADMIN', 1);

INSERT INTO public."role" (role_id, date_created, role_name, status)
VALUES(1001, now(), 'ROLE_ADMIN', 1);

INSERT INTO public."role" (role_id, date_created, role_name, status)
VALUES(1002, now(), 'ROLE_USER', 1);

-- USER
INSERT INTO public.app_users
(app_user_id, date_created, email, "password", status, username, parent_user_id)
VALUES(1000, now(), 'super_admin93@gmail.com', '$2a$10$/tbGib7Iq4kHNw0zyLq6N.a79DLHoBf8EqNFfuNXfTglQRCQT30I6', 1, 'super_admin93', null);

INSERT INTO public.app_user_roles
(app_user_id, role_id)
VALUES(1000, 1000);

-- DEFAULT LOOKUP CREATE BY MASTER ADMIN

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1000, now(), 'QUEUE_FETCH_LIMIT For FETCH QUEUE LIMIT', 'QUEUE_FETCH_LIMIT', '500', 1000, null);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1001, now(), 'SCHEDULER_LAST_RUN_TIME For SCHEDULER LAST RUN TIME', 'SCHEDULER_LAST_RUN_TIME', '2023-03-26 21:37:25.525 +0300', 1000, null);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1002, now(), 'EMAIL_SENDER for send the email', 'EMAIL_SENDER', 'nabeel.amd93@gmail.com', 1000, null);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1004, now(), 'RESET_PASSWORD_LINK for send the email', 'RESET_PASSWORD_LINK', 'http://localhost:8080/forgotpass', 1000, null);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1005, now(), 'Scheduler TimeZone Detail', 'SCHEDULER_TIMEZONE', 'Scheduler TimeZone', 1000, null);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1006, now(), '(GMT+3:00) Asia/Qatar :: Arabia Standard Time', '(GMT+3:00) Asia/Qatar', 'Asia/Qatar', 1000, 1005);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1007, now(), '(GMT+5:00) Asia/Karachi :: Pakistan Time', '(GMT+5:00) Asia/Karachi', 'Asia/Karachi', 1000, 1005);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1008, now(), '(GMT-5:00) America/New_York :: Eastern Standard Time', '(GMT-5:00) America/New_York', 'America/New_York', 1000, 1005);

INSERT INTO public.lookup_data
(lookup_id, date_created, description, lookup_type, lookup_value, app_user_id, parent_lookup_id)
VALUES(1009, now(), 'Super admin username detail', 'SUPER_ADMIN', 'super_admin93', 1000, null);

-- DEFAULT STT CREATE BY MASTER ADMIN
INSERT INTO public.source_task_type
(source_task_type_id, description, service_name, task_type_status, task_type, app_user_id, is_default, date_created)
VALUES(1000, 'Test Loop', 'Test Loop', 1, 3, 1000, true, now());

INSERT INTO public.app_user_stt
(app_user_id, stt_id)
VALUES(1000, 1000);

INSERT INTO public.kafka_task_type
(kafka_task_type_id, num_partitions, topic_name, topic_pattern, source_task_type_id, kafka_tt_status)
VALUES(1000, 3, 'test-topic', 'topic=test-topic&partitions=[*]', 1000, 1);

INSERT INTO public.source_task_type
(source_task_type_id, description, service_name, task_type_status, task_type, app_user_id, is_default, date_created)
VALUES(1001, 'Test Api Loop', 'Test Api Loop', 1, 1, 1000, true, now());

INSERT INTO public.app_user_stt
(app_user_id, stt_id)
VALUES(1000, 1001);

INSERT INTO public.api_task_type
(api_task_type_id, api_url, http_method, source_task_type_id, app_tt_status, api_security_id)
VALUES(1000, 'https://github.com/NABEEL-AHMED-JAMIL/process/tree/process-v1', 2, 1001, 1, null);

INSERT INTO public.stt_form
(sttf_id, date_created, description, sttf_status, sttf_name, app_user_id, is_default)
VALUES(1000, now(), 'Test Form', 1, 'Test Form Loop', 1000, true);

INSERT INTO public.app_user_sttf
(sttf_id, stt_id)
VALUES(1000, 1000);

INSERT INTO public.stt_form
(sttf_id, date_created, description, sttf_status, sttf_name, app_user_id, is_default)
VALUES(1001, now(), 'Test Form V1', 1, 'Test Form Loop  V1', 1000, true);

INSERT INTO public.app_user_sttf
(sttf_id, stt_id)
VALUES(1001, 1001);

INSERT INTO public.stt_section
(stts_id, date_created, description, stts_status, stts_name, stts_order, app_user_id, is_default)
VALUES(1000, now(), 'Section 1', 1, 'Section 3', 1, 1000, true);

INSERT INTO public.stt_section
(stts_id, date_created, description, stts_status, stts_name, stts_order, app_user_id, is_default)
VALUES (1001, now(), 'Section 2', 2, 'Section 4', 1, 1000, true);

INSERT INTO public.stt_section
(stts_id, date_created, description, stts_status, stts_name, stts_order, app_user_id, is_default)
VALUES(1002, now(), 'Section 2.1', 1, 'Section 2.1', 1, 1000, true);

INSERT INTO public.stt_section
(stts_id, date_created, description, stts_status, stts_name, stts_order, app_user_id, is_default)
VALUES(1003, now(), 'Section 3', 1, 'Section 3', 1, 1000, true);

INSERT INTO public.stt_section
(stts_id, date_created, description, stts_status, stts_name, stts_order, app_user_id, is_default)
VALUES (1004, now(), 'Section 4', 2, 'Section 4', 1, 1000, true);

INSERT INTO public.app_user_stts
(sttf_id, stts_id)
VALUES(1000, 1000);

INSERT INTO public.app_user_stts
(sttf_id, stts_id)
VALUES(1000, 1001);

INSERT INTO public.app_user_stts
(sttf_id, stts_id)
VALUES(1001, 1000);




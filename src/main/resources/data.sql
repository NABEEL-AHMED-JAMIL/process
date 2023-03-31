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

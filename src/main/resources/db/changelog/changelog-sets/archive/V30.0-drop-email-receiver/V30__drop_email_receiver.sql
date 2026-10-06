-- The EMAIL_RECEIVER lookup named ONE mailbox that every tenant's job notifications were sent
-- to. Two things were wrong with that. Every workspace's job names and failure messages were
-- delivered to whoever owned that address, on a platform whose own tenants page says "jobs,
-- buckets, tasks and users never cross between them". And the person the job actually belongs
-- to was told nothing, which is the entire purpose of a job notification.
--
-- Notifications now resolve the recipient from the job itself -- source_job.assigned_user_id ->
-- app_user.username, which in this schema IS the address. See
-- process.emailer.EmailMessagesFactory.sendSourceJobEmail. Nothing reads this row any more.
DELETE FROM public.lookup_data WHERE lookup_type = 'EMAIL_RECEIVER';

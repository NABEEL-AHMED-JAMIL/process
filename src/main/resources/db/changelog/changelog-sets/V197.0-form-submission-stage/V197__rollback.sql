-- V197's way back: the column goes; the requests in workflow-service keep their steps.
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS workflow_stage;

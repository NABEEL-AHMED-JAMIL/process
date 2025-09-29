-- DROP FUNCTION public.insert_app_user_env(int4, int4);

CREATE OR REPLACE FUNCTION public.insert_app_user_env(p_app_user_id integer, p_created_by integer)
 RETURNS int
 LANGUAGE plpgsql
AS $function$
DECLARE
    success_indicator int := 0;
BEGIN
    -- Insert into public.app_user_env and get the au_env_id
    INSERT INTO public.app_user_env
    (au_env_id, date_created, status, app_user_id, created_by, env_key_id)
    SELECT
        NEXTVAL('au_env_seq'),
        CURRENT_TIMESTAMP,
        1,
        p_app_user_id,
        p_created_by,
        ev.env_key_id
    FROM
        env_variables ev
    WHERE
        ev.status = 1;

    -- Set success indicator to 1 if the INSERT was successful
    GET DIAGNOSTICS success_indicator = ROW_COUNT;

    RETURN success_indicator;
END;
$function$;

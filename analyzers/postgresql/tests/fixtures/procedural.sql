CREATE FUNCTION public.adjust_order(n integer) RETURNS integer LANGUAGE plpgsql AS $body$
DECLARE
    total integer := 0;
BEGIN
    IF n > 500 THEN
        total := n * 2;
        UPDATE public.orders SET amount = total WHERE id = n;
    ELSIF n = 0 THEN
        RAISE EXCEPTION 'invalid amount';
    ELSE
        total := 1;
    END IF;
    FOR i IN 1..3 LOOP
        total := total + i;
    END LOOP;
    WHILE total < 2000 LOOP
        total := total + 100;
        EXIT WHEN total = 1900;
    END LOOP;
    PERFORM pg_catalog.abs(total);
    EXECUTE 'DELETE FROM ' || quote_ident('unknown_table');
    RETURN total;
EXCEPTION WHEN division_by_zero THEN
    RETURN -1;
END;
$body$;

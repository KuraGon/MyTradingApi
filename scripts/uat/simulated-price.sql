-- Fixture manuelle psql UAT ; jamais incluse dans le changelog Liquibase.
-- Parametres obligatoires : -v pair=XAUEUR -v bid=3000.000000 -v ask=3001.000000
-- Ne cree pas de paire : une seule ligne SIMULATED doit deja exister.
\set ON_ERROR_STOP on

BEGIN READ WRITE;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';

SELECT 1 / CASE WHEN current_database() = 'trading_uat'
                    AND current_schema() = 'public' THEN 1 ELSE 0 END AS uat_guard;
SELECT set_config('mytrading.fixture_pair', :'pair', true),
       set_config('mytrading.fixture_bid', :'bid', true),
       set_config('mytrading.fixture_ask', :'ask', true);

DO $fixture$
DECLARE
    requested_pair text := current_setting('mytrading.fixture_pair');
    requested_bid numeric := current_setting('mytrading.fixture_bid')::numeric;
    requested_ask numeric := current_setting('mytrading.fixture_ask')::numeric;
    before_row public.trading_market_price%ROWTYPE;
    after_row public.trading_market_price%ROWTYPE;
    affected integer;
    quoted_at timestamptz;
BEGIN
    IF current_database() <> 'trading_uat' OR current_schema() <> 'public' THEN
        RAISE EXCEPTION 'Fixture reservee a trading_uat/public';
    END IF;
    IF requested_pair !~ '^(XAU|XAG|XPT|XPD)(EUR|USD)$'
       OR requested_bid::text IN ('NaN', 'Infinity', '-Infinity')
       OR requested_ask::text IN ('NaN', 'Infinity', '-Infinity')
       OR requested_bid <= 0 OR requested_ask < requested_bid
       OR requested_bid <> round(requested_bid, 6)
       OR requested_ask <> round(requested_ask, 6) THEN
        RAISE EXCEPTION 'Paire ou prix invalides (positifs, bid <= ask, au plus 6 decimales)';
    END IF;

    -- STRICT refuse zero ou plusieurs lignes ; le verrou reste jusqu'au COMMIT.
    SELECT * INTO STRICT before_row FROM public.trading_market_price
    WHERE pair = requested_pair FOR UPDATE;
    IF before_row.source IS DISTINCT FROM 'SIMULATED' THEN
        RAISE EXCEPTION 'La cotation cible doit deja etre SIMULATED';
    END IF;
    quoted_at := clock_timestamp();
    UPDATE public.trading_market_price
    SET bid = requested_bid, ask = requested_ask,
        mid = round((requested_bid + requested_ask) / 2, 6),
        price_as_of = quoted_at, updated_at = quoted_at
    WHERE pair = requested_pair;
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN
        RAISE EXCEPTION 'La fixture doit affecter exactement une ligne';
    END IF;

    SELECT * INTO STRICT after_row FROM public.trading_market_price WHERE pair = requested_pair;
    IF after_row.bid IS DISTINCT FROM requested_bid
       OR after_row.ask IS DISTINCT FROM requested_ask
       OR after_row.mid IS DISTINCT FROM round((after_row.bid + after_row.ask) / 2, 6)
       OR after_row.price_as_of IS DISTINCT FROM quoted_at
       OR after_row.updated_at IS DISTINCT FROM quoted_at
       OR (to_jsonb(after_row) - ARRAY['bid','ask','mid','price_as_of','updated_at'])
          IS DISTINCT FROM (to_jsonb(before_row) - ARRAY['bid','ask','mid','price_as_of','updated_at']) THEN
        RAISE EXCEPTION 'Relecture de la fixture non conforme';
    END IF;
    RAISE NOTICE 'Fixture validee : exactement 1 ligne affectee';
END;
$fixture$;

-- Relecture visible avant validation definitive ; toute erreur annule la transaction.
SELECT * FROM public.trading_market_price WHERE pair = current_setting('mytrading.fixture_pair');
COMMIT;

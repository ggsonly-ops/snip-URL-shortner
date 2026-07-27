-- ---------------------------------------------------------------------------
-- Snip: core schema
--
-- Note the primary key is BIGINT, not BIGSERIAL. The application generates the
-- id (Snowflake), deliberately: a database sequence is a single point of
-- coordination, and Base62 of a sequence is enumerable and leaks creation volume.
-- ---------------------------------------------------------------------------

CREATE TABLE links (
    id              BIGINT      PRIMARY KEY,        -- Snowflake id, generated in the app
    short_code      VARCHAR(16) NOT NULL UNIQUE,
    long_url        TEXT        NOT NULL,
    -- SHA-256 hex of the canonical URL, for dedupe. VARCHAR rather than CHAR: the value
    -- is always exactly 64 characters anyway, and CHAR's blank-padding comparison
    -- semantics are a trap for no benefit.
    url_hash        VARCHAR(64) NOT NULL,
    owner_key       VARCHAR(64),                    -- capability token of the creator, NULL = anonymous
    password_hash   VARCHAR(255),                   -- BCrypt, optional protection
    expires_at      TIMESTAMPTZ,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    click_count     BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- short_code already has a unique index from the UNIQUE constraint above; these
-- are the access paths that actually get used by the API.
CREATE INDEX idx_links_owner      ON links (owner_key, created_at DESC) WHERE active = TRUE;
CREATE INDEX idx_links_hash_owner ON links (url_hash, owner_key);
CREATE INDEX idx_links_expiry     ON links (expires_at) WHERE expires_at IS NOT NULL;
-- supports the cache-warming query (top links by clicks)
CREATE INDEX idx_links_clicks     ON links (click_count DESC) WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- clicks: the high-volume table, range-partitioned by month.
--
-- Why partition:
--   * partition pruning - "last 30 days" scans one or two partitions, not the lot
--   * cheap retention   - DROP TABLE clicks_2025_07 is instant metadata work,
--                         where DELETE would rewrite millions of rows and bloat
--   * smaller indexes   - each partition's index stays resident more easily
--
-- Operational risk this creates: a missing future partition means INSERTs start
-- failing at midnight on the 1st. Production would use pg_partman; here a
-- scheduled job in the app (PartitionMaintenanceJob) keeps a rolling window of
-- future partitions, and ensure_click_partition below is what it calls.
--
-- A partitioned table's primary key must include the partition key, hence
-- (id, clicked_at) rather than id alone.
-- ---------------------------------------------------------------------------

CREATE TABLE clicks (
    id           BIGSERIAL,
    -- The Redis Stream entry id of the event that produced this row. Redis assigns it
    -- once and reuses it on redelivery, which is exactly what makes the consumer
    -- idempotent: at-least-once delivery plus a unique key equals effectively-once.
    event_id     VARCHAR(32) NOT NULL,
    link_id      BIGINT      NOT NULL,
    clicked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    country      CHAR(2),
    referrer     VARCHAR(500),
    device_type  VARCHAR(20),
    browser      VARCHAR(50),
    os           VARCHAR(50),
    PRIMARY KEY (id, clicked_at)
) PARTITION BY RANGE (clicked_at);

-- No FK to links(id): a foreign key on a high-write partitioned table costs a lookup
-- per insert on the hot batch path, and an orphaned click row is harmless for
-- analytics. Deleting a link cascades explicitly in the application instead.
CREATE INDEX idx_clicks_link_time ON clicks (link_id, clicked_at DESC);

-- A unique index on a partitioned table must contain the partition key, hence the
-- clicked_at column. This is what ON CONFLICT DO NOTHING keys off.
CREATE UNIQUE INDEX idx_clicks_event ON clicks (event_id, clicked_at);

-- Creates the monthly partition covering the month containing `month_start`,
-- if it does not already exist. Idempotent, safe to call concurrently.
CREATE OR REPLACE FUNCTION ensure_click_partition(month_start DATE)
RETURNS TEXT AS $$
DECLARE
    start_date DATE := date_trunc('month', month_start)::date;
    end_date   DATE := (date_trunc('month', month_start) + INTERVAL '1 month')::date;
    part_name  TEXT := 'clicks_' || to_char(start_date, 'YYYY_MM');
BEGIN
    IF to_regclass('public.' || part_name) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF clicks FOR VALUES FROM (%L) TO (%L)',
            part_name, start_date, end_date);
    END IF;
    RETURN part_name;
END;
$$ LANGUAGE plpgsql;

-- Seed a window of partitions: 3 months back through 12 months forward from the
-- migration date, so a fresh checkout works immediately without waiting for the job.
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN
        SELECT generate_series(
            date_trunc('month', NOW()) - INTERVAL '3 months',
            date_trunc('month', NOW()) + INTERVAL '12 months',
            INTERVAL '1 month')::date
    LOOP
        PERFORM ensure_click_partition(m);
    END LOOP;
END $$;

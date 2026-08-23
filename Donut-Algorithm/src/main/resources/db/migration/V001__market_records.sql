CREATE TABLE IF NOT EXISTS item_fingerprints (
    fingerprint TEXT PRIMARY KEY,
    base_item_id TEXT NOT NULL,
    match_type TEXT NOT NULL,
    normalized_metadata TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auction_listings (
    listing_key TEXT PRIMARY KEY,
    remote_listing_id TEXT,
    seller_uuid TEXT,
    seller_name TEXT,
    item_fingerprint TEXT NOT NULL,
    raw_item_id TEXT NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    listing_price TEXT NOT NULL,
    unit_price TEXT,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    listed_at INTEGER,
    expires_at INTEGER,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'MISSING_ONCE', 'MISSING_REPEATEDLY', 'INACTIVE_UNKNOWN', 'SOLD_CONFIRMED', 'EXPIRED')),
    missing_observations INTEGER NOT NULL DEFAULT 0 CHECK (missing_observations >= 0),
    raw_json TEXT,
    FOREIGN KEY (item_fingerprint) REFERENCES item_fingerprints(fingerprint)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_listings_remote_id
    ON auction_listings(remote_listing_id)
    WHERE remote_listing_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_listings_fingerprint_state
    ON auction_listings(item_fingerprint, state);

CREATE INDEX IF NOT EXISTS idx_listings_last_seen
    ON auction_listings(last_seen_at);

CREATE TABLE IF NOT EXISTS completed_sales (
    sale_key TEXT PRIMARY KEY,
    remote_transaction_id TEXT,
    seller_uuid TEXT,
    seller_name TEXT,
    buyer_uuid TEXT,
    buyer_name TEXT,
    item_fingerprint TEXT NOT NULL,
    raw_item_id TEXT NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    sale_price TEXT NOT NULL,
    unit_price TEXT,
    sold_at INTEGER NOT NULL,
    imported_at INTEGER NOT NULL,
    raw_json TEXT,
    FOREIGN KEY (item_fingerprint) REFERENCES item_fingerprints(fingerprint)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sales_remote_id
    ON completed_sales(remote_transaction_id)
    WHERE remote_transaction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sales_fingerprint_sold_at
    ON completed_sales(item_fingerprint, sold_at DESC);

CREATE TABLE IF NOT EXISTS scanner_metadata (
    metadata_key TEXT PRIMARY KEY,
    metadata_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

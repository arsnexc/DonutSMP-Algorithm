CREATE TABLE IF NOT EXISTS trade_executions (
    execution_id TEXT PRIMARY KEY,
    opportunity_id TEXT NOT NULL,
    listing_key TEXT NOT NULL,
    mode TEXT NOT NULL,
    state TEXT NOT NULL,
    item_id TEXT NOT NULL,
    item_fingerprint TEXT NOT NULL,
    expected_item_count INTEGER NOT NULL,
    expected_listing_price TEXT NOT NULL,
    relist_price TEXT,
    purchase_confirmed INTEGER NOT NULL DEFAULT 0,
    listing_confirmed INTEGER NOT NULL DEFAULT 0,
    status_message TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_executions_state_updated
    ON trade_executions(state, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_trade_executions_listing
    ON trade_executions(listing_key, updated_at DESC);

CREATE TABLE IF NOT EXISTS trade_execution_transitions (
    transition_id INTEGER PRIMARY KEY AUTOINCREMENT,
    execution_id TEXT NOT NULL,
    previous_state TEXT,
    new_state TEXT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    transitioned_at INTEGER NOT NULL,
    FOREIGN KEY (execution_id) REFERENCES trade_executions(execution_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_trade_execution_transitions_execution_time
    ON trade_execution_transitions(execution_id, transitioned_at, transition_id);

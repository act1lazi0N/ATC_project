ALTER TABLE settlement_batches ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE settlement_items ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE reconciliation_runs ALTER COLUMN currency TYPE VARCHAR(3);

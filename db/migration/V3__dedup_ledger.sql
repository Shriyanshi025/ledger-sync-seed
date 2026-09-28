-- Migration V3: Add dedup_key for business-level transaction deduplication
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(300);

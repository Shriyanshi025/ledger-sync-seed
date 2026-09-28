-- Migration V4: Add source_kind column for provenance tracking (CORPUS, LEGACY, INCIDENT_EVIDENCE)
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS source_kind VARCHAR(30) DEFAULT 'CORPUS';

-- Mark legacy seed rows inserted by V2 as LEGACY
UPDATE ledger SET source_kind = 'LEGACY' WHERE source_message_ids LIKE 'm-legacy-%';

-- Mark the specific Task 3 incident evidence row as INCIDENT_EVIDENCE
UPDATE ledger SET source_kind = 'INCIDENT_EVIDENCE' WHERE source_message_ids LIKE '%m-legacy-0041%';

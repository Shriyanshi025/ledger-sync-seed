# System Architecture & Design Decision Log

This document records the 10 key technical architecture, database, and design decisions made during the Simplify Money **Ledger Sync** backend system implementation.

---

### Decision 1: DynamoDB over MongoDB for Document Store
- **Decision**: Selected DynamoDB Local as the primary document store engine.
- **Why**: DynamoDB's single-table/key-value model, Global Secondary Indexes, and explicit `ScannedCount` vs `Count` query metrics align directly with the three required access patterns and make the examined-vs-returned behavior measurable.
- **Evidence/Measurement**: The measured 100,000-transaction DynamoDB Local benchmark returned Q1 = 20,000/20,000, Q2 = 100,000/100,000, and Q3 = 1/1 examined/returned.
- **Tradeoff**: Rigid secondary index structures; requires explicit single-table partition/sort key modeling upfront compared to MongoDB's ad-hoc query flexibility.

---

### Decision 2: Business-Level Deterministic Identity (`dedup_key`)
- **Decision**: Defined transaction identity derived exclusively from immutable business attributes (`account_last4|occurred_at|direction|amount|normalized_merchant`).
- **Why**: Prevents duplicate transaction rows during repeat ingestion while remaining completely independent of source message ordering, ingestion batch order, or evidence IDs.
- **Evidence/Measurement**: First corpus ingestion wrote 256 transactions (`ledger rows = 266`). Exact replay ingestion wrote 0 new transactions (`ledger rows = 266`, `transactionsWritten = 0`).
- **Tradeoff**: Requires explicit merchant string normalization (`trim().toLowerCase()`) and stable handling of null/blank merchants.

---

### Decision 3: GSI1 Composite Key (`ACCT_MONTH#<account>#<YYYY-MM>`) for Q1
- **Decision**: Modeled Global Secondary Index 1 (`GSI1`) with partition key `GSI1_PK = ACCT_MONTH#<account>#<YYYY-MM>` and sort key `GSI1_SK = occurred_at` (ISO-8601).
- **Why**: Allows fetching an account's monthly transactions in newest-first order using `ScanIndexForward = false` without requiring filter expressions or client-side sorting.
- **Evidence/Measurement**: Executing Q1 for the benchmark account `9998` in month `2026-07` returned 20,000 items with `ScannedCount = 20,000` and `Count = 20,000`.
- **Tradeoff**: Increases storage and write provisioned throughput for GSI1 index updates.

---

### Decision 4: GSI2 Message Evidence Mapping (`MSG#<message_id>`) for Q3
- **Decision**: Created Global Secondary Index 2 (`GSI2`) with partition key `GSI2_PK = MSG#<message_id>` and sort key `GSI2_SK = TXN#<dedup_key>`, writing one index item per source message ID.
- **Why**: Enables instant $O(1)$ lookup of the canonical transaction produced by any raw SMS or email message ID.
- **Evidence/Measurement**: Q3 lookup for `benchmark-100k-50000` yields `ScannedCount = 1` and `Count = 1`.
- **Tradeoff**: Multi-evidence correlated transactions write multiple GSI2 mapping entries upon insertion.

---

### Decision 5: Base-Partition Query for Q2 (`categoryTotals`)
- **Decision**: Implemented Q2 by querying the base table partition (`PK = ACCOUNT#<account_last4>`, `begins_with(SK, "TXN#")`) and aggregating category totals in memory across pages.
- **Why**: Isolates query execution strictly to items belonging to the target account, preventing full table scans across unrelated accounts.
- **Evidence/Measurement**: Executing Q2 for benchmark account `9998` examined 100,000 items and returned 100,000 items. All 100,000 benchmark transactions belonged to this account.
- **Tradeoff**: Requires aggregating category totals across paginated result sets rather than reading a pre-computed single counter document (prioritizing atomic transaction consistency over pre-aggregation maintenance).

---

### Decision 6: Pagination via `LastEvaluatedKey` / `ExclusiveStartKey`
- **Decision**: Implemented pagination loops using `LastEvaluatedKey` across all DynamoDB Query and Scan access patterns.
- **Why**: Guarantees complete data retrieval beyond DynamoDB's 1 MB response payload boundary for large datasets.
- **Evidence/Measurement**: Successfully retrieves and aggregates all 100,000 transactions across multiple paginated HTTP response frames.
- **Tradeoff**: Requires accumulating `ScannedCount` and `Count` metrics across multiple page responses.

---

### Decision 7: Evidence Set Union for Repeated/Correlated Ingestion
- **Decision**: When an ingested transaction matches an existing `dedup_key`, update `source_message_ids` with the deterministic sorted union of all evidence message IDs rather than inserting a duplicate row.
- **Why**: Preserves full source evidence traceability when SMS and Email messages for the same transaction arrive in separate ingestion batches.
- **Evidence/Measurement**: `RepeatSafeIngestionTest.java` verifies that merging `m1` and `m2` yields `source_message_ids = ["m1", "m2"]` while keeping total ledger count unchanged.
- **Tradeoff**: Requires a `SELECT` check prior to `INSERT` in SQL store and an item update when new evidence IDs arrive.

---

### Decision 8: Database Migration Pre-Deduplication Cleanup (`V3__dedup_ledger.sql`)
- **Decision**: Backfilled `dedup_key` on existing SQL rows and merged legacy duplicate evidence before creating the `idx_ledger_dedup_key` UNIQUE index.
- **Why**: Legacy production seed (`V2__seed.sql`) contained un-deduplicated rows. Attempting to create a unique index without pre-cleaning would trigger SQL constraint violations.
- **Evidence/Measurement**: Migration reduced 15 raw legacy seed rows to 10 distinct transaction records while preserving all source message IDs (`m-legacy-0001,m-legacy-0002`).
- **Tradeoff**: Migration script requires custom Java-based deduplication logic prior to executing DDL `CREATE UNIQUE INDEX`.

---

### Decision 9: Multi-leg Transfer Detection with Direction Tie-Breaker
- **Decision**: Correlated self-transfers between user accounts using matching amount within a 5-minute time window and same-owner evidence keywords (`SELF`, `PARAG KAPOOR`, etc.), adding a direction tie-breaker (`DEBIT` before `CREDIT`) for identical timestamps.
- **Why**: Prevents self-transfers from being misclassified as `SPEND` or `INCOME`, resolving account balances to exact bank stated totals.
- **Evidence/Measurement**: Corpus A classification correctly identified 5 transfer pairs (e.g. ₹25,000 and ₹6,000 transfers), setting 4821 transferred out to ₹25,000 and 9075 transferred in to ₹25,000.
- **Tradeoff**: Time-window correlation logic assumes clocks across banks/SMS channels are aligned within 5 minutes.

---

### Decision 10: Deep Field-Level Consistency Verification
- **Decision**: Implemented `ConsistencyChecker` using `dedup_key` mapping to verify every field value (`amount`, `category`, `direction`, `occurredAt`, `merchant`, `sourceMessageIds`) individually between SQL and DocumentStore.
- **Why**: Simple row-count comparison fails to detect subtle data corruption, altered categories, or missing source evidence.
- **Evidence/Measurement**: `DocumentStoreTest.testConsistencyCheckerFindsFieldDivergence()` correctly catches an altered category (`SPEND` vs `MICRO`) and reports exact `Divergence` field details.
- **Tradeoff**: $O(N)$ memory and time complexity to index and cross-evaluate all records across both stores.



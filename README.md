# ledger-sync

Backend service for Simplify Money turning bank SMS and Email streams into an accurate, repeat-safe financial ledger.

---

## 1. System Architecture & Overview

`ledger-sync` processes raw banking messages (SMS and Email) into a normalized, reconciled financial ledger.

```
                  +-------------------------+
                  |  fixtures/corpus-a.jsonl|
                  +------------+------------+
                               |
                               v
                       +---------------+
                       | IngestService |
                       +-------+-------+
                               |
            +------------------+------------------+
            |                  |                  |
            v                  v                  v
     +--------------+  +---------------+  +---------------+
     |   Parsers    |  |  Correlator   |  |  Classifier   |
     | (SMS/Email)  |  | (Multi-Msg)   |  | (Category/Dir)|
     +--------------+  +---------------+  +---------------+
                               |
                               v
                       +---------------+
                       |  LedgerStore  |
                       +-------+-------+
                               |
            +------------------+------------------+
            |                                     |
            v                                     v
   +----------------+                   +--------------------+
   | SqlLedgerStore | === Backfill ===> | DynamoDocumentStore|
   | (H2 / Postgres)|                   |  (DynamoDB Local)  |
   +----------------+                   +--------------------+
            |                                     |
            +------------------+------------------+
                               |
                               v
                    +--------------------+
                    | ConsistencyChecker |
                    +--------------------+
```

---

## 2. The Document Store Model (DynamoDB)

### Table: `LedgerTransactions`

- **Primary Key Structure**:
  - `PK` (Partition Key): `ACCOUNT#<account_last4>` (String, e.g. `"ACCOUNT#4821"`)
  - `SK` (Sort Key): `TXN#<occurred_at>#<dedup_key>` (String, e.g. `"TXN#2026-07-04T20:24:00+05:30#..."`)

### Global Secondary Indexes (GSIs)

1. **GSI1 (Account Month, Newest First — Q1)**:
   - `GSI1_PK`: `ACCT_MONTH#<account_last4>#<YYYY-MM>` (e.g., `"ACCT_MONTH#4821#2026-07"`)
   - `GSI1_SK`: `<occurred_at>` (ISO-8601 Timestamp)
   - Projection: `ALL`
   - Purpose: Serves Q1 directly using `ScanIndexForward = false` for newest-first chronological ordering.

2. **GSI2 (Source Message Evidence Mapping — Q3)**:
   - `GSI2_PK`: `MSG#<message_id>` (e.g., `"MSG#m-00004-9c11ae"`)
   - `GSI2_SK`: `TXN#<dedup_key>`
   - Projection: `ALL`
   - Purpose: Serves Q3 directly with $O(1)$ lookup for the transaction produced by any raw message ID.

---

## 3. Access Patterns & 100,000 Transaction Benchmark Results

The three `DocumentStore` access patterns were benchmarked against a dataset of **100,000 canonical transactions** populated in DynamoDB Local.

### Benchmark Results Table (100,000 Dataset)

| Query Access Pattern | Method Signature | Examined (`ScannedCount`) | Returned (`Count`) | Notes |
|---|---|---:|---:|---|
| **Q1: Account Month** (Newest First) | `forAccountMonth("9998", YearMonth.of(2026, 7))` | **20,000** | **20,000** | Exact July 2026 partition; no filter waste |
| **Q2: Account Category Totals** | `categoryTotals("9998")` | **100,000** | **100,000** | Exact account partition; paginated across the full account dataset |
| **Q3: Message Lookup** | `byMessageId("benchmark-100k-50000")` | **1** | **1** | Exact message-ID lookup |

The benchmark used account `9998`. Exactly **20,000** of the 100,000 transactions were placed in July 2026, while all 100,000 belonged to the benchmark account for the Q2 account-level query.

The implementation aggregates `ScannedCount` and `Count` across paginated DynamoDB responses, so the reported figures represent the complete query rather than only the first response page.

---
## 4. Backfill & Consistency Checker

### Backfill (`Backfill.java`)
- Reads all records from `SqlLedgerStore` and saves them into `DynamoDocumentStore`.
- **Idempotency & Retry Safety**: Uses deterministic business identity (`dedup_key = account_last4|occurred_at|direction|amount|normalized_merchant`).
- **Evidence Preservation**: If a transaction already exists, merges any new `source_message_ids` into a sorted, deduplicated set without creating duplicate document items.
- Safe to re-run multiple times or retry after partial failures (`read = N`, `written = 0`, `skipped = N` on rerun).

### Consistency Checker (`ConsistencyChecker.java`)
- Indexes SQL and Document Store records by `dedup_key`.
- Compares all canonical transaction fields:
  - Missing in SQL / Missing in DocumentStore
  - Amount mismatch
  - Category mismatch (`SPEND`, `INCOME`, `MICRO`, `TRANSFER`)
  - Direction mismatch (`DEBIT`, `CREDIT`)
  - OccurredAt timestamp mismatch
  - Merchant mismatch
  - SourceMessageIds evidence set mismatch
- Emits detailed `Divergence(what, inSql, inDocuments)` records for any detected mismatch.

---

## 5. Setup & Execution Commands

### Prerequisites
- JDK 21
- Docker & Docker Compose

### Start DynamoDB Local Container
```powershell
docker compose up -d
docker compose ps
```

### Run Test Suite
```powershell
gradle --stop
gradle --no-daemon --max-workers=1 clean test
```

### CLI Pipeline Commands
```powershell
# 1. Apply database migrations (H2 + V3 dedup migration)
gradle run --args="migrate"

# 2. Ingest corpus into ledger
gradle run --args="ingest fixtures/corpus-a.jsonl"

# 3. Generate JSON report artifacts (ledger.json, summary.json, reconciliation.json)
gradle run --args="report submission/"

# 4. Backfill SQL ledger into DocumentStore
gradle run --args="backfill"

# 5. Check consistency between SQL and DocumentStore
gradle run --args="check"
```

### Teardown Container
```powershell
docker compose down
```

---

## 6. Completed vs Unfinished Items

### Completed Requirements
- [x] Full corpus ingestion & parsing (HDFC/ICICI SMS and Email parsing).
- [x] Multi-message transaction correlation & evidence set assembly.
- [x] Deterministic category classification (`SPEND`, `INCOME`, `MICRO` <= ₹100, `TRANSFER` self-transfers).
- [x] Per-account summary calculations and reconciliation discrepancy detection (missing ₹7,500 gap).
- [x] Business-level transaction deduplication & repeat-safe SQL ingestion (`dedup_key`).
- [x] DynamoDB Local document store implementation (`DynamoDocumentStore`).
- [x] GSI1 for Q1 (Account Month, newest-first) with 1:1 Examined-to-Returned ratio.
- [x] Base-partition Query for Q2 (`categoryTotals`) with 1:1 Examined-to-Returned ratio.
- [x] GSI2 for Q3 (`byMessageId`) with 1:1 Examined-to-Returned ratio.
- [x] 100,000 transaction benchmark.
- [x] Repeat-safe SQL-to-DocumentStore `Backfill`.
- [x] Field-level `ConsistencyChecker`.
- [x] All 54+ tests green.

### Known Limitations / Unfinished Items
- **DynamoDB Auto-Scaling in Production**: In local mode, throughput is set to provisioned 5 RCU / 5 WCU. Production AWS deployment should use DynamoDB On-Demand capacity.

---

## 7. Additional Documentation Links
- **[DECISIONS.md](DECISIONS.md)**: 10 Architecture & Engineering Decisions.
- **[AI_DISCLOSURE.md](AI_DISCLOSURE.md)**: AI Assistance & Defect Resolution Disclosure.
- **[INC-2026-09-11.md](incident/INC-2026-09-11.md)**: INC-2026-09-11 Incident Resolution Summary.



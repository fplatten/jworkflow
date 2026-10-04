# Persisted data format

This is the storage contract of `jworkflow-jdbc`: what workflow data can be stored without loss, and how values are
encoded in SQLite and PostgreSQL.

## Workflow variables and other JSON values

Workflow variables, command results, definitions and message metadata are stored as UTF-8 JSON text. Supported values:

- `null`, strings and booleans;
- numbers (see below);
- maps with string keys, and lists;
- any nesting of these, including empty maps and lists.

Other Java objects are rejected with `PersistenceSerializationException`; Java serialization is never used. Convert
domain types (including dates and times) to strings before putting them in workflow variables. Values read back are
immutable copies.

### Numbers keep their type

| Stored Java type | Read back as |
| --- | --- |
| `Integer`, `Double` | Same type (plain JSON numbers) |
| `Long`, `BigDecimal`, `BigInteger`, `Float`, `Short`, `Byte` | Same type and exact value |

The types in the second row cannot survive plain JSON (a `Long` that fits an int would come back as `Integer`, and a
`BigDecimal` as a `Double`), so they are stored as a tagged object holding the exact text:

```json
{"@jworkflow.number": "decimal", "value": "12345678901234567890.12"}
```

A map with exactly the keys `@jworkflow.number` and `value` is reserved and rejected on write. Other `Number`
implementations are stored as plain JSON numbers and come back as `Integer`, `Long`, `BigInteger` or `Double`.

### Envelope

Every JSON column holds an envelope with sorted keys, so equal values produce identical text:

```json
{"format": "jworkflow-json", "value": {...}, "version": 1}
```

- **Version 1:** no tagged numbers.
- **Version 2:** the value contains tagged numbers.

Data is written as version 1 whenever possible, so existing rows never change. Builds from before typed numbers were
added reject version 2 instead of misreading it, so do not run old and new workers against one database; see
[upgrading](upgrading.md).

### Legacy rows

Very early JDBC code stored maps with `Map.toString()`. That text cannot be decoded reliably. Blank values and `{}`
are read as an empty map; anything else fails with `PersistenceSerializationException` rather than being silently
emptied. Such rows need an application-specific migration.

## Timestamps

| Where | SQLite | PostgreSQL |
| --- | --- | --- |
| Table columns (created, updated, due, claim, retry…) | UTC text with a fixed nine-digit fraction, e.g. `2026-01-01T00:00:00.500000000Z` | `numeric(30,9)` epoch seconds |
| Inside JSON values | ISO-8601 text | ISO-8601 text |

SQLite compares the text directly, so the fixed width keeps text order equal to time order. Migration V7 rewrites
older variable-width values. Years outside 0000–9999 (only sentinels such as `Instant.MIN`) keep the ISO form and do
not sort correctly.

PostgreSQL values are exact. Decode them with `BigDecimal`, never through `double` or `timestamp`; timestamp
conversions are only for display. For example:

```sql
SELECT id, updated_at,
       to_timestamp(updated_at::double precision) AT TIME ZONE 'UTC' AS approximate_utc
FROM workflow_instance ORDER BY updated_at, id LIMIT 20;
```

## Other values

| Value | SQLite | PostgreSQL |
| --- | --- | --- |
| Identifiers and definition revisions | Text | Text (not native UUID) |
| Binary message bodies | BLOB | `bytea` |
| Lock versions and event sequences | Integer | `bigint` |
| JSON | Text | Text (not JSONB) |

On PostgreSQL, relational idempotency keys escape `%` as `%25` and NUL as `%00`; the 255-character limit applies after
escaping.

## Definitions and snapshots

- A definition is stored with its canonical JSON, a checksum and, when compiled from the DSL, its source text. Each
  running workflow keeps the exact revision it started with, even after a newer revision of the same name and
  version is registered.
- A snapshot stores the current state, status, variables and lock version. Pending waits are reconstructed from the
  current state and the stored definition revision; timers are stored as separate rows.
- A command result stored under an idempotency key holds the command-time snapshot and the emitted event ids, on
  SQLite and PostgreSQL, so a repeat returns the original result. Results written before 0.1.0 on SQLite lack these
  fields; repeats of those commands return the current snapshot instead.

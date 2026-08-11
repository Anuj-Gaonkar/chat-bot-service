# DB Backups

Backups of the `chatbot` Postgres database (container `chat-bot-service-v2-postgres`, from `chat-bot-service/docker-compose.yml`), taken before making schema/data changes to the workflow tables.

## Migrations

`migrations/` holds every hand-run SQL change applied to the live dev DB *after* this backup was
taken (`WorkflowSeeder` is idempotent and only seeds an empty DB, so any schema/data change to an
already-seeded database needs its own SQL file here, applied directly via
`docker exec -i chat-bot-service-v2-postgres psql -U chatbot -d chatbot < migrations/<file>.sql`).
Applied in date order, each is self-documenting (a comment block at the top explains what changed
and why) — as of this writing:

1. `2026-08-06_amb_full_journey_rebuild.sql` — the generic `OPTION`+`option_index` transition
   model, and the first 5-branch AMB shortfall journey (replacing an earlier 3-branch cut).
2. `2026-08-06_amb_journey_fixes.sql` — post-rebuild fixes (duplicate-line acks, some
   "remind me later" routing).
3. `2026-08-10_amb_journey_v2_script_update.sql` — reworked the flow to match the bank's actual
   WhatsApp script (updated copy, dropped/added options across every branch, the callback-button
   pattern).
4. `2026-08-11_session_conclusions.sql` — `conclusion_code` on `workflow_node`/`workflow_session`,
   the `session_outcome` view.
5. `2026-08-11_entry_reason_and_path.sql` — `entry_reason_code` on `workflow_transition`/
   `workflow_session`, extends `session_outcome`.

See `SINGLE-MODULE-CHATBOT-BUILD-CONTEXT.md` §2/§7.4 (one level up) for what these columns/views
are actually for.

## 2026-08-06_12:52:56 backup

Files:
- `chatbot_full_backup_20260806_125256.sql` — plain-SQL dump (`pg_dump --clean --if-exists --create`). Human-readable, restorable with `psql`.
- `chatbot_full_backup_20260806_125256.dump` — custom-format dump (`pg_dump -Fc`). Restorable with `pg_restore`, supports selective/parallel restore.
- `row_counts_20260806_125256.txt` — row-count snapshot at backup time, for sanity-checking a restore.

Row counts at backup time:

| Table | Rows |
|---|---|
| workflow | 1 |
| workflow_version | 1 |
| workflow_node | 17 |
| workflow_transition | 21 |
| workflow_action_config | 3 |
| workflow_entry_point | 1 |
| workflow_session | 74 |
| workflow_session_event | 510 |

This covers the full `chatbot` database (all 8 tables currently in the schema), not just the `workflow_*` graph tables — `workflow_session`/`workflow_session_event` runtime data is included too.

## How to restore

Postgres container must be running (`docker compose up -d` from `chat-bot-service/`).

**Option A — plain SQL dump (drops & recreates the DB, then reloads everything):**
```sh
docker exec -i chat-bot-service-v2-postgres psql -U chatbot -d postgres \
  < chatbot_full_backup_20260806_125256.sql
```
(The dump was taken with `--clean --if-exists --create`, so it drops/recreates the `chatbot` database itself — connect to the `postgres` maintenance DB to run it, not `chatbot`.)

**Option B — custom-format dump (restore into a clean or existing `chatbot` db):**
```sh
docker exec -i chat-bot-service-v2-postgres pg_restore -U chatbot -d chatbot --clean --if-exists \
  < chatbot_full_backup_20260806_125256.dump
```

**Verify after restore:**
```sh
docker exec chat-bot-service-v2-postgres psql -U chatbot -d chatbot -c "
SELECT 'workflow' t, count(*) c FROM workflow
UNION ALL SELECT 'workflow_version', count(*) FROM workflow_version
UNION ALL SELECT 'workflow_node', count(*) FROM workflow_node
UNION ALL SELECT 'workflow_transition', count(*) FROM workflow_transition
UNION ALL SELECT 'workflow_action_config', count(*) FROM workflow_action_config
UNION ALL SELECT 'workflow_entry_point', count(*) FROM workflow_entry_point
UNION ALL SELECT 'workflow_session', count(*) FROM workflow_session
UNION ALL SELECT 'workflow_session_event', count(*) FROM workflow_session_event
ORDER BY 1;"
```
Counts should match the table above.

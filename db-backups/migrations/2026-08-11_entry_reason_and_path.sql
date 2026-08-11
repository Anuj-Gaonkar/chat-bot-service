-- Entry reason: a coarse, persisted tag for which top-level AMB_MENU option a customer picked,
-- frozen onto workflow_session alongside conclusion_code. Two paths that converge on the same
-- conclusion (e.g. "funds shortly" vs. "cash flow constraints -> remind me later", both ending
-- in REMINDER_SET) become distinguishable by why the customer engaged in the first place.
--
-- The finer-grained full path (e.g. distinguishing "Service concern" vs. "Other" within the
-- same top-level choice) is NOT persisted here - it's derived on read by
-- SessionFrameService.pathSummary() from data that already exists (workflow_session_event +
-- workflow_transition), and shows up as the new `pathSummary` field on the frame endpoints.

BEGIN;

-- ============================================================
-- 1. Schema: one nullable column on each table
-- ============================================================

ALTER TABLE workflow_transition ADD COLUMN IF NOT EXISTS entry_reason_code VARCHAR(50);
ALTER TABLE workflow_session ADD COLUMN IF NOT EXISTS entry_reason_code VARCHAR(50);

-- ============================================================
-- 2. Taxonomy: tag AMB_MENU's (node 120) 5 options
-- ============================================================

UPDATE workflow_transition SET entry_reason_code = 'FUND_NOW'               WHERE from_node_id = 120 AND option_index = 1;
UPDATE workflow_transition SET entry_reason_code = 'FUNDS_SHORTLY'          WHERE from_node_id = 120 AND option_index = 2;
UPDATE workflow_transition SET entry_reason_code = 'CASH_FLOW_CONSTRAINTS'  WHERE from_node_id = 120 AND option_index = 3;
UPDATE workflow_transition SET entry_reason_code = 'UNAWARE_OF_REQUIREMENT' WHERE from_node_id = 120 AND option_index = 4;
UPDATE workflow_transition SET entry_reason_code = 'CHURN_RISK'             WHERE from_node_id = 120 AND option_index = 5;

-- ============================================================
-- 3. Backfill: sessions that already answered AMB_MENU get tagged retroactively.
--    A session can loop back to AMB_MENU after a FAILURE and answer again with a different
--    option - context gets overwritten each time a real reply happens, so the frozen value is
--    always the LATEST answer, not necessarily the first. Pick the same (latest) event here via
--    a LATERAL join ordered by event_id DESC, so the backfill matches live engine behavior.
-- ============================================================

-- Postgres doesn't allow UPDATE ... FROM LATERAL to reference the update target itself, so
-- this uses a correlated scalar subquery in SET instead - same "pick the latest match" logic.
UPDATE workflow_session s
SET entry_reason_code = (
  SELECT t.entry_reason_code
  FROM workflow_session_event e
  JOIN workflow_transition t
    ON t.from_node_id = e.node_id
   AND t.event_code = 'OPTION'
   AND t.option_index = (e.payload ->> 'rawInput')::int
  WHERE e.session_id = s.session_id
    AND e.node_id = 120
    AND e.event_code = 'OPTION'
  ORDER BY e.event_id DESC
  LIMIT 1
)
WHERE s.entry_reason_code IS NULL;

-- ============================================================
-- 4. Extend session_outcome with entry_reason_code, so one query gives conclusion + entry
--    reason together (e.g. splitting REMINDER_SET by why the customer actually engaged).
-- ============================================================

-- CREATE OR REPLACE VIEW can only append new columns at the end, not insert them in the
-- middle - entry_reason_code goes last, after the columns the original view already had.
CREATE OR REPLACE VIEW session_outcome AS
SELECT
  s.session_id,
  s.customer_id,
  s.status,
  COALESCE(s.conclusion_code, 'DROPPED_AT:' || n.node_code) AS conclusion,
  s.started_at,
  s.last_interaction_at,
  s.ended_at,
  s.entry_reason_code
FROM workflow_session s
JOIN workflow_node n ON n.node_id = s.current_node_id;

COMMIT;

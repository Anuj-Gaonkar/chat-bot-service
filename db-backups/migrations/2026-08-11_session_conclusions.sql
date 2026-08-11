-- Session conclusions: a business-outcome taxonomy tagged on END nodes, frozen onto
-- workflow_session the instant a session completes. See the "Session conclusions" plan for the
-- full design rationale.
--
-- Covers both completed outcomes (workflow_session.conclusion_code, stamped by WorkflowEngine)
-- and sessions that never finish - the session_outcome view below derives "DROPPED_AT:<node>"
-- dynamically for those, from data that already exists (current_node_id), without any new
-- persisted state or scheduled job.

BEGIN;

-- ============================================================
-- 1. Schema: one nullable column on each table
-- ============================================================

ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS conclusion_code VARCHAR(50);
ALTER TABLE workflow_session ADD COLUMN IF NOT EXISTS conclusion_code VARCHAR(50);

-- ============================================================
-- 2. Taxonomy: tag every END node with its business-outcome code
-- ============================================================

UPDATE workflow_node SET conclusion_code = 'FUND_LINK_SENT'          WHERE node_id = 205; -- FUND_TODAY_ACK
UPDATE workflow_node SET conclusion_code = 'FUNDED'                  WHERE node_id = 220; -- END_FUNDED (dormant)
UPDATE workflow_node SET conclusion_code = 'FUND_PENDING'            WHERE node_id = 230; -- END_FUND_PENDING (dormant)
UPDATE workflow_node SET conclusion_code = 'REMINDER_SET'            WHERE node_id = 350; -- END_FUNDS_REMINDER
UPDATE workflow_node SET conclusion_code = 'INFO_REDIRECT'           WHERE node_id = 425; -- CHARGES_INFO_REDIRECT
UPDATE workflow_node SET conclusion_code = 'ESCALATED_TO_EXECUTIVE'  WHERE node_id = 430; -- END_EXECUTIVE_HANDOFF
UPDATE workflow_node SET conclusion_code = 'INFO_REDIRECT'           WHERE node_id = 515; -- INFO_WEBSITE_REDIRECT
UPDATE workflow_node SET conclusion_code = 'UPGRADE_INTEREST'        WHERE node_id = 520; -- ACCOUNT_UPGRADE_JOURNEY
UPDATE workflow_node SET conclusion_code = 'SALARY_ACCOUNT_OFFERED'  WHERE node_id = 631; -- END_SALARY_ACCOUNT_OFFER
UPDATE workflow_node SET conclusion_code = 'RETENTION_APPEAL'        WHERE node_id = 632; -- END_RETENTION_APPEAL
UPDATE workflow_node SET conclusion_code = 'VISIT_BRANCH'            WHERE node_id = 634; -- END_VISIT_BRANCH
UPDATE workflow_node SET conclusion_code = 'CALLBACK_REQUESTED'      WHERE node_id = 639; -- END_CALLBACK_LOGGED

-- ============================================================
-- 3. Backfill: sessions already COMPLETED before this migration get tagged retroactively too
-- ============================================================

UPDATE workflow_session s
SET conclusion_code = n.conclusion_code
FROM workflow_node n
WHERE s.current_node_id = n.node_id
  AND s.status = 'COMPLETED'
  AND s.conclusion_code IS NULL;

-- ============================================================
-- 4. session_outcome: one place to query a conclusion regardless of whether the session
--    ever finished - COALESCEs the frozen value, falling back to "DROPPED_AT:<node_code>"
--    for anything still sitting on a non-END node.
-- ============================================================

CREATE OR REPLACE VIEW session_outcome AS
SELECT
  s.session_id,
  s.customer_id,
  s.status,
  COALESCE(s.conclusion_code, 'DROPPED_AT:' || n.node_code) AS conclusion,
  s.started_at,
  s.last_interaction_at,
  s.ended_at
FROM workflow_session s
JOIN workflow_node n ON n.node_id = s.current_node_id;

COMMIT;

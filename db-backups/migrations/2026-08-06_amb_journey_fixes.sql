-- Fixes for 4 issues found after the full-journey rebuild. NOT YET APPLIED - for review.
-- See conversation notes / AMB_FULL_JOURNEY_DB_MIGRATION_PLAN.md for the original graph.

BEGIN;

-- ============================================================
-- 1. Branch 1 shows the "24-hour recheck" result instantly (same turn as the
--    acknowledgment, since the engine has no real async wait). Stop the turn
--    right after the acknowledgment; treat the recheck outcome as an
--    out-of-band batch-job concern (same category as the Day 1-20 reminder
--    ladder), not a live graph edge.
-- ============================================================

UPDATE workflow_node
SET node_type = 'END', back_allowed = false, exit_allowed = false, home_allowed = false
WHERE node_id = 200; -- FUND_TODAY_ACK becomes terminal

DELETE FROM workflow_transition WHERE from_node_id = 200; -- drop the AUTO -> 210 edge

-- 210 CHECK_FUNDING_STATUS / 220 END_FUNDED / 230 END_FUND_PENDING are left in place but
-- now unreachable from the live entry point - preserved as a template for whatever later
-- sends the real 24-hour follow-up (a new entry point into node 210, e.g.), not deleted.

-- ============================================================
-- 2. Duplicate lines: an ack MESSAGE already contains the exact sentence the
--    following QUESTION node asks again, and both get joined into one bubble.
--    Trim the repeated clause from each ack - the QUESTION node's own message
--    must keep the full question text (it doubles as the invalid-input reprompt).
-- ============================================================

UPDATE workflow_node SET message = 'Thank you for letting us know.'
WHERE node_id = 300; -- FUNDS_SHORTLY_ACK (was duplicating FUNDS_TIMING's question)

UPDATE workflow_node SET message = 'We understand situations can be challenging.'
WHERE node_id = 400; -- CASH_FLOW_ACK (was duplicating CASH_FLOW_MENU's question)

UPDATE workflow_node SET message = 'No worries. Let us help you understand.'
WHERE node_id = 500; -- UNAWARE_ACK (was duplicating INFO_MENU's question)

UPDATE workflow_node SET message = 'We''re sorry to hear that.'
WHERE node_id = 600; -- CHURN_ACK (same bug, caught while checking the others - was duplicating CHURN_REASON_MENU's question)

-- ============================================================
-- 3. "Remind me later" paths (Branch 3's option 1, Branch 4's NO) should ask
--    "when" the same way Branch 2 does, instead of silently scheduling an
--    undated reminder. Reroute both into FUNDS_TIMING (310) - same 3/7/15-day/
--    custom-date menu, same SCHEDULE_FUNDS_REMINDER action, same END_FUNDS_REMINDER
--    ending as Branch 2. Business rules are keyed by the *current* node code
--    (FUNDS_TIMING), not by how the customer arrived there, so no engine changes
--    are needed beyond the transition targets themselves.
-- ============================================================

UPDATE workflow_transition SET to_node_id = 310
WHERE from_node_id = 410 AND option_index = 1; -- CASH_FLOW_MENU "Remind me later" -> FUNDS_TIMING

UPDATE workflow_transition SET to_node_id = 310
WHERE from_node_id = 520 AND event_code = 'NO'; -- MAINTAIN_NOW "Remind me later" -> FUNDS_TIMING

-- 530 LOG_AWARENESS_REMINDER / 540 END_AWARENESS_REMINDER have no remaining path to them
-- (unlike 210/220/230 above, there's no future reuse case for this pair once merged into
-- FUNDS_TIMING) - remove them outright.
DELETE FROM workflow_transition WHERE from_node_id = 530;
DELETE FROM workflow_action_config WHERE node_id = 530;
DELETE FROM workflow_node WHERE node_id IN (530, 540);

COMMIT;

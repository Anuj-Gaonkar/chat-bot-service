-- AMB full-journey rebuild: schema change + truncate + full reseed.
-- Applied 2026-08-06. See AMB_FULL_JOURNEY_DB_MIGRATION_PLAN.md at repo root for the design.
-- Pre-change backup: chat-bot-service/db-backups/chatbot_full_backup_20260806_125256.{sql,dump}

BEGIN;

-- ============================================================
-- 1. Truncate everything first (definition tables + runtime session
--    tables) - must happen before tightening the event_code CHECK
--    constraints below, otherwise the old OPTION_1/2/3 rows would
--    violate the new constraint before they're gone.
-- ============================================================

TRUNCATE TABLE workflow_session_event, workflow_session,
  workflow_action_config, workflow_entry_point, workflow_transition,
  workflow_node, workflow_version, workflow RESTART IDENTITY;

-- ============================================================
-- 2. Schema change: OPTION_1/2/3 -> generic OPTION + option_index
-- ============================================================

ALTER TABLE workflow_transition ADD COLUMN IF NOT EXISTS option_index integer;

ALTER TABLE workflow_transition DROP CONSTRAINT IF EXISTS workflow_transition_event_code_check;
ALTER TABLE workflow_transition ADD CONSTRAINT workflow_transition_event_code_check
  CHECK (event_code IN ('AUTO','OPTION','YES','NO','SUCCESS','FAILURE','TIMEOUT','INVALID_INPUT'));

ALTER TABLE workflow_action_config DROP CONSTRAINT IF EXISTS workflow_action_config_on_success_event_check;
ALTER TABLE workflow_action_config ADD CONSTRAINT workflow_action_config_on_success_event_check
  CHECK (on_success_event IN ('AUTO','OPTION','YES','NO','SUCCESS','FAILURE','TIMEOUT','INVALID_INPUT'));

ALTER TABLE workflow_action_config DROP CONSTRAINT IF EXISTS workflow_action_config_on_failure_event_check;
ALTER TABLE workflow_action_config ADD CONSTRAINT workflow_action_config_on_failure_event_check
  CHECK (on_failure_event IN ('AUTO','OPTION','YES','NO','SUCCESS','FAILURE','TIMEOUT','INVALID_INPUT'));

-- workflow_session_event: same enum shrink (it logs every AUTO/OPTION/YES/NO/SUCCESS/FAILURE/
-- INVALID_INPUT hop, so it carries the same CHECK constraint independently of workflow_transition)
ALTER TABLE workflow_session_event DROP CONSTRAINT IF EXISTS workflow_session_event_event_code_check;
ALTER TABLE workflow_session_event ADD CONSTRAINT workflow_session_event_event_code_check
  CHECK (event_code IN ('AUTO','OPTION','YES','NO','SUCCESS','FAILURE','TIMEOUT','INVALID_INPUT'));

-- ============================================================
-- 3. Workflow + version
-- ============================================================

INSERT INTO workflow (workflow_id, name) VALUES (1, 'AMB shortfall outreach');

INSERT INTO workflow_version (workflow_version_id, workflow_id, version_number, status, start_node_id, created_at, published_at)
VALUES (1, 1, 1, 'PUBLISHED', 100, now(), now());

-- ============================================================
-- 4. Nodes (29)
-- ============================================================

INSERT INTO workflow_node (node_id, workflow_version_id, node_code, node_type, title, message, back_allowed, exit_allowed, home_allowed) VALUES
(100, 1, 'START',                  'START',   'Start',
  'System entry - triggered by the AMB-shortfall batch job', false, true, false),
(110, 1, 'INTRO',                  'MESSAGE', 'Intro',
  'You are on the HDFC Bank WhatsApp Chat. We need to share some important information about your AMB maintenance in your Savings Account.

Your balance maintenance in your Savings Account is low.

To help you continue enjoying all account benefits and avoid applicable charges, please let us know how you would like to proceed.', false, true, false),
(120, 1, 'AMB_MENU',               'QUESTION','AMB menu',
  'What would you like to do?', true, true, true),

(200, 1, 'FUND_TODAY_ACK',         'MESSAGE', 'Fund today ack',
  'Thank you! Please fund your account to maintain the required AMB. We will recheck within 24 hours.', false, true, false),
(210, 1, 'CHECK_FUNDING_STATUS',   'ACTION',  'Check funding status',
  'Rechecks whether the required AMB balance has been received (simulated 24-hour recheck)', false, true, false),
(220, 1, 'END_FUNDED',             'END',     'Funded',
  'Thank you! We have received the required balance. Your account benefits continue as usual.', false, false, false),
(230, 1, 'END_FUND_PENDING',       'END',     'Fund pending',
  'We haven''t received the required balance yet. We will remind you again in a few days. Thank you for your time.', false, false, false),

(300, 1, 'FUNDS_SHORTLY_ACK',      'MESSAGE', 'Funds shortly ack',
  'Thank you for letting us know. When do you expect the funds?', false, true, true),
(310, 1, 'FUNDS_TIMING',           'QUESTION','Funds timing',
  'When do you expect the funds?', true, true, true),
(330, 1, 'CUSTOM_DATE_INPUT',      'INPUT',   'Custom date input',
  'Please enter the date you expect to fund your account (DD-MM-YYYY).', true, true, true),
(340, 1, 'SCHEDULE_FUNDS_REMINDER','ACTION',  'Schedule funds reminder',
  'Creates a CRM follow-up reminder for the expected funding date (simulated)', false, true, false),
(350, 1, 'END_FUNDS_REMINDER',     'END',     'Funds reminder set',
  'We will send you a reminder one day before the expected date. Thank you.', false, false, false),

(400, 1, 'CASH_FLOW_ACK',          'MESSAGE', 'Cash flow ack',
  'We understand situations can be challenging. Please choose how we can assist you.', false, true, true),
(410, 1, 'CASH_FLOW_MENU',         'QUESTION','Cash flow assistance',
  'Please choose how we can assist you.', true, true, true),
(420, 1, 'LOG_ASSISTANCE_REQUEST', 'ACTION',  'Log assistance request',
  'Logs which assistance option the customer picked (simulated CRM call)', false, true, false),
(430, 1, 'END_CASH_FLOW',          'END',     'Cash flow follow-up',
  'Thank you for sharing. We will remind you again in a few days. Thank you for your time.', false, false, false),

(500, 1, 'UNAWARE_ACK',            'MESSAGE', 'Unaware ack',
  'No worries. Let us help you understand. You can choose to know more about:', false, true, true),
(510, 1, 'INFO_MENU',              'QUESTION','AMB info menu',
  'You can choose to know more about:', true, true, true),
(520, 1, 'MAINTAIN_NOW',           'QUESTION','Maintain now?',
  'Would you like to maintain the required AMB now?', true, true, true),
(530, 1, 'LOG_AWARENESS_REMINDER', 'ACTION',  'Log awareness reminder',
  'Logs that the customer was educated and wants a later reminder (simulated CRM call)', false, true, false),
(540, 1, 'END_AWARENESS_REMINDER', 'END',     'Awareness reminder set',
  'Thank you for your time. We will remind you again in a few days.', false, false, false),

(600, 1, 'CHURN_ACK',              'MESSAGE', 'Churn ack',
  'We''re sorry to hear that. May we know the reason?', false, true, true),
(610, 1, 'CHURN_REASON_MENU',      'QUESTION','Reason for leaving',
  'May we know the reason?', true, true, true),
(620, 1, 'CHURN_REASON_OTHER',     'INPUT',   'Other reason',
  'Please tell us more.', true, true, true),
(640, 1, 'CONFIRM_CLOSURE',        'QUESTION','Confirm closure',
  'Would you like to close this account?', true, true, true),
(650, 1, 'RECORD_CLOSURE_REQUEST', 'ACTION',  'Record closure request',
  'Updates CRM with the account-closure request (simulated)', false, true, false),
(660, 1, 'END_CLOSURE_INITIATED',  'END',     'Closure initiated',
  'Thank you for sharing. If you still wish to close the account, we will guide you through the process. Thank you for your time.', false, false, false),
(670, 1, 'RECORD_RETENTION',       'ACTION',  'Record retention',
  'Updates CRM noting the customer wants to retain the account (simulated)', false, true, false),
(680, 1, 'END_RETAINED',           'END',     'Retained',
  'Thank you for sharing. Thank you for your time.', false, false, false);

-- ============================================================
-- 5. Transitions (47)
-- ============================================================

INSERT INTO workflow_transition (from_node_id, event_code, option_index, option_label, to_node_id, display_order) VALUES
(100, 'AUTO',    NULL, NULL,                                          110, 1),
(110, 'AUTO',    NULL, NULL,                                          120, 1),

(120, 'OPTION',  1,    'I will fund my account today',                200, 1),
(120, 'OPTION',  2,    'I expect funds shortly',                      300, 2),
(120, 'OPTION',  3,    'I''m facing temporary cash flow constraints', 400, 3),
(120, 'OPTION',  4,    'I wasn''t aware of the balance requirement',  500, 4),
(120, 'OPTION',  5,    'I no longer actively use this account',      600, 5),

(200, 'AUTO',    NULL, NULL,                                          210, 1),
(210, 'SUCCESS', NULL, NULL,                                          220, 1),
(210, 'FAILURE', NULL, NULL,                                          230, 2),

(300, 'AUTO',    NULL, NULL,                                          310, 1),
(310, 'OPTION',  1,    'Within 3 days',                                340, 1),
(310, 'OPTION',  2,    'Within 7 days',                                340, 2),
(310, 'OPTION',  3,    'Within 15 days',                               340, 3),
(310, 'OPTION',  4,    'Choose another date',                         330, 4),
(330, 'AUTO',    NULL, NULL,                                          340, 1),
(340, 'SUCCESS', NULL, NULL,                                          350, 1),
(340, 'FAILURE', NULL, NULL,                                          120, 2),

(400, 'AUTO',    NULL, NULL,                                          410, 1),
(410, 'OPTION',  1,    'Remind me later',                             420, 1),
(410, 'OPTION',  2,    'Explore another account',                     420, 2),
(410, 'OPTION',  3,    'Speak to an executive',                       420, 3),
(410, 'OPTION',  4,    'Understand applicable charges',               420, 4),
(420, 'SUCCESS', NULL, NULL,                                          430, 1),
(420, 'FAILURE', NULL, NULL,                                          120, 2),

(500, 'AUTO',    NULL, NULL,                                          510, 1),
(510, 'OPTION',  1,    'Balance requirement',                         520, 1),
(510, 'OPTION',  2,    'Applicable charges',                          520, 2),
(510, 'OPTION',  3,    'Easy ways to maintain balance',                520, 3),
(510, 'OPTION',  4,    'Account benefits',                            520, 4),
(520, 'YES',     NULL, 'Yes, I will fund now',                        200, 1),
(520, 'NO',      NULL, 'Remind me later',                             530, 2),
(530, 'SUCCESS', NULL, NULL,                                          540, 1),
(530, 'FAILURE', NULL, NULL,                                          120, 2),

(600, 'AUTO',    NULL, NULL,                                          610, 1),
(610, 'OPTION',  1,    'Salary moved elsewhere',                      640, 1),
(610, 'OPTION',  2,    'Better offer elsewhere',                      640, 2),
(610, 'OPTION',  3,    'Account no longer needed',                    640, 3),
(610, 'OPTION',  4,    'Service concern',                             640, 4),
(610, 'OPTION',  5,    'Other (Please specify)',                      620, 5),
(620, 'AUTO',    NULL, NULL,                                          640, 1),
(640, 'YES',     NULL, 'Yes, please close',                           650, 1),
(640, 'NO',      NULL, 'No, I want to retain',                        670, 2),
(650, 'SUCCESS', NULL, NULL,                                          660, 1),
(650, 'FAILURE', NULL, NULL,                                          120, 2),
(670, 'SUCCESS', NULL, NULL,                                          680, 1),
(670, 'FAILURE', NULL, NULL,                                          120, 2);

-- ============================================================
-- 6. Action configs (6)
-- ============================================================

INSERT INTO workflow_action_config (node_id, endpoint, http_method, request_template, response_mapping, timeout_ms, on_success_event, on_failure_event) VALUES
(210, '/accounts/amb-check',        'POST', '{customer_id}',                                          '{balance_ok: $.sufficientBalance}', 5000, 'SUCCESS', 'FAILURE'),
(340, '/crm/reminders',             'POST', '{customer_id, remind_on: reminder_date}',                '{reminder_id: $.id}',               5000, 'SUCCESS', 'FAILURE'),
(420, '/crm/assistance-requests',   'POST', '{customer_id, assistance_type}',                         '{request_id: $.id}',                5000, 'SUCCESS', 'FAILURE'),
(530, '/crm/reminders',             'POST', '{customer_id, reason: ''AMB_AWARENESS''}',               '{reminder_id: $.id}',               5000, 'SUCCESS', 'FAILURE'),
(650, '/crm/closure-requests',      'POST', '{customer_id, reason}',                                  '{request_id: $.id}',                5000, 'SUCCESS', 'FAILURE'),
(670, '/crm/preferences',           'POST', '{customer_id, preference: ''RETAIN_ACCOUNT''}',          '{}',                                 5000, 'SUCCESS', 'FAILURE');

-- ============================================================
-- 7. Entry point
-- ============================================================

INSERT INTO workflow_entry_point (entry_code, workflow_version_id, start_node_id, valid_from, valid_to)
VALUES ('AMB_SHORTFALL_Q2', 1, 100, '2026-07-01', '2026-09-30');

COMMIT;

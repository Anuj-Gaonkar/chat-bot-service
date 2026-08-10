-- AMB journey v2: aligns the DB with the customer's updated WhatsApp script.
-- Truncate + full reseed of definition tables (same approach as the 2026-08-06 full-journey
-- rebuild) - the graph shape changed enough (nodes added/removed/repurposed across every
-- branch) that incremental UPDATE/DELETE statements would be harder to get right than a clean
-- reseed matching WorkflowSeeder.java exactly.
--
-- Key changes from the previous version:
--  1. Branch 1 (fund now): adds a real GENERATE_FUND_LINK action before the ack, ack message
--     now includes the {{payment_link}}. Still a one-way ack - no funded/not-funded branch.
--  2. Branch 2 (funds shortly): drops the "Choose another date" custom-date option - only the
--     3 fixed windows remain.
--  3. Branch 3 (cash flow): drops "Explore another account". "Speak to an executive" and
--     "Understand application charges" are now distinct endings instead of sharing one action.
--  4. Branch 4 (unaware): drops the "would you like to maintain now?" follow-up question -
--     every option ends directly in a redirect. Adds a reserved ACCOUNT_UPGRADE_JOURNEY
--     placeholder for "Upgrade benefits" (secondary account).
--  5. Branch 5 (churn): drops the shared "close account? yes/no" question - all 5 reasons end
--     in their own distinct response. "Service concern" and "Other" both offer a "Request a
--     callback" button (an ordinary single-option QUESTION node) sharing one logging action.

BEGIN;

TRUNCATE TABLE workflow_session_event, workflow_session,
  workflow_action_config, workflow_entry_point, workflow_transition,
  workflow_node, workflow_version, workflow RESTART IDENTITY;

-- ============================================================
-- 1. Workflow + version
-- ============================================================

INSERT INTO workflow (workflow_id, name) VALUES (1, 'AMB shortfall outreach');

INSERT INTO workflow_version (workflow_version_id, workflow_id, version_number, status, start_node_id, created_at, published_at)
VALUES (1, 1, 1, 'PUBLISHED', 100, now(), now());

-- ============================================================
-- 2. Nodes (28)
-- ============================================================

INSERT INTO workflow_node (node_id, workflow_version_id, node_code, node_type, title, message, back_allowed, exit_allowed, home_allowed) VALUES
(100, 1, 'START',                    'START',   'Start',
  'System entry - triggered by the AMB-shortfall batch job', false, true, false),
(110, 1, 'INTRO',                    'MESSAGE', 'Intro',
  'You are on the HDFC Bank WhatsApp Chat. We need to share some urgent and important information about AMB maintenance in your Savings Account.

Your balance maintenance in your Savings Account is currently low.

To help you continue enjoying all account benefits and avoid AMB non-maintenance charges in future, we would want you to fund your account appropriately. Please let us know how you would like to proceed.', false, true, false),
(120, 1, 'AMB_MENU',                 'QUESTION','AMB menu',
  'What would you like to do?', true, true, true),

(200, 1, 'GENERATE_FUND_LINK',       'ACTION',  'Generate fund link',
  'Calls the payment gateway to create a secured funding link (simulated)', false, true, false),
(205, 1, 'FUND_TODAY_ACK',           'END',     'Fund today ack',
  'Thank you! Please fund your account by clicking on the below attached secured link. This will help you avoid AMB non-maintenance charges. {{payment_link}}', false, false, false),
(210, 1, 'CHECK_FUNDING_STATUS',     'ACTION',  'Check funding status',
  'Rechecks whether the required AMB balance has been received (simulated 24-hour recheck)', false, true, false),
(220, 1, 'END_FUNDED',               'END',     'Funded',
  'Thank you! We have received the required balance. Your account benefits continue as usual.', false, false, false),
(230, 1, 'END_FUND_PENDING',         'END',     'Fund pending',
  'We haven''t received the required balance yet. We will remind you again in a few days. Thank you for your time.', false, false, false),

(300, 1, 'FUNDS_SHORTLY_ACK',        'MESSAGE', 'Funds shortly ack',
  'Thank you for letting us know.', false, true, true),
(310, 1, 'FUNDS_TIMING',             'QUESTION','Funds timing',
  'When do you expect the funds?', true, true, true),
(340, 1, 'SCHEDULE_FUNDS_REMINDER',  'ACTION',  'Schedule funds reminder',
  'Creates a CRM follow-up reminder for the expected funding date (simulated)', false, true, false),
(350, 1, 'END_FUNDS_REMINDER',       'END',     'Funds reminder set',
  'We will send you a reminder one day before the expected date. Thank you!', false, false, false),

(400, 1, 'CASH_FLOW_ACK',            'MESSAGE', 'Cash flow ack',
  'We understand situations can be challenging.', false, true, true),
(410, 1, 'CASH_FLOW_MENU',           'QUESTION','Cash flow assistance',
  'Please choose how we can assist you.', true, true, true),
(420, 1, 'ROUTE_TO_EXECUTIVE',       'ACTION',  'Route to executive',
  'Connects the customer with an executive for assistance (simulated CRM handoff)', false, true, false),
(425, 1, 'CHARGES_INFO_REDIRECT',    'END',     'Charges info redirect',
  'You can learn more about applicable charges on our website or at your nearest branch.', false, false, false),
(430, 1, 'END_EXECUTIVE_HANDOFF',    'END',     'Executive handoff',
  'Thank you! We''re connecting you with an executive who will assist you shortly.', false, false, false),

(500, 1, 'UNAWARE_ACK',              'MESSAGE', 'Unaware ack',
  'No worries. Let us help you understand.', false, true, true),
(510, 1, 'INFO_MENU',                'QUESTION','AMB info menu',
  'You can choose to know more about:', true, true, true),
(515, 1, 'INFO_WEBSITE_REDIRECT',    'END',     'Info website redirect',
  'You can learn more about this on our website or by visiting your nearest branch.', false, false, false),
(520, 1, 'ACCOUNT_UPGRADE_JOURNEY',  'END',     'Account upgrade journey',
  'Thank you for your interest - our team will reach out separately to help you explore account upgrade options for this account.', false, false, false),

(600, 1, 'CHURN_ACK',                'MESSAGE', 'Churn ack',
  'We''re sorry to hear that.', false, true, true),
(610, 1, 'CHURN_REASON_MENU',        'QUESTION','Reason for leaving',
  'May we know the reason?', true, true, true),
(620, 1, 'CHURN_REASON_OTHER',       'INPUT',   'Other reason',
  'Please tell us more.', true, true, true),
(630, 1, 'CONVERT_SALARY_ACCOUNT',   'ACTION',  'Convert to salary account',
  'Notifies an executive to help convert this into a Salary Account (simulated)', false, true, false),
(631, 1, 'END_SALARY_ACCOUNT_OFFER', 'END',     'Salary account offer',
  'Thank you! We''re connecting you with an executive to help convert this into a Salary Account.', false, false, false),
(632, 1, 'END_RETENTION_APPEAL',     'END',     'Retention appeal',
  'Thank you for your response. We would still like to serve you - please maintain your AMB balance to avoid any charges.', false, false, false),
(634, 1, 'END_VISIT_BRANCH',         'END',     'Visit branch',
  'Thank you for your response. Please visit the branch for any other details.', false, false, false),
(636, 1, 'SERVICE_CONCERN_CALLBACK', 'QUESTION','Service concern callback',
  'Thank you for your response. Request a callback from the branch.', true, true, true),
(637, 1, 'OTHER_CONCERN_CALLBACK',   'QUESTION','Other concern callback',
  'Thank you for your response. Request a callback for any concern.', true, true, true),
(638, 1, 'LOG_CALLBACK_REQUEST',     'ACTION',  'Log callback request',
  'Logs a callback request for the branch to action (simulated CRM call)', false, true, false),
(639, 1, 'END_CALLBACK_LOGGED',      'END',     'Callback logged',
  'Thank you, we''ve logged your request. Someone will call you back shortly.', false, false, false);

-- ============================================================
-- 3. Transitions (39)
-- ============================================================

INSERT INTO workflow_transition (from_node_id, event_code, option_index, option_label, to_node_id, display_order) VALUES
(100, 'AUTO',    NULL, NULL,                                          110, 1),
(110, 'AUTO',    NULL, NULL,                                          120, 1),

(120, 'OPTION',  1,    'I will fund my account now',                  200, 1),
(120, 'OPTION',  2,    'I expect funds shortly',                      300, 2),
(120, 'OPTION',  3,    'I''m facing temporary cash flow constraints', 400, 3),
(120, 'OPTION',  4,    'I wasn''t aware of the balance requirement',  500, 4),
(120, 'OPTION',  5,    'I no longer actively use this account',      600, 5),

(200, 'SUCCESS', NULL, NULL,                                          205, 1),
(200, 'FAILURE', NULL, NULL,                                          120, 2),

(300, 'AUTO',    NULL, NULL,                                          310, 1),
(310, 'OPTION',  1,    'Within 3 days',                                340, 1),
(310, 'OPTION',  2,    'Within 7 days',                                340, 2),
(310, 'OPTION',  3,    'Within 15 days',                               340, 3),
(340, 'SUCCESS', NULL, NULL,                                          350, 1),
(340, 'FAILURE', NULL, NULL,                                          120, 2),

(400, 'AUTO',    NULL, NULL,                                          410, 1),
(410, 'OPTION',  1,    'Remind me later',                             310, 1),
(410, 'OPTION',  2,    'Speak to an executive',                       420, 2),
(410, 'OPTION',  3,    'Understand application charges',              425, 3),
(420, 'SUCCESS', NULL, NULL,                                          430, 1),
(420, 'FAILURE', NULL, NULL,                                          120, 2),

(500, 'AUTO',    NULL, NULL,                                          510, 1),
(510, 'OPTION',  1,    'AMB charges',                                 515, 1),
(510, 'OPTION',  2,    'Easy ways to maintain balance',                515, 2),
(510, 'OPTION',  3,    'Upgrade benefits in case this is your secondary account', 520, 3),

(600, 'AUTO',    NULL, NULL,                                          610, 1),
(610, 'OPTION',  1,    'Salary moved elsewhere',                      630, 1),
(610, 'OPTION',  2,    'Better offer elsewhere',                      632, 2),
(610, 'OPTION',  3,    'Account no longer needed',                    634, 3),
(610, 'OPTION',  4,    'Service concern',                             636, 4),
(610, 'OPTION',  5,    'Other (Please specify)',                      620, 5),
(620, 'AUTO',    NULL, NULL,                                          637, 1),
(630, 'SUCCESS', NULL, NULL,                                          631, 1),
(630, 'FAILURE', NULL, NULL,                                          120, 2),
(636, 'OPTION',  1,    'Request a callback',                          638, 1),
(637, 'OPTION',  1,    'Request a callback',                          638, 1),
(638, 'SUCCESS', NULL, NULL,                                          639, 1),
(638, 'FAILURE', NULL, NULL,                                          120, 2);

-- ============================================================
-- 4. Action configs (6)
-- ============================================================

INSERT INTO workflow_action_config (node_id, endpoint, http_method, request_template, response_mapping, timeout_ms, on_success_event, on_failure_event) VALUES
(200, '/payments/links',              'POST', '{customer_id}',                           '{payment_link: $.link}',            5000, 'SUCCESS', 'FAILURE'),
(210, '/accounts/amb-check',          'POST', '{customer_id}',                           '{balance_ok: $.sufficientBalance}',  5000, 'SUCCESS', 'FAILURE'),
(340, '/crm/reminders',               'POST', '{customer_id, remind_on: reminder_date}', '{reminder_id: $.id}',               5000, 'SUCCESS', 'FAILURE'),
(420, '/crm/executive-handoff',       'POST', '{customer_id, assistance_type}',          '{handoff_id: $.id}',                5000, 'SUCCESS', 'FAILURE'),
(630, '/crm/salary-account-conversion','POST', '{customer_id}',                          '{request_id: $.id}',                5000, 'SUCCESS', 'FAILURE'),
(638, '/crm/callback-requests',       'POST', '{customer_id, reason}',                   '{callback_id: $.id}',               5000, 'SUCCESS', 'FAILURE');

-- ============================================================
-- 5. Entry point
-- ============================================================

INSERT INTO workflow_entry_point (entry_code, workflow_version_id, start_node_id, valid_from, valid_to)
VALUES ('AMB_SHORTFALL_Q2', 1, 100, '2026-07-01', '2026-09-30');

COMMIT;

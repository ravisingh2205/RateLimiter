INSERT INTO api_endpoint (api_id, http_method, path_pattern, description) VALUES
 ('auth.login',         'POST', '/api/auth/login',         'Login (brute-force sensitive)'),
 ('employees.list',     'GET',  '/api/employees',          'List / search employees (read heavy)'),
 ('payments.create',    'POST', '/api/payments',           'Create a payment (money moving)'),
 ('reports.export',     'POST', '/api/reports/export',     'Heavy report export'),
 ('notifications.send', 'POST', '/api/notifications/send', 'Send SMS / email via a third-party provider');

INSERT INTO rate_limit_plan (name, description) VALUES
 ('FREE',     'Entry tier'),
 ('STANDARD', 'Default paid tier (uses the default rules)'),
 ('PREMIUM',  'High volume tier');

-- Columns: api_id, plan_id, dimension, algorithm, limit_count, window_seconds, burst_capacity, priority, fail_mode
INSERT INTO rate_limit_rule (api_id, plan_id, dimension, algorithm, limit_count, window_seconds, burst_capacity, priority, fail_mode) VALUES
 -- safety net for EVERY api: 1200 req/min per IP, burst 200
 ('*',                  NULL, 'IP',     'TOKEN_BUCKET',   1200, 60,    200,  100, 'FAIL_OPEN'),

 -- 1. login: strict sliding window, fail closed
 ('auth.login',         NULL, 'IP',     'SLIDING_WINDOW', 20,   300,   NULL, 10,  'FAIL_CLOSED'),
 ('auth.login',         NULL, 'EMP',    'SLIDING_WINDOW', 5,    300,   NULL, 20,  'FAIL_CLOSED'),

 -- 2. employees: token bucket per employee and per department, plan-aware department limit
 ('employees.list',     NULL, 'EMP',    'TOKEN_BUCKET',   100,  60,    20,   10,  'FAIL_OPEN'),
 ('employees.list',     NULL, 'DEPT',   'TOKEN_BUCKET',   2000, 60,    200,  20,  'FAIL_OPEN'),
 ('employees.list',     (SELECT plan_id FROM rate_limit_plan WHERE name = 'FREE'),
                             'DEPT',   'TOKEN_BUCKET',   300,  60,    30,   20,  'FAIL_OPEN'),
 ('employees.list',     (SELECT plan_id FROM rate_limit_plan WHERE name = 'PREMIUM'),
                             'DEPT',   'TOKEN_BUCKET',   10000, 60,   1000, 20,  'FAIL_OPEN'),

 -- 3. payments: token bucket (emp, dept, global) + concurrency per employee, fail closed
 ('payments.create',    NULL, 'EMP',    'TOKEN_BUCKET',   20,   60,    5,    10,  'FAIL_CLOSED'),
 ('payments.create',    NULL, 'DEPT',   'TOKEN_BUCKET',   500,  60,    50,   20,  'FAIL_CLOSED'),
 ('payments.create',    NULL, 'GLOBAL', 'TOKEN_BUCKET',   5000, 60,    500,  30,  'FAIL_CLOSED'),
 ('payments.create',    NULL, 'EMP',    'CONCURRENCY',    3,    30,    NULL, 90,  'FAIL_CLOSED'),

 -- 4. report export: concurrency + daily quota per department
 ('reports.export',     NULL, 'DEPT',   'FIXED_WINDOW',   50,   86400, NULL, 10,  'FAIL_OPEN'),
 ('reports.export',     NULL, 'DEPT',   'CONCURRENCY',    2,    60,    NULL, 90,  'FAIL_OPEN'),

 -- 5. notifications: leaky bucket per department and global (drain 10/s and 50/s)
 ('notifications.send', NULL, 'DEPT',   'LEAKY_BUCKET',   10,   1,     20,   10,  'FAIL_OPEN'),
 ('notifications.send', NULL, 'GLOBAL', 'LEAKY_BUCKET',   50,   1,     100,  20,  'FAIL_OPEN');

INSERT INTO subject_plan (dimension, subject_value, plan_id) VALUES
 ('DEPT', 'D1', (SELECT plan_id FROM rate_limit_plan WHERE name = 'FREE')),
 ('DEPT', 'D2', (SELECT plan_id FROM rate_limit_plan WHERE name = 'STANDARD')),
 ('DEPT', 'D7', (SELECT plan_id FROM rate_limit_plan WHERE name = 'PREMIUM'));

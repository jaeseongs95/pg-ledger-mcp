-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Keep existing grants valid while allowing individual permissions to be stored.
ALTER TABLE session_grant DROP CONSTRAINT ck_session_grant_scope;
ALTER TABLE session_grant ADD CONSTRAINT ck_session_grant_scope CHECK (grant_scope IN (
  'ISSUE', 'SPEC', 'TASK', 'DELIVERABLE', 'VERIFY',
  'ISSUE_CREATE', 'ISSUE_UPDATE', 'SPEC_CREATE', 'SPEC_UPDATE',
  'TASK_CREATE', 'TASK_UPDATE', 'CRITERION_CREATE', 'CRITERION_UPDATE',
  'DELIVERABLE_ADD', 'DELIVERABLE_EDIT', 'VERSION_CREATE', 'VERSION_UPDATE'
));

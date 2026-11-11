-- V3: counters, the code revision and the common codes of a fresh ledger. The category list starts empty
-- (categories belong to the project and are added with code-set).

INSERT INTO id_counter (name, issued_no) VALUES ('HISTORY', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('DEFECT_FIX', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('DEFECT_CASE', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('SESSION_GRANT', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('SESSION_KEY', 0);
INSERT INTO id_counter (name, issued_no) VALUES ('OWNER_AUDIT', 0);

INSERT INTO code_revision (id, revision) VALUES (1, 0);

INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DEFECT_SEVERITY', 'P1', '치명', 'Critical', '사용자 작업 차단 또는 거짓 완료 보고', 'Blocks the user''s work or reports false completion', 1, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DEFECT_SEVERITY', 'P2', '중대', 'Major', '우회 가능한 잘못된 결과 또는 작업 손실', 'Wrong result or lost work with a workaround', 2, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DEFECT_SEVERITY', 'P3', '경미', 'Minor', '불편, 비용 또는 문구 문제', 'Inconvenience, cost or wording', 3, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('DEFECT_SEVERITY', 'UNRATED', '미분류', 'Unrated', '기존 행 전용, 다음 변경에 P1/P2/P3 지정 필요', 'Legacy only; rate P1, P2 or P3 on the next write', 9, 'INACTIVE', 'user:owner');

INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_site', '동일 위치', 'Same site', '코드 또는 제품의 같은 위치', 'Same place in the code or product', 1, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'caused_by', '원인 관계', 'Caused by', 'a의 원인은 b', 'a is caused by b', 2, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'blocks', '차단', 'Blocks', 'a가 b를 차단', 'a blocks b', 3, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_family', '동일 계열', 'Same family', '근본 원인이 다른 유사 결함', 'Similar defects with different root causes', 4, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'same_fix_unit', '동일 수정 단위', 'Same fix unit', '하나의 변경에서 함께 수정', 'Fixed together in one change', 5, 'ACTIVE', 'user:owner');
INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state, created_by)
VALUES ('RELATION_KIND', 'regression_of', '재발(회귀)', 'Regression of', 'b는 검증까지 끝난 a가 다시 나타난 것(나중 번호가 재발)', 'b is a recurrence of a after a was verified (the later id is the recurrence)', 6, 'ACTIVE', 'user:owner');

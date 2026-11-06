-- Only category, severity and relation kinds are extensible common codes. Other CHECKs remain fixed.
\set ON_ERROR_STOP on
BEGIN;
SET ROLE ledger_owner;
DO $m$
DECLARE r record; v_con text; n int;
BEGIN
  IF EXISTS (SELECT 1 FROM ledger._migration WHERE step = 'common_code') THEN RETURN; END IF;
  CREATE TABLE ledger.common_code (
    code_group text NOT NULL CHECK (code_group IN ('DEFECT_CATEGORY','DEFECT_SEVERITY','RELATION_KIND')),
    code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    name_ko text NOT NULL CHECK (length(btrim(name_ko)) BETWEEN 1 AND 200),
    name_en text NOT NULL CHECK (length(btrim(name_en)) BETWEEN 1 AND 200),
    description_ko text NOT NULL CHECK (length(description_ko) <= 2000),
    description_en text NOT NULL CHECK (length(description_en) <= 2000),
    sort_order int NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY (code_group, code)
  );
  CREATE TABLE ledger.common_code_use (
    table_name text NOT NULL, column_name text NOT NULL, code_group text NOT NULL,
    always_active boolean NOT NULL DEFAULT false, PRIMARY KEY (table_name, column_name)
  );
  INSERT INTO ledger.common_code (code_group,code,name_ko,name_en,description_ko,description_en,sort_order,active) VALUES
    ('DEFECT_SEVERITY','P1','치명','Critical','사용자 작업 차단 또는 거짓 완료 보고','Blocks the user''s work or reports false completion',1,true),
    ('DEFECT_SEVERITY','P2','중대','Major','우회 가능한 잘못된 결과 또는 작업 손실','Wrong result or lost work with a workaround',2,true),
    ('DEFECT_SEVERITY','P3','경미','Minor','불편, 비용 또는 문구 문제','Inconvenience, cost or wording',3,true),
    ('DEFECT_SEVERITY','UNRATED','미분류','Unrated','기존 행 전용, 다음 변경에 P1/P2/P3 지정 필요','Legacy only; rate P1, P2 or P3 on the next write',9,false),
    ('RELATION_KIND','same_site','동일 위치','Same site','코드 또는 제품의 같은 위치','Same place in the code or product',1,true),
    ('RELATION_KIND','caused_by','원인 관계','Caused by','a의 원인은 b','a is caused by b',2,true),
    ('RELATION_KIND','blocks','차단','Blocks','a가 b를 차단','a blocks b',3,true),
    ('RELATION_KIND','same_family','동일 계열','Same family','근본 원인이 다른 유사 결함','Similar defects with different root causes',4,true),
    ('RELATION_KIND','same_fix_unit','동일 수정 단위','Same fix unit','하나의 변경에서 함께 수정','Fixed together in one change',5,true);
  -- Preserve Korean source text. Unknown category codes copy it into their English fallback.
  INSERT INTO ledger.common_code (code_group,code,name_ko,name_en,description_ko,description_en)
  SELECT 'DEFECT_CATEGORY',c.code,c.name,coalesce(e.name,c.name),c.definition,coalesce(e.description,c.definition)
  FROM ledger.category c LEFT JOIN (VALUES
    ('ARC','Structure and dependency direction','The core depends on outer layers or a contract is tied to a concrete implementation (SOLID).'),
    ('CON','Concurrency and atomicity','Races, retries, locks or leases cause an incorrect state.'),
    ('DAT','Data loss and recovery','Data or backups disappear, or recovery material is not retained.'),
    ('DENY','False refusal and excessive blocking','Valid input, calls or states are blocked or judged failures, stopping normal operation (the opposite of GATE).'),
    ('DIAG','Incorrect diagnosis','Guidance, fixed messages or status displays do not match the actual cause.'),
    ('DOC','Documentation and contract contradictions','Documentation, schemas or descriptions differ from actual behavior.'),
    ('EXC','Error cause suppression or substitution','Swallowed exceptions or substituted causes or codes hide the actual failure from the caller.'),
    ('GATE','Gate bypass and false PASS','Verification, gates or minimum requirements pass without meeting their actual conditions.'),
    ('HOST','Host extensibility','Behavior is missing on a new or different host, or is handled through another host path.'),
    ('PKG','Build and distribution artifacts','Distributions, dist output or package contents differ from the source or are missing.'),
    ('RES','Resource limits','Workload, size or time limits are absent or do not impose an actual bound.'),
    ('SEC','Authorization and trust boundaries','Authorization, identity or path boundaries are bypassed, or untrusted values are trusted.'),
    ('TST','Test gaps and false passes','Tests fail to detect defects or encode defective behavior as the expected result.'),
    ('VND','Vendor and model name coupling','Behavior depends on preregistered vendor, model or host names, or name literals (SOLID O and D).')
  ) e(code,name,description) ON e.code=c.code;
  INSERT INTO ledger.common_code_use VALUES
    ('defect','category','DEFECT_CATEGORY',false),
    ('defect','severity','DEFECT_SEVERITY',true),
    ('defect_relation','kind','RELATION_KIND',false);
  FOR r IN SELECT * FROM ledger.common_code_use LOOP
    EXECUTE format('SELECT count(*) FROM ledger.%I t WHERE t.%I IS NOT NULL AND NOT EXISTS
      (SELECT 1 FROM ledger.common_code c WHERE c.code_group=$1 AND c.code=t.%I)',r.table_name,r.column_name,r.column_name)
      INTO n USING r.code_group;
    IF n <> 0 THEN RAISE EXCEPTION 'common_code: % row(s) of %.% missing from group %',n,r.table_name,r.column_name,r.code_group; END IF;
  END LOOP;
  FOR r IN SELECT * FROM ledger.common_code_use WHERE column_name <> 'category' LOOP
    FOR v_con IN SELECT c.conname FROM pg_constraint c
      WHERE c.conrelid=format('ledger.%I',r.table_name)::regclass AND c.contype='c'
        AND c.conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=format('ledger.%I',r.table_name)::regclass AND attname=r.column_name)] LOOP
      EXECUTE format('ALTER TABLE ledger.%I DROP CONSTRAINT %I',r.table_name,v_con);
    END LOOP;
  END LOOP;
  SELECT string_agg(conname,',') INTO v_con FROM pg_constraint WHERE conrelid='ledger.defect'::regclass AND confrelid='ledger.category'::regclass;
  IF v_con IS NULL OR v_con LIKE '%,%' THEN RAISE EXCEPTION 'common_code: expected one category foreign key, found %',v_con; END IF;
  EXECUTE format('ALTER TABLE ledger.defect DROP CONSTRAINT %I',v_con);
  DROP TABLE ledger.category;
  CREATE VIEW ledger.category AS SELECT code,name_ko AS name,description_ko AS definition
    FROM ledger.common_code WHERE code_group='DEFECT_CATEGORY';
  INSERT INTO ledger._migration(step,detail) VALUES ('common_code',jsonb_build_object('by',session_user));
END $m$;
RESET ROLE;
COMMIT;

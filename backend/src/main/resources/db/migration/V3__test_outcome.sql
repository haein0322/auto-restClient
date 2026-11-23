-- 케이스 결과 구분: PASS / FAIL / SKIPPED, 실행 단위의 AI 원인 분석 결과(JSON)
ALTER TABLE test_case_result ADD COLUMN outcome VARCHAR(10) DEFAULT 'FAIL' NOT NULL;
ALTER TABLE test_run ADD COLUMN diagnosis_json CLOB;

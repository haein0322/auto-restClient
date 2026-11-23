-- 샘플 생성 출처: RULE(규칙 기반), AI, FALLBACK(AI 실패 후 규칙 기반 대체), USER(사용자 편집)
ALTER TABLE sample_data ADD COLUMN source VARCHAR(20) DEFAULT 'RULE' NOT NULL;

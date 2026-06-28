CREATE TABLE activity_logs (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id),
    action     VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 분석 쿼리: 24h 윈도우 + action별 집계
CREATE INDEX idx_activity_logs_action_created ON activity_logs (action, created_at);
CREATE INDEX idx_activity_logs_user_id        ON activity_logs (user_id);

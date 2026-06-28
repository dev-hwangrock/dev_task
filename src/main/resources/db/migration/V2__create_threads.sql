CREATE TABLE threads (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_chat_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 유저별 스레드 조회 + 30분 규칙 판정용 (user_id + last_chat_at DESC)
CREATE INDEX idx_threads_user_last_chat ON threads (user_id, last_chat_at DESC);

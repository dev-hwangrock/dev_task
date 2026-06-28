CREATE TABLE feedbacks (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    chat_id     UUID        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    is_positive BOOLEAN     NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 한 사용자는 한 대화에 하나의 피드백만
    CONSTRAINT uq_feedback_user_chat UNIQUE (user_id, chat_id)
);

CREATE INDEX idx_feedbacks_user_id     ON feedbacks (user_id);
CREATE INDEX idx_feedbacks_created_at  ON feedbacks (created_at);
CREATE INDEX idx_feedbacks_is_positive ON feedbacks (is_positive);

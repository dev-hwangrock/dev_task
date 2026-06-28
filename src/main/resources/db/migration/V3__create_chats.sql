CREATE TABLE chats (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id  UUID        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id),
    question   TEXT        NOT NULL,
    answer     TEXT        NOT NULL,
    model      VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chats_thread_id  ON chats (thread_id, created_at ASC);
CREATE INDEX idx_chats_user_id    ON chats (user_id);
CREATE INDEX idx_chats_created_at ON chats (created_at);

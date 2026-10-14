-- Esquema base (borrador, ver README del proyecto, sección 6).
CREATE TABLE tenant (
    id            UUID PRIMARY KEY,
    name          TEXT        NOT NULL,
    api_key_hash  TEXT        NOT NULL,
    webhook_url   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE trust_profile (
    id       UUID PRIMARY KEY,
    name     TEXT  NOT NULL UNIQUE,
    sources  JSONB NOT NULL
);

CREATE TABLE use_case (
    id                UUID PRIMARY KEY,
    tenant_id         UUID    NOT NULL REFERENCES tenant (id),
    key               TEXT    NOT NULL,
    version           INT     NOT NULL,
    dcql              JSONB   NOT NULL,
    classification    TEXT    NOT NULL,
    trust_profile_id  UUID    NOT NULL REFERENCES trust_profile (id),
    retention         TEXT    NOT NULL DEFAULT 'DISCARD',
    i18n              JSONB   NOT NULL DEFAULT '{}',
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, key, version)
);

CREATE TABLE presentation (
    id           TEXT PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    use_case_id  UUID        REFERENCES use_case (id),
    status       TEXT        NOT NULL,
    nonce        TEXT        NOT NULL,
    state        JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX presentation_expires_idx ON presentation (expires_at);

CREATE TABLE presentation_event (
    id               BIGSERIAL PRIMARY KEY,
    presentation_id  TEXT        NOT NULL REFERENCES presentation (id) ON DELETE CASCADE,
    type             TEXT        NOT NULL,
    at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail           JSONB
);

CREATE TABLE webhook_outbox (
    id               BIGSERIAL PRIMARY KEY,
    presentation_id  TEXT        NOT NULL REFERENCES presentation (id) ON DELETE CASCADE,
    payload          JSONB       NOT NULL,
    attempts         INT         NOT NULL DEFAULT 0,
    next_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

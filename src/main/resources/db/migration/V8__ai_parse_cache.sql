-- Identical texts recur in these groups, and an inference costs seconds. The key includes the
-- reference date because "amanha" resolves differently depending on when it was said, and the
-- model because two models do not agree.
create table ai_parse_cache (
    text_hash varchar(64) not null,
    model varchar(64) not null,
    reference_date date not null,
    response_json jsonb not null,
    latency_ms integer check (latency_ms is null or latency_ms >= 0),
    hits integer not null default 0 check (hits >= 0),
    created_at timestamptz not null default current_timestamp,
    last_used_at timestamptz not null default current_timestamp,
    primary key (text_hash, model, reference_date)
);

create index idx_ai_parse_cache_recent on ai_parse_cache (last_used_at desc);

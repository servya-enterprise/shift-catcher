-- `WP-POC-008` is the project's own GO/NO-GO gate, and its two endpoints were specified but never
-- built, so the gate had no way to run. This is the harness: `08-Quality/Benchmark-Plan.md`.

create table benchmark_run (
    id uuid primary key default uuidv7(),
    status varchar(16) not null check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    label varchar(128),
    corpus_size integer not null check (corpus_size >= 0),
    -- Recorded per run because it changes what the numbers mean: the same corpus scored with the
    -- model off measures only the deterministic parser.
    ai_enabled boolean not null,
    started_at timestamptz not null,
    completed_at timestamptz,
    failure text,
    report jsonb,
    created_at timestamptz not null default current_timestamp
);

create index idx_benchmark_run_recent on benchmark_run (started_at desc);

-- One at a time. Two concurrent runs would queue behind the same single-threaded model and report
-- latencies that measure the contention rather than the pipeline.
create unique index uq_benchmark_run_single_active on benchmark_run (status) where status = 'RUNNING';

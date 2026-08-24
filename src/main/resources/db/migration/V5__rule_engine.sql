create table rule_set (
    id uuid primary key default uuidv7(),
    version integer not null unique check (version > 0),
    name varchar(128),
    status varchar(16) not null check (status in ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    definition jsonb not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    activated_at timestamptz,
    superseded_at timestamptz
);

-- `04-Domain/Rule-Engine.md`: a RuleSet is versioned and immutable once active, and exactly one
-- version may be active at a time. The partial index makes that an invariant of the database
-- rather than a promise of the service layer.
create unique index uq_rule_set_single_active on rule_set (status) where status = 'ACTIVE';

create table rule_evaluation (
    id uuid primary key default uuidv7(),
    opportunity_id uuid not null references shift_opportunity (id),
    rule_set_id uuid references rule_set (id),
    rule_set_version integer,
    result varchar(20) not null check (result in ('ELIGIBLE', 'REJECTED', 'REVIEW_REQUIRED')),
    -- Reason codes are enum-like constants without commas, matching the joined-text convention
    -- already used by detection_result.signals and shift_opportunity.ambiguous_fields.
    reasons varchar(1024) not null default '',
    auto_claim_allowed boolean not null default false,
    evaluated_at timestamptz not null,
    created_at timestamptz not null default current_timestamp
);

create index idx_rule_evaluation_opportunity
    on rule_evaluation (opportunity_id, evaluated_at desc);

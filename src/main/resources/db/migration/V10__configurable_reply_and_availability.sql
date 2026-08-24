-- Two changes that the first real user needs and that neither requires nor anticipates
-- multi-tenancy: the reply stops being a constant, and the agenda becomes something the rules can
-- read. See `12-MVP/MVP-Scope.md`.

-- The claim text was frozen as `PEGO` by a check constraint. `00-Start/POC-Freeze.md` fixed that
-- wording for the POC and the transport proof keeps it (`transport_test_reply` is untouched on
-- purpose), but the product needs each user to write her own. The column keeps being written at
-- decision time and never re-derived, exactly like the quote target.
alter table shift_claim
    drop constraint shift_claim_message_check;

alter table shift_claim
    alter column message type varchar(512);

alter table shift_claim
    add constraint ck_shift_claim_message_not_blank check (btrim(message) <> '');

-- A single row, because there is a single user. The primary key is the singleton guard: `id` can
-- only ever be true, so a second row is a constraint violation rather than a convention nobody
-- enforces. When credentials become per-user data (`12-MVP/MVP-Scope.md`) this table gains an owner
-- column and loses the guard.
create table claim_message_setting (
    id boolean primary key default true,
    message varchar(512) not null,
    version integer not null default 0 check (version >= 0),
    updated_at timestamptz not null default current_timestamp,
    constraint ck_claim_message_setting_singleton check (id),
    constraint ck_claim_message_setting_not_blank check (btrim(message) <> '')
);

insert into claim_message_setting (id, message) values (true, 'PEGO');

-- Per-group override. Groups are different hospitals with different customs, and this costs one
-- nullable column instead of a new endpoint: `EP-010` already edits the group.
alter table allowed_group
    add column claim_message varchar(512)
        constraint ck_allowed_group_claim_message
            check (claim_message is null or btrim(claim_message) <> '');

-- The commitments the operator keeps outside this system. Shifts claimed *through* Shift Catcher
-- are not copied here: they are read live from `shift_claim`, so retracting a claim frees the slot
-- again instead of leaving a stale duplicate behind.
create table availability_entry (
    id uuid primary key default uuidv7(),
    shift_date date not null,
    start_time time,
    end_time time,
    ends_next_day boolean not null default false,
    label varchar(128),
    note text,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    -- Half a window cannot be compared with anything, so it is refused at the door rather than
    -- silently degrading every overlap check that touches it.
    constraint ck_availability_entry_window check (
        (start_time is null and end_time is null) or (start_time is not null and end_time is not null)
    ),
    constraint ck_availability_entry_next_day check (ends_next_day = false or start_time is not null)
);

-- The conflict query reads one date and its neighbours, because a shift that ends the next day
-- overlaps a morning it does not share a date with.
create index idx_availability_entry_date on availability_entry (shift_date);

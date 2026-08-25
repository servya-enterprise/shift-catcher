-- Nothing in this system was ever deleted. Two problems, and they pull in the same direction:
-- unbounded growth on a two-vCPU box with one volume, and messages written by people who never
-- consented to being stored and are not users (`12-MVP/MVP-Scope.md`).
--
-- The message chain cannot simply be dropped. `shift_claim` references `shift_opportunity`
-- references `incoming_message` references `incoming_provider_event`, and a claim is the record of
-- a message that really went into a group - deleting that would erase evidence of something the
-- world still remembers. The dedupe key matters too: drop an old event row and a redelivered
-- webhook stops being recognised as a duplicate.
--
-- So the content is redacted in place and the rows stay. That keeps idempotency intact and is the
-- better answer for data protection anyway: what is stored stops being the third party's words
-- while the fact that a message was seen survives.

alter table incoming_provider_event
    add column redacted_at timestamptz;

alter table incoming_message
    add column redacted_at timestamptz;

-- The retention scan reads by age and skips what it has already done.
create index idx_incoming_provider_event_retention
    on incoming_provider_event (webhook_received_at)
    where redacted_at is null;

create index idx_incoming_message_retention
    on incoming_message (received_at)
    where redacted_at is null;

create index idx_outbox_event_completed on outbox_event (completed_at) where status = 'DONE';

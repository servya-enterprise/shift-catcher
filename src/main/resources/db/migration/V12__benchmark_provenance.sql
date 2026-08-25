-- Where the corpus came from decides what its numbers can be used for, so it is recorded with the
-- run rather than remembered by whoever started it.
--
-- Invented messages can fail this system but cannot pass it: they measure the phrasings whoever
-- wrote the corpus thought of, not how the people in that group actually write. A run over a
-- synthetic corpus is a regression floor and a NO-GO detector; it is not GO evidence.
alter table benchmark_run
    add column provenance varchar(16) not null default 'SYNTHETIC'
        check (provenance in ('REAL', 'SYNTHETIC', 'MIXED'));

-- The default exists only to fill the rows already written; new runs must say it out loud.
alter table benchmark_run
    alter column provenance drop default;

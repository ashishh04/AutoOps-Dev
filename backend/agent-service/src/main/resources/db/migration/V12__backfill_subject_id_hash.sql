-- Fill in the hashes nothing was writing.
--
-- V7 added findings.subject_id_hash and wrote the backfill statement as a
-- deliberate no-op, because the table was empty and the pattern was worth
-- having on hand. Nothing then populated it: the column existed on the table
-- and not on the entity, so every finding since has been written with it NULL.
--
-- It surfaced the way this kind of thing does — the first real finding landed
-- and the counter said `unhashed 1`. Nothing failed, because nothing reads the
-- column yet. Two things would have, silently:
--
--   * the reaper joins findings to agent_run_subject on subject_id_hash, and a
--     NULL never matches, so no finding would EVER have been reaped and the
--     backlog would have grown with no error anywhere;
--   * overclaimSuspects counts COUNT(DISTINCT subject_id_hash), and NULLs are
--     not counted — so the one gauge covering the all/dimensional blast-radius
--     gap would have read a confident zero. That is the second independent
--     reason that gauge was dead, after the agent_name join mismatch.
--
-- The UPDATE below is the one V7 rehearsed, now doing real work. It is written
-- against MySQL's own SHA2 rather than in application code deliberately: the
-- two agree byte for byte (SchemaInvariantsIT asserts it on a real server), and
-- a backfill that has to boot the application is a backfill that cannot run
-- during an incident.
--
-- An empty subject_id stays NULL rather than hashing to the well-known digest
-- of the empty string, which would join every subject-less finding to every
-- other one.

UPDATE findings
   SET subject_id_hash = UNHEX(SHA2(subject_id, 256))
 WHERE subject_id_hash IS NULL
   AND subject_id <> '';

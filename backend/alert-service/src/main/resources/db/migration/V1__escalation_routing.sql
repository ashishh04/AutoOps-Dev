-- Where a page went, why, and whether anyone answered.
--
-- alert-service has been a stateless facade over Keep and the incident engine
-- until now, and this is the first thing it genuinely cannot proxy: a routing
-- DECISION is a fact about what this platform did, and it has to survive a
-- restart. The spec's own line is the reason — "retries and restarts
-- double-page without it".
--
-- THE ONE NON-NEGOTIABLE, which this schema is shaped around: never silently
-- drop a page. Every other guardrail is secondary. So unresolvable ownership
-- does not produce an absent row; it produces a row with
-- ownership_source = DEFAULT_FALLBACK and routing_failed = 1, which is loud and
-- queryable. A missing row means the agent never ran; it must never mean "we
-- could not work out where to send it".

-- ---------------------------------------------------------------------------
-- One decision: this incident, at this escalation level, to this target.
-- ---------------------------------------------------------------------------
CREATE TABLE escalation_decisions (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,

    -- The incident engine's id, not ours. Opaque here on purpose: this service
    -- does not own incidents and must not invent a parallel identity for them.
    incident_id         VARCHAR(128) NOT NULL,

    -- 0 = first page. Incremented by escalation, capped by policy.
    escalation_level    SMALLINT     NOT NULL DEFAULT 0,

    -- WHO was paged. Any of the three may be null — a team with no rotation
    -- resolved yet, a channel chosen without a named human — but not all three.
    target_team         VARCHAR(128) NULL,
    target_user         VARCHAR(255) NULL,
    target_channel      ENUM('TICKET','CHAT','PUSH','PHONE','NONE') NOT NULL,

    -- THE IDEMPOTENCY KEY, hashed rather than composite.
    --
    -- The spec's key is hash(incident_id + escalation_level + target), and
    -- target is three fields. A unique index over incident_id(128) +
    -- level + three VARCHARs would be 128+128+255+... characters at 4 bytes
    -- each in utf8mb4 — far past MySQL's 3072-byte key limit, which this
    -- codebase has already hit once (V6, error 1071). sha256 is fixed width and
    -- needs no prefix, so there is no prefix-collision question at volume.
    decision_key        BINARY(32)   NOT NULL,

    -- WHY this target. The resolution order from the spec, in order, and
    -- DEFAULT_FALLBACK is the one that means routing failed.
    ownership_source    ENUM('ANNOTATION','CATALOG','CODEOWNERS',
                             'LAST_DEPLOYER','DEFAULT_FALLBACK') NOT NULL,
    root_cause_service  VARCHAR(255) NULL,
    confidence          DECIMAL(4,3) NULL,
    rationale           TEXT         NULL,

    -- Set whenever ownership could not be resolved and the default path was
    -- used. Separate from ownership_source so that "we fell back" is one
    -- indexed boolean rather than a string comparison somebody gets wrong.
    routing_failed      TINYINT(1)   NOT NULL DEFAULT 0,

    -- SUPPRESSION, and the rule that caused it.
    --
    -- suppression_rule is required when suppressed is set — enforced by CHECK,
    -- because "fully auditable" is worth a constraint rather than a convention.
    -- A suppressed page with no named rule is indistinguishable from a page
    -- that was lost.
    suppressed          TINYINT(1)   NOT NULL DEFAULT 0,
    suppression_rule    VARCHAR(255) NULL,

    -- Timers. Both nullable: a suppressed decision has neither.
    ack_deadline        TIMESTAMP(6) NULL,
    next_escalation_at  TIMESTAMP(6) NULL,

    -- Whether this page landed outside working hours, computed at decision time
    -- against the target's own timezone and STORED. Deriving it later from
    -- created_at would use the server's zone and quietly mislabel every page for
    -- a responder in another country — and off-hours volume is the number that
    -- feeds the fatigue signal.
    off_hours           TINYINT(1)   NOT NULL DEFAULT 0,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_escalation_decision UNIQUE (tenant_id, decision_key),
    CONSTRAINT ck_suppression_has_a_rule
        CHECK (suppressed = 0 OR suppression_rule IS NOT NULL),
    CONSTRAINT ck_target_is_named
        CHECK (target_team IS NOT NULL OR target_user IS NOT NULL)
) ENGINE=InnoDB;

-- Every decision for one incident, newest first: what the timeline view reads.
CREATE INDEX idx_escalation_by_incident
    ON escalation_decisions (tenant_id, incident_id, escalation_level);

-- The fatigue query: pages per responder per week, off-hours separable.
-- Deliberately not a separate counter table — a counter drifts from the rows it
-- counts, and there is no volume here that a GROUP BY cannot carry.
CREATE INDEX idx_escalation_by_responder
    ON escalation_decisions (tenant_id, target_user, created_at);

-- Finding routing failures without scanning: the number somebody should be
-- alerted on, because it means ownership data is missing or wrong.
CREATE INDEX idx_escalation_routing_failed
    ON escalation_decisions (tenant_id, routing_failed, created_at);

-- ---------------------------------------------------------------------------
-- Acknowledgement, which is ONE state on the incident.
-- ---------------------------------------------------------------------------
--
-- Not per channel, and that is the whole point of the table existing. A
-- responder acking on their phone must silence the chat page and the ticket
-- reminder; storing an ack per delivery lets the same human be paged again
-- through a route they never saw. First ack wins, and which channel it arrived
-- on is recorded as a fact rather than as identity.
CREATE TABLE incident_acks (
    tenant_id       VARCHAR(64)  NOT NULL,
    incident_id     VARCHAR(128) NOT NULL,

    acked_by        VARCHAR(255) NOT NULL,
    acked_via       ENUM('TICKET','CHAT','PUSH','PHONE','API') NOT NULL,
    acked_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- Which decision the ack answered, when it can be told. Null when a human
    -- acked out of band — that is normal and must not block recording the ack.
    decision_id     BIGINT UNSIGNED NULL,

    PRIMARY KEY (tenant_id, incident_id),
    CONSTRAINT fk_ack_decision FOREIGN KEY (decision_id)
        REFERENCES escalation_decisions (id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- Was the routing right? The only feedback signal available.
-- ---------------------------------------------------------------------------
--
-- routing accuracy = 1 - reassignment rate. The spec calls reassignments "the
-- single best proxy for whether your ownership data is real or aspirational",
-- and it is cheap: the incident engine already carries an assignee, so the
-- comparison is between who we chose and who ended up holding it.
--
-- One row per decision, written later by whatever observes the incident closing
-- — which is why it is a separate table rather than columns on the decision: a
-- decision is written once at page time and never updated, and an outcome
-- arrives hours afterwards.
CREATE TABLE routing_outcomes (
    decision_id     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,

    final_assignee  VARCHAR(255) NULL,
    reassigned      TINYINT(1)   NOT NULL,
    observed_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_outcome_decision FOREIGN KEY (decision_id)
        REFERENCES escalation_decisions (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_routing_outcomes_accuracy
    ON routing_outcomes (tenant_id, reassigned, observed_at);

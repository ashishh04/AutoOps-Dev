package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.intertec.autoops.agent.domain.Finding;
import com.intertec.autoops.agent.domain.FindingObservation;
import com.intertec.autoops.agent.domain.FindingSuppression;
import com.intertec.autoops.agent.domain.FindingTransition;
import com.intertec.autoops.agent.repo.FindingObservationRepository;
import com.intertec.autoops.agent.repo.FindingRepository;
import com.intertec.autoops.agent.repo.FindingSuppressionRepository;
import com.intertec.autoops.agent.repo.FindingTransitionRepository;
import com.intertec.autoops.agent.scope.SubjectDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Turns a verdict into a disposition.
 *
 * <p><b>The disposition is the product.</b> Everything downstream — dedupe
 * rates, dismissal churn, materiality tuning, whether an agent has started
 * drifting — becomes measurable the moment an agent is told what happened to
 * what it emitted, and stays anecdotal until then. So this service's contract
 * is not "store a finding"; it is "say precisely what you did with this".
 *
 * <p>The table it implements:
 *
 * <pre>
 * incoming                     current state                  disposition
 * ─────────────────────────────────────────────────────────────────────────
 * new key                      —                              CREATED
 * same key, same content       OPEN / ACKNOWLEDGED            NO_CHANGE
 * same key, material change    OPEN / ACKNOWLEDGED            UPDATED
 * same key, same content       SUPPRESSED, live               SUPPRESSED
 * same key, material change    SUPPRESSED, live, survives     SUPPRESSED
 * same key, material change    SUPPRESSED, live, !survives    REOPENED
 * same key, any content        RESOLVED / STALE               REOPENED, episode++
 * same verdict id seen before  any                            DUPLICATE_VERDICT
 * </pre>
 *
 * <p><b>Two hashes.</b> The idempotency key answers <i>is this the same
 * finding</i> and is composed by the agent runtime. The content hash answers
 * <i>has the substance changed</i> and is computed here, over the fields that
 * would change a human's mind — never over the whole payload, which carries
 * timestamps and evidence excerpts that differ on every run and would make
 * every sighting look material.
 */
@Service
public class FindingIngestService {

    private static final Logger log = LoggerFactory.getLogger(FindingIngestService.class);

    /** What happened to a verdict. Returned per verdict, never per batch. */
    public enum Disposition {
        CREATED, UPDATED, NO_CHANGE, REOPENED, SUPPRESSED, DUPLICATE_VERDICT, REJECTED
    }

    /**
     * @param notify whether a human should be told. Deliberately not the same
     *               as "something changed": a nightly sweep produces thousands
     *               of touches and a handful of things worth interrupting
     *               somebody for.
     */
    public record Result(String verdictId, Long findingId, Disposition disposition,
                         Finding.State state, Finding.State priorState, String reasonCode,
                         int occurrenceCount, int episodeCount, boolean material,
                         boolean shouldNotify, Long suppressedBy, String error) {

        static Result rejected(String verdictId, String error) {
            return new Result(verdictId, null, Disposition.REJECTED, null, null,
                    "schema_violation", 0, 0, false, false, null, error);
        }

        /**
         * Refused for a reason that is not a schema problem.
         *
         * <p>The reason code is distinct per cause rather than collapsed into
         * {@code schema_violation}, because these separate two different bugs:
         * an agent emitting under another agent's run, and an agent racing its
         * own completion. Collapsing them means the first is diagnosed as the
         * second and the wrong thing gets fixed.
         */
        static Result refused(String verdictId, String reasonCode, String error) {
            return new Result(verdictId, null, Disposition.REJECTED, null, null,
                    reasonCode, 0, 0, false, false, null, error);
        }
    }

    private final FindingRepository findings;
    private final FindingObservationRepository observations;
    private final FindingSuppressionRepository suppressions;
    private final FindingTransitionRepository transitions;
    private final VerdictAttributionService attributions;

    public FindingIngestService(FindingRepository findings,
                                FindingObservationRepository observations,
                                FindingSuppressionRepository suppressions,
                                FindingTransitionRepository transitions,
                                VerdictAttributionService attributions) {
        this.findings = findings;
        this.observations = observations;
        this.suppressions = suppressions;
        this.transitions = transitions;
        this.attributions = attributions;
    }

    /**
     * Ingests one verdict.
     *
     * <p>Per verdict rather than per batch, and each in its own transaction:
     * one malformed verdict must not stall a nightly sweep of four hundred
     * good ones. The caller collects the results and answers 207.
     */
    @Transactional
    public Result ingest(String tenantId, Long projectId, Long runId, JsonNode verdict) {
        String verdictId = text(verdict, "verdict_id", null);
        if (verdictId == null || verdictId.isBlank()) {
            // Without it there is no replay protection, so it is required even
            // though nothing else reads it.
            return Result.rejected(null, "verdict_id is required");
        }
        String key = text(verdict, "idempotency_key", null);
        if (key == null || key.isBlank()) {
            return Result.rejected(verdictId, "idempotency_key is required");
        }

        // An UNSTABLE key deduplicates nothing across runs — the runtime says
        // so explicitly rather than pretending. Storing it would create a new
        // finding every night that nobody could ever dismiss, so it is refused
        // here rather than quietly filling the table.
        if (verdict.has("stable_key") && !verdict.path("stable_key").asBoolean(true)) {
            return Result.rejected(verdictId,
                    "this verdict's idempotency key is not stable (the finding names no "
                            + "subject), so it cannot be tracked across runs");
        }

        // Can this verdict be traced to the run that produced it? Phase A
        // tolerates "no" and counts it; it never tolerates a run belonging to
        // somebody else, or one that has already settled its coverage claim.
        // Placed before the replay check on purpose — a retry of a verdict from
        // a foreign run should be refused for the same reason the original was,
        // not waved through as "seen before".
        String agentName = text(verdict.path("agent"), "name", "unknown");
        VerdictAttributionService.Attribution attribution = attributions.classify(
                tenantId, agentName, runId,
                text(verdict.path("subject"), "kind", null),
                text(verdict.path("subject"), "id", null),
                Instant.now());
        if (attribution == VerdictAttributionService.Attribution.FOREIGN_RUN) {
            return Result.refused(verdictId, "foreign_run",
                    "This verdict names run " + runId + ", which does not belong to agent '"
                            + agentName + "' in this workspace. A verdict attributed to another "
                            + "agent's run would be resolved by absence against a scope that "
                            + "never covered it.");
        }
        if (attribution == VerdictAttributionService.Attribution.OUT_OF_SCOPE) {
            return Result.refused(verdictId, "subject_out_of_scope",
                    "This verdict is about a subject that run " + runId + " says it never "
                            + "examined. The run has an opinion about it, so it did — which "
                            + "means the scope describes a smaller set than the run covered, "
                            + "and every subject missing from it would be reaped by the next "
                            + "run that does declare them.");
        }
        if (attribution == VerdictAttributionService.Attribution.LATE) {
            return Result.refused(verdictId, "run_already_completed",
                    "Run " + runId + " already reported what it covered. A verdict arriving "
                            + "afterwards claims coverage for a subject that completion "
                            + "validation never counted.");
        }

        if (observations.existsByTenantIdAndVerdictId(tenantId, verdictId)) {
            // A network retry, not a new sighting. Incrementing the occurrence
            // count here is the single easiest way to make every counter lie.
            return new Result(verdictId, null, Disposition.DUPLICATE_VERDICT, null, null,
                    "replay", 0, 0, false, false, null, null);
        }

        Instant now = Instant.now();
        String contentHash = contentHash(verdict);
        Short keyVersion = (short) verdict.path("idempotency_key_version").asInt(1);

        Optional<Finding> existing = findings
                .findByTenantIdAndIdempotencyKeyAndIdempotencyKeyVersion(tenantId, key, keyVersion);

        return existing.isPresent()
                ? touch(existing.get(), verdict, verdictId, runId, contentHash, now)
                : create(tenantId, projectId, runId, verdict, verdictId, key, keyVersion,
                        contentHash, now);
    }

    // ------------------------------------------------------------- creating ---

    private Result create(String tenantId, Long projectId, Long runId, JsonNode verdict,
                          String verdictId, String key, Short keyVersion,
                          String contentHash, Instant now) {
        Finding finding = new Finding();
        finding.setTenantId(tenantId);
        finding.setProjectId(projectId);
        finding.setIdempotencyKey(key);
        finding.setIdempotencyKeyVersion(keyVersion);
        finding.setContentHash(contentHash);
        finding.setAgentName(text(verdict.path("agent"), "name", "unknown"));
        finding.setAgentVersion(text(verdict.path("agent"), "version", "0"));
        finding.setCategory(text(verdict.path("finding"), "type", "unclassified"));
        finding.setSubjectKind(text(verdict.path("subject"), "kind", "unknown"));
        String subjectId = text(verdict.path("subject"), "id", "");
        finding.setSubjectId(subjectId);
        // Written HERE and only here, because the subject is part of the
        // idempotency key — a finding's subject cannot change without becoming a
        // different finding, so there is nothing to keep in step on a later
        // sighting. Empty stays null: hashing "" produces a real, shared value
        // that would join every subject-less finding to every other one.
        finding.setSubjectIdHash(
                subjectId.isEmpty() ? null : SubjectDigest.hash(subjectId));
        finding.setServiceRef(text(verdict, "service_ref", null));
        finding.setEnvironment(text(verdict, "environment", "unknown"));
        finding.setSeverity(text(verdict.path("finding"), "severity", "info"));
        finding.setRiskTier(text(verdict, "risk_tier", null));
        finding.setConfidenceBand(text(verdict, "confidence_band", null));
        if (verdict.hasNonNull("confidence")) {
            finding.setConfidence(BigDecimal.valueOf(verdict.path("confidence").asDouble()));
        }
        if (verdict.hasNonNull("priority_score")) {
            finding.setPriorityScore(BigDecimal.valueOf(verdict.path("priority_score").asDouble()));
        }
        finding.setFirstSeenAt(now);
        finding.setLastSeenAt(now);
        finding.setLastMaterialChangeAt(now);
        finding.setStateChangedAt(now);
        finding.setUpdatedAt(now);
        finding.setPayload(verdict.toString());
        finding.setLastRunId(runId);

        // Suppression is checked BEFORE the finding is first shown to anybody.
        // A category dismissed at service scope last month must not surface a
        // new finding today just because this particular one is new.
        Optional<FindingSuppression> cover = coveringSuppression(tenantId, finding, now, true);
        finding.setState(cover.isPresent() ? Finding.State.SUPPRESSED : Finding.State.OPEN);
        cover.ifPresent(s -> finding.setStateReason("suppressed_by_scope:" + s.getScope()));

        Finding saved = findings.save(finding);
        writeObservation(saved, verdict, verdictId, runId, contentHash, true, now);
        record(saved, null, saved.getState(), FindingTransition.ActorKind.AGENT,
                saved.getAgentName(),
                cover.isPresent() ? "created_suppressed" : "created",
                null, cover.map(FindingSuppression::getId).orElse(null));

        return new Result(verdictId, saved.getId(),
                cover.isPresent() ? Disposition.SUPPRESSED : Disposition.CREATED,
                saved.getState(), null,
                cover.isPresent() ? "suppressed_by_scope" : "created",
                1, 1, true, cover.isEmpty(),
                cover.map(FindingSuppression::getId).orElse(null), null);
    }

    // ------------------------------------------------------------- touching ---

    private Result touch(Finding finding, JsonNode verdict, String verdictId, Long runId,
                         String contentHash, Instant now) {
        Finding.State prior = finding.getState();
        boolean material = !contentHash.equals(finding.getContentHash());

        finding.setLastSeenAt(now);
        finding.setUpdatedAt(now);
        finding.setOccurrenceCount(finding.getOccurrenceCount() + 1);
        finding.setAgentVersion(text(verdict.path("agent"), "version", finding.getAgentVersion()));
        finding.setLastRunId(runId);

        if (material) {
            finding.setContentHash(contentHash);
            finding.setPayload(verdict.toString());
            finding.setLastMaterialChangeAt(now);
            finding.setMaterialChangeCount(finding.getMaterialChangeCount() + 1);
            finding.setSeverity(text(verdict.path("finding"), "severity", finding.getSeverity()));
        }

        Disposition disposition;
        String reason;
        boolean shouldNotify;
        Long suppressedBy = null;

        if (finding.isClosed()) {
            // It came BACK. A regression is worth telling somebody about
            // however small the change, because the interesting fact is that a
            // fix did not hold — not what the current numbers are.
            finding.setEpisodeCount(finding.getEpisodeCount() + 1);
            move(finding, Finding.State.OPEN, now, "reopened_regression");
            disposition = Disposition.REOPENED;
            reason = prior == Finding.State.RESOLVED ? "regression" : "reobserved_after_stale";
            shouldNotify = true;
        } else if (prior == Finding.State.SUPPRESSED) {
            Optional<FindingSuppression> cover =
                    coveringSuppression(finding.getTenantId(), finding, now, !material);
            if (cover.isPresent()) {
                disposition = Disposition.SUPPRESSED;
                reason = "suppressed_by_scope";
                shouldNotify = false;
                suppressedBy = cover.get().getId();
            } else {
                // Either the dismissal lapsed, or the substance moved past what
                // was dismissed. Both mean somebody should look again.
                move(finding, Finding.State.OPEN, now,
                        material ? "material_change_under_suppression" : "suppression_expired");
                disposition = Disposition.REOPENED;
                reason = material ? "material_change_under_suppression" : "suppression_expired";
                shouldNotify = true;
            }
        } else if (material) {
            disposition = Disposition.UPDATED;
            reason = "material_change";
            // Only worth an interruption when it crossed into a worse band.
            shouldNotify = escalated(prior, finding);
        } else {
            disposition = Disposition.NO_CHANGE;
            reason = "unchanged";
            shouldNotify = false;
        }

        Finding saved = findings.save(finding);

        // NOT on NO_CHANGE. A nightly sweep is mostly unchanged, and a row per
        // sighting buys nothing the counters do not already carry while
        // costing a table somebody has to prune.
        if (disposition != Disposition.NO_CHANGE && disposition != Disposition.SUPPRESSED) {
            writeObservation(saved, verdict, verdictId, runId, contentHash, material, now);
        }
        if (disposition == Disposition.REOPENED) {
            record(saved, prior, saved.getState(), FindingTransition.ActorKind.AGENT,
                    saved.getAgentName(), reason, null, null);
        }

        return new Result(verdictId, saved.getId(), disposition, saved.getState(), prior, reason,
                saved.getOccurrenceCount(), saved.getEpisodeCount(), material, shouldNotify,
                suppressedBy, null);
    }

    // ---------------------------------------------------------- suppression ---

    /**
     * The live suppression covering this finding, narrowest scope first.
     *
     * <p>Precedence matters for the audit trail rather than for the outcome:
     * any live cover suppresses, but the one RECORDED must be the most
     * specific, or "why did this never appear" gets answered with a
     * tenant-wide rule when a finding-level dismissal was the real reason.
     *
     * @param honourSurvivesFlag false when the substance has materially
     *                           changed, so only suppressions that explicitly
     *                           survive a material change still count
     */
    private Optional<FindingSuppression> coveringSuppression(String tenantId, Finding finding,
                                                             Instant now,
                                                             boolean honourSurvivesFlag) {
        return suppressions.findByTenantIdAndRevokedAtIsNull(tenantId).stream()
                .filter(s -> s.isActiveAt(now))
                .filter(s -> honourSurvivesFlag || s.isSurvivesMaterialChange())
                .filter(s -> s.covers(finding))
                .min(Comparator.comparingInt(s -> s.getScope().ordinal()));
    }

    // -------------------------------------------------------------- helpers ---

    private void move(Finding finding, Finding.State to, Instant now, String reason) {
        finding.setState(to);
        finding.setStateChangedAt(now);
        finding.setStateReason(reason);
    }

    /**
     * Whether a change is worth interrupting somebody for.
     *
     * <p>Only an escalation. A finding that got better, or moved sideways, is
     * recorded and not announced — the alternative trains people that every
     * notification is noise, which is how the important one gets missed.
     */
    private boolean escalated(Finding.State prior, Finding finding) {
        return prior == Finding.State.OPEN
                && ("critical".equalsIgnoreCase(finding.getSeverity())
                    || "high".equalsIgnoreCase(finding.getSeverity()));
    }

    private void writeObservation(Finding finding, JsonNode verdict, String verdictId, Long runId,
                                  String contentHash, boolean material, Instant now) {
        FindingObservation observation = new FindingObservation();
        observation.setTenantId(finding.getTenantId());
        observation.setFindingId(finding.getId());
        observation.setVerdictId(verdictId);
        observation.setRunId(runId);
        observation.setAgentVersion(finding.getAgentVersion());
        observation.setObservedAt(now);
        observation.setContentHash(contentHash);
        observation.setMaterial(material);
        observation.setPayload(verdict.toString());
        FindingObservation saved = observations.save(observation);
        finding.setHeadObservationId(saved.getId());
    }

    private void record(Finding finding, Finding.State from, Finding.State to,
                        FindingTransition.ActorKind actorKind, String actorRef,
                        String reasonCode, String detail, Long refId) {
        transitions.save(FindingTransition.of(finding, from, to, actorKind, actorRef,
                reasonCode, detail, refId));
    }

    /**
     * A hash of the fields that would change a human's decision.
     *
     * <p>Deliberately NOT the whole payload. Evidence excerpts carry
     * timestamps and free text that differ on every run, so hashing them would
     * make every sighting material — which reopens every dismissed finding
     * nightly and teaches people to dismiss permanently.
     *
     * <p>What is included is the shape of the conclusion: what it is about,
     * what kind of problem, how bad, and what is proposed about it.
     */
    String contentHash(JsonNode verdict) {
        String material = String.join("|",
                text(verdict.path("subject"), "kind", ""),
                text(verdict.path("subject"), "id", ""),
                text(verdict.path("finding"), "type", ""),
                text(verdict.path("finding"), "severity", ""),
                text(verdict, "risk_tier", ""),
                text(verdict.path("autonomy"), "requested_tier", ""),
                actionShape(verdict));
        return sha256(material);
    }

    /** The proposal's identity, if there is one — its type, not its wording. */
    private String actionShape(JsonNode verdict) {
        JsonNode action = verdict.path("suggested_action");
        if (action.isMissingNode() || action.isNull()) {
            return "";
        }
        return text(action, "type", "") + ":" + action.path("params").toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 64);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    /**
     * Two live findings from one agent on one subject and category.
     *
     * <p>The undeclared key-fork alarm. When somebody changes what goes into
     * the idempotency key without bumping its version, every existing finding
     * silently forks into a duplicate that looks like a new problem — and
     * nothing else in the system can tell. Cheap to check and invisible until
     * somebody does.
     */
    @Transactional(readOnly = true)
    public List<Finding> forkedDuplicates(String tenantId, Finding finding) {
        List<Finding> live = findings
                .findByTenantIdAndAgentNameAndSubjectIdAndCategoryAndStateIn(
                        tenantId, finding.getAgentName(), finding.getSubjectId(),
                        finding.getCategory(),
                        List.of(Finding.State.OPEN, Finding.State.ACKNOWLEDGED));
        if (live.size() > 1) {
            log.warn("Tenant {} has {} live findings for agent {} on {}/{} — the idempotency key "
                            + "composition may have changed without a version bump",
                    tenantId, live.size(), finding.getAgentName(), finding.getSubjectKind(),
                    finding.getSubjectId());
        }
        return live;
    }
}

package com.intertec.autoops.core.service;

import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunStatus;
import com.intertec.autoops.core.repo.ApprovalRepository;
import com.intertec.autoops.core.repo.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What HAPPENED in a workspace, as one ordered list, whatever it happened to.
 *
 * <p><b>Why this exists, and why it is not another cloud integration.</b> Every
 * other automation in this platform reaches out to one vendor and answers a
 * question about that vendor: which S3 buckets are public, which Entra accounts
 * are stale. Those are useful and they are also what any competent script does.
 * They say nothing about AutoOps that a shell script and a cron entry could not.
 *
 * <p>This is the other kind. It answers a question only AutoOps can answer,
 * because only AutoOps holds the data: <em>what has been going on in this
 * workspace</em>. The automations that ran and what they did. The ones that
 * failed, and whether they failed together. The changes parked waiting for a
 * human. It is vendor-agnostic by construction — a customer running AWS, Azure,
 * on-premises VMware or all three has one timeline here, because every one of
 * those estates is automated through the same control plane.
 *
 * <p>That is the difference between a catalog of vendor scripts and an agentic
 * platform, and it is the reason this class is deliberately NOT under
 * {@code client/}: there is nothing to call. The evidence is already here.
 *
 * <p><b>Tenant scoping.</b> Every query is keyed on {@code tenantId} and
 * {@code projectId} together, and there is no path through this class that does
 * not carry both. The caller is a service holding the internal token, and that
 * token authorises it to ASK on a tenant's behalf — never to widen the answer.
 */
@Service
public class PlatformTimelineService {

    /**
     * The widest window a single call may ask for.
     *
     * <p>Not a performance guard — the repository is already capped at 200 rows.
     * It is an honesty guard: a model handed thirty days of history will find a
     * correlation in it, because in thirty days of a real estate there is always
     * something that happened before something else. Correlation is a claim
     * about proximity in time, and a window wide enough to make everything
     * proximate makes the claim worthless.
     */
    private static final int MAX_WINDOW_HOURS = 168;

    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;

    public PlatformTimelineService(RunRepository runRepository,
                                   ApprovalRepository approvalRepository) {
        this.runRepository = runRepository;
        this.approvalRepository = approvalRepository;
    }

    /** One thing that happened, flattened so unlike events can be read in order. */
    public record Event(String at, long minutesAgo, String kind, String what, String detail,
                        String outcome, String actor, Long id) {
    }

    /**
     * Everything that happened in this project inside the window, newest last.
     *
     * <p>Ordered OLDEST FIRST, which is the opposite of every list in the
     * console and correct here: this is read as a narrative, and a cause
     * precedes its effect.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> timeline(String tenantId, Long projectId, int windowHours) {
        int hours = Math.max(1, Math.min(windowHours, MAX_WINDOW_HOURS));
        Instant since = Instant.now().minus(Duration.ofHours(hours));

        List<Event> events = new ArrayList<>();
        List<Run> runs = runRepository
                .findTop200ByTenantIdAndProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        tenantId, projectId, since);
        for (Run run : runs) {
            events.add(runEvent(run));
        }
        for (Approval approval : approvalRepository
                .findTop200ByTenantIdAndProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        tenantId, projectId, since)) {
            events.add(approvalEvent(approval));
        }

        events.sort(Comparator.comparing(Event::at));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("windowHours", hours);
        answer.put("since", since.toString());
        answer.put("events", events);
        answer.putAll(summarise(runs, events));
        // Named explicitly. A capped list that reads as complete is how an
        // investigation concludes that nothing else happened.
        answer.put("truncated", runs.size() >= 200);
        return answer;
    }

    private Event runEvent(Run run) {
        String outcome = run.getStatus() == null ? "UNKNOWN" : run.getStatus().name();
        StringBuilder detail = new StringBuilder();
        if (run.getDurationMs() != null) {
            detail.append(run.getDurationMs() / 1000).append("s");
        }
        if (run.getError() != null && !run.getError().isBlank()) {
            if (!detail.isEmpty()) {
                detail.append(" · ");
            }
            // The first line only. A stack trace in a timeline buries the
            // sequence the timeline exists to show.
            detail.append(firstLine(run.getError()));
        }
        return new Event(
                String.valueOf(run.getCreatedAt()),
                minutesAgo(run.getCreatedAt()),
                "RUN",
                (run.getTargetType() == null ? "" : run.getTargetType().name().toLowerCase() + " ")
                        + orDash(run.getTargetName()),
                detail.toString(),
                outcome,
                orDash(run.getTriggeredBy()),
                run.getId());
    }

    private Event approvalEvent(Approval approval) {
        return new Event(
                String.valueOf(approval.getCreatedAt()),
                minutesAgo(approval.getCreatedAt()),
                "APPROVAL",
                (approval.getTargetType() == null
                        ? "" : approval.getTargetType().name().toLowerCase() + " ")
                        + orDash(approval.getTargetName()),
                // Who decided it, once somebody has. A request still sitting in
                // the inbox is the interesting case and says so by having none.
                approval.getDecidedBy() == null ? "awaiting a decision"
                        : "decided by " + approval.getDecidedBy(),
                approval.getStatus() == null ? "UNKNOWN" : approval.getStatus().name(),
                orDash(approval.getRequestedBy()),
                approval.getId());
    }

    /**
     * The counts worth having computed rather than inferred.
     *
     * <p>A model asked to tally forty rows will get one wrong, and a wrong tally
     * carrying a citation is the worst output this platform can produce. So the
     * arithmetic is done here and quoted there — the same rule the cost and
     * alarm automations follow.
     */
    private Map<String, Object> summarise(List<Run> runs, List<Event> events) {
        Map<String, Object> summary = new LinkedHashMap<>();
        long failed = runs.stream().filter(r -> r.getStatus() == RunStatus.FAILED).count();
        long succeeded = runs.stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED).count();
        long running = runs.stream()
                .filter(r -> r.getStatus() == RunStatus.RUNNING || r.getStatus() == RunStatus.QUEUED)
                .count();

        summary.put("eventsTotal", events.size());
        summary.put("runsTotal", runs.size());
        summary.put("runsFailed", failed);
        summary.put("runsSucceeded", succeeded);
        summary.put("runsInFlight", running);

        // Which automations failed MORE THAN ONCE. A single failure is an
        // incident; the same automation failing four times is a broken
        // automation, and telling them apart is most of triage.
        Map<String, Long> repeats = new LinkedHashMap<>();
        for (Run run : runs) {
            if (run.getStatus() == RunStatus.FAILED) {
                repeats.merge(orDash(run.getTargetName()), 1L, Long::sum);
            }
        }
        repeats.entrySet().removeIf(entry -> entry.getValue() < 2);
        summary.put("repeatedFailures", repeats);
        return summary;
    }

    private static long minutesAgo(Instant when) {
        return when == null ? -1 : Duration.between(when, Instant.now()).toMinutes();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}

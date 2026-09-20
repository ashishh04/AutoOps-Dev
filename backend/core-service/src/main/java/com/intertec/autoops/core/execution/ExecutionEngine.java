package com.intertec.autoops.core.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunStatus;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.repo.RunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Walks a run's snapshotted steps through the {@link StepExecutor}, streaming
 * progress (step counter + log) to the DB after every step so the UI can poll
 * a live run. The run row is RELOADED before each write — cancel flips
 * {@code cancel_requested} from another thread/transaction and a stale save
 * here must not clobber it. Runs on the bounded execution pool, one thread
 * per run.
 *
 * <p>Every terminal state increments {@code core_runs_total}
 * (tags {@code status}, {@code trigger}).
 */
@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    /** Hard cap on per-step retries, whatever the definition asks for. */
    static final int MAX_RETRIES = 5;

    private final RunRepository runRepository;
    private final StepExecutor stepExecutor;
    private final ObjectMapper objectMapper;
    private final CoreProperties properties;
    /** The native replacement: a node graph executed on agent-runtime. */
    private final com.intertec.autoops.core.service.NativeWorkflowService nativeWorkflows;
    /** Nullable: slice tests have no MeterRegistry; prod wires Prometheus. */
    private final MeterRegistry meterRegistry;
    /** Nullable in slice tests that don't import it; failures then go unannounced. */
    private final com.intertec.autoops.core.service.NotificationService notificationService;
    /** Nullable for the same reason: outbound channels stay silent in slice tests. */
    private final com.intertec.autoops.core.service.LifecycleNotifier lifecycleNotifier;

    public ExecutionEngine(RunRepository runRepository,
                           StepExecutor stepExecutor,
                           ObjectMapper objectMapper,
                           CoreProperties properties,
                           com.intertec.autoops.core.service.NativeWorkflowService nativeWorkflows,
                           ObjectProvider<MeterRegistry> meterRegistry,
                           ObjectProvider<com.intertec.autoops.core.service.NotificationService> notificationService,
                           ObjectProvider<com.intertec.autoops.core.service.LifecycleNotifier> lifecycleNotifier) {
        this.runRepository = runRepository;
        this.stepExecutor = stepExecutor;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.nativeWorkflows = nativeWorkflows;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.notificationService = notificationService.getIfAvailable();
        this.lifecycleNotifier = lifecycleNotifier.getIfAvailable();
    }

    private void sleepBetweenRetries() {
        long millis = properties.getExecution().getRetryDelay().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void execute(Long runId) {
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.QUEUED) {
            return;
        }
        if (run.isCancelRequested()) {
            finish(run, RunStatus.CANCELED, null, "Cancelled before start.");
            return;
        }
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);
        // STARTED is announced here because RUNNING is the transition that
        // means it — RunStatus has no STARTED member of its own.
        if (lifecycleNotifier != null) {
            lifecycleNotifier.runStarted(run);
        }

        StringBuilder logText = new StringBuilder();

        // A native workflow graph is handed WHOLE to the runtime — one call,
        // not a walk of local steps. Checked before parseSteps because such a
        // definition has no `steps[]` at all, so the step walk would find
        // nothing and report a vacuous success.
        if (nativeWorkflows.isNative(run.getDefinition())) {
            executeViaRuntime(run, logText);
            return;
        }

        List<StepExecutor.RunStep> steps;
        try {
            steps = parseSteps(run);
        } catch (UnresolvedInputException ex) {
            // A step whose placeholders cannot all be filled must not run.
            // Passing "{{TargetHost}}" to ssh verbatim is the failure this
            // prevents, and it has to be loud rather than a confusing remote
            // error minutes later.
            logText.append(ex.getMessage());
            run.setLog(logText.toString());
            finish(run, RunStatus.FAILED, ex.getMessage(), null);
            return;
        }
        if (steps.isEmpty()) {
            logText.append("Nothing to execute — the definition has no steps.");
            run.setLog(logText.toString());
            finish(run, RunStatus.SUCCEEDED, null, null);
            return;
        }

        try {
            int ignoredFailures = 0;
            for (StepExecutor.RunStep step : steps) {
                // Production-grade step policy, both from the step's own JSON:
                // retries (0..MAX, transient failures self-heal) and
                // continueOnError (non-critical steps don't kill the pipeline).
                int retries = Math.clamp(step.raw().path("retries").asInt(0), 0, MAX_RETRIES);
                boolean continueOnError = step.raw().path("continueOnError").asBoolean(false);

                StepExecutor.StepOutcome outcome = null;
                boolean success = false;
                for (int attempt = 0; attempt <= retries; attempt++) {
                    if (attempt > 0) {
                        logText.append("    ↻ retrying (attempt ").append(attempt + 1)
                                .append("/").append(retries + 1).append(")…\n");
                        sleepBetweenRetries();
                        // A cancel during the wait must win over the retry.
                        run = runRepository.findById(runId).orElse(null);
                        if (run == null) {
                            return;
                        }
                        if (run.isCancelRequested()) {
                            run.setLog(logText.toString());
                            finish(run, RunStatus.CANCELED, null, "Run cancelled by user.");
                            return;
                        }
                    }
                    outcome = stepExecutor.execute(run.getTenantId(), run.getProjectId(),
                            step.withAttempt(attempt));
                    logText.append(line(step.withAttempt(attempt), outcome, retries));
                    success = outcome.success();
                    if (success) {
                        break;
                    }
                }

                // Fresh read: pick up a cancel requested while the step ran.
                run = runRepository.findById(runId).orElse(null);
                if (run == null) {
                    return; // purged mid-run
                }
                run.setStepCompleted(step.index() + 1);
                if (!success && continueOnError) {
                    ignoredFailures++;
                    logText.append("    ! continue-on-error: failure ignored, moving on\n");
                }
                run.setLog(logText.toString());
                if (!success && !continueOnError) {
                    finish(run, RunStatus.FAILED, outcome.error(), null);
                    return;
                }
                if (run.isCancelRequested()) {
                    finish(run, RunStatus.CANCELED, null, "Run cancelled by user.");
                    return;
                }
                run = runRepository.save(run);
            }
            finish(run, RunStatus.SUCCEEDED, null, ignoredFailures == 0
                    ? "All " + steps.size() + " steps completed."
                    : "Completed with " + ignoredFailures + " ignored failure(s).");
        } catch (Exception ex) {
            log.error("Run {} crashed", runId, ex);
            run = runRepository.findById(runId).orElse(null);
            if (run != null && !run.getStatus().isTerminal()) {
                run.setLog(logText.toString());
                finish(run, RunStatus.FAILED, "Internal execution error: " + ex.getMessage(), null);
            }
        }
    }

    /** "2m 59s" reads better in a run log than "179322". */
    private static String humanDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /**
     * Hands the whole run to the native workflow runtime and records the
     * outcome.
     *
     * <p>The per-node log lines, the mid-run saves that keep a polling run
     * screen moving, and the reload-before-write that stops a stale save
     * clobbering a cancel are all behaviour an operator depends on, and none of
     * it is specific to which engine is behind the call.
     *
     * <p><b>Not cancellable mid-flight.</b> The runtime owns execution once the
     * call is made. Said in the log rather than left to be discovered — a
     * Cancel button that silently does nothing is worse than one that explains
     * itself.
     */
    private void executeViaRuntime(Run run, StringBuilder logText) {
        Map<String, Object> inputs = readInputs(run.getInputs());
        run.setStepTotal(1);
        // The TRACE only — no banner naming the engine, and no echo of the
        // inputs. The engine is an implementation detail a customer never asked
        // about, and the inputs are the text they just typed: replaying a
        // 400-word meeting transcript back at them pushed the actual report a
        // screenful down the page. Both are already on the run row
        // (`runs.inputs`) for anyone who needs them.
        try {
            java.util.concurrent.atomic.AtomicInteger done =
                    new java.util.concurrent.atomic.AtomicInteger();

            com.intertec.autoops.core.client.WorkflowRuntimeClient.RunOutcome outcome =
                    nativeWorkflows.run(run.getId(), run.getTenantId(), run.getProjectId(),
                            run.getDefinition(),
                            inputs, (title, finished, index, elapsedMs, failed) -> {
                        if (finished) {
                            logText.append("    ").append(failed ? "✗ " : "✓ ").append(title);
                            if (elapsedMs != null) {
                                logText.append(" (").append(humanDuration(elapsedMs)).append(')');
                            }
                            logText.append('\n');
                        } else {
                            logText.append("    → ").append(title).append(" …\n");
                        }
                        int completed = finished ? done.incrementAndGet() : done.get();
                        // Written straight to the repository: this runs
                        // mid-workflow, and the in-memory Run is not the row
                        // the UI is reading.
                        runRepository.findById(run.getId()).ifPresent(live -> {
                            if (!live.getStatus().isTerminal()) {
                                live.setStepCompleted(completed);
                                live.setStepTotal(Math.max(live.getStepTotal(), completed + 1));
                                live.setLog(logText.toString());
                                runRepository.save(live);
                            }
                        });
                    });

            if (outcome.totalSteps() != null) {
                logText.append("    ").append(outcome.totalSteps()).append(" node(s)\n");
            }
            // The report as prose, not the raw outputs JSON — dumping that
            // object verbatim put escaped HTML on screen with the answer buried
            // inside it. See RunOutputs.
            String readable = RunOutputs.readable(outcome.outputs());

            Run current = runRepository.findById(run.getId()).orElse(run);
            current.setStepTotal(1);
            current.setStepCompleted(outcome.success() ? 1 : 0);
            // The deliverable goes to `output`, the trace stays in `log`. They
            // shared one field and the customer got both at once — node timings
            // and an echo of their own input above the document they asked for,
            // then the document again because finish() appended it a second
            // time. Two audiences, two fields.
            current.setOutput(readable == null || readable.isBlank() ? null : readable);
            current.setLog(logText.toString());
            if (current.isCancelRequested()) {
                logText.append("Cancel arrived after the workflow had started — it ran to "
                        + "completion.\n");
                current.setLog(logText.toString());
            }
            finish(current, outcome.success() ? RunStatus.SUCCEEDED : RunStatus.FAILED,
                    outcome.error(), null);
        } catch (Exception ex) {
            // A transport failure, as opposed to a workflow that ran and
            // failed. Both end the run; only this one is an AutoOps problem.
            log.error("Workflow run {} could not be dispatched to the runtime", run.getId(), ex);
            Run current = runRepository.findById(run.getId()).orElse(run);
            // The exception already names the cause. Prefixing it again
            // produced "Could not reach the workflow runtime: Could not reach
            // the workflow runtime: …" in the run log an operator reads.
            logText.append(ex.getMessage()).append('\n');
            current.setLog(logText.toString());
            finish(current, RunStatus.FAILED, ex.getMessage(), null);
        }
    }

    private Map<String, Object> readInputs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------

    private void finish(Run run, RunStatus status, String error, String logSuffix) {
        Instant now = Instant.now();
        if (logSuffix != null) {
            run.setLog((run.getLog() == null || run.getLog().isEmpty()
                    ? "" : run.getLog() + "\n") + logSuffix);
        }
        run.setStatus(status);
        run.setError(error);
        run.setFinishedAt(now);
        if (run.getStartedAt() != null) {
            run.setDurationMs(Duration.between(run.getStartedAt(), now).toMillis());
        }
        runRepository.save(run);
        log.info("Run {} ({} {}) finished {} in {}ms", run.getId(), run.getTargetType(),
                run.getTargetId(), status, run.getDurationMs());
        if (meterRegistry != null) {
            meterRegistry.counter("core_runs_total",
                    "status", status.name(),
                    "trigger", run.getTrigger().name()).increment();
        }
        if (status == RunStatus.FAILED && notificationService != null) {
            notificationService.publish(run.getTenantId(),
                    com.intertec.autoops.core.domain.AppNotification.Kind.ALERT,
                    "Run failed: " + run.getTargetName(),
                    error != null ? error : "The run finished with a failed step.",
                    "/app/projects/" + run.getProjectId() + "/executions");
        }
        // Outbound channels get EVERY terminal state, not just FAILED: the
        // in-app inbox above is a place you go and look, so alerting only on
        // failure is reasonable there. Slack and email are pushed at you, and
        // a tenant who asked to hear about successes has said they want them.
        if (lifecycleNotifier != null) {
            lifecycleNotifier.runFinished(run);
        }
    }

    private String line(StepExecutor.RunStep step, StepExecutor.StepOutcome outcome,
                        int maxRetries) {
        String head = String.format("[%d/%d] %s — ", step.index() + 1, step.total(), step.label());
        String attempt = maxRetries > 0
                ? " [attempt " + (step.attempt() + 1) + "/" + (maxRetries + 1) + "]" : "";
        String tail = (outcome.success()
                ? "ok (" + outcome.durationMs() + "ms)"
                : "FAILED: " + outcome.error() + " (" + outcome.durationMs() + "ms)") + attempt;
        StringBuilder line = new StringBuilder(head).append(tail).append('\n');
        // Captured output (real executors) — indented under the status line.
        if (outcome.detail() != null && !outcome.detail().isBlank()
                && !"completed".equals(outcome.detail())) {
            for (String outputLine : outcome.detail().split("\r?\n")) {
                line.append("    | ").append(outputLine).append('\n');
            }
        }
        return line.toString();
    }

    /** Jobs execute {@code steps[]}, workflows execute {@code nodes[]}. */
    List<StepExecutor.RunStep> parseSteps(Run run) {
        List<StepExecutor.RunStep> steps = new ArrayList<>();
        if (run.getDefinition() == null || run.getDefinition().isBlank()) {
            return steps;
        }
        JsonNode items;
        try {
            JsonNode root = objectMapper.readTree(run.getDefinition());
            items = root.path(run.getTargetType() == RunTargetType.JOB ? "steps" : "nodes");
        } catch (Exception ex) {
            return steps; // unparseable snapshot: nothing to execute
        }
        Map<String, Object> inputs = readInputs(run.getInputs());

        // Job-level fleet dispatch, applied to every step of the job.
        //
        // Read from `nodeDispatch` rather than `nodes` on purpose: a WORKFLOW
        // definition already uses `nodes` for its step array (see the path
        // above), so the two would be indistinguishable under one name.
        JsonNode dispatch = null;
        try {
            JsonNode root = objectMapper.readTree(run.getDefinition());
            JsonNode candidate = root.path("nodeDispatch");
            if (candidate.path("dispatch").asBoolean(false)) {
                dispatch = candidate;
            }
        } catch (Exception ex) {
            dispatch = null; // already parsed once above; nothing new to report
        }

        int total = items.size();
        for (int i = 0; i < total; i++) {
            JsonNode item = items.get(i);
            // The job designer stores the palette id ("command", "script", ...)
            // in `id`; older/mock definitions used `type`. Accept both.
            String type = item.path("type").asText(item.path("id").asText("step"));
            String label = item.path("label").asText(type + " " + (i + 1));
            steps.add(new StepExecutor.RunStep(i, total, type, label,
                    withDispatch(resolve(item, inputs, label), dispatch), 0));
        }
        return steps;
    }

    /**
     * Folds the job's fleet settings onto a step, so the executor sees one
     * self-contained node and {@link StepExecutor} keeps its narrow signature.
     *
     * <p>A step that already carries its own {@code nodeFilter} wins: the job
     * setting is the default for the job, not an override of a deliberate
     * per-step choice.
     */
    private JsonNode withDispatch(JsonNode step, JsonNode dispatch) {
        if (dispatch == null || !(step instanceof ObjectNode object)) {
            return step;
        }
        ObjectNode merged = object.deepCopy();
        if (!merged.hasNonNull("nodeFilter")) {
            String filter = dispatch.path("filter").asText("");
            if (!filter.isBlank()) {
                merged.put("nodeFilter", filter);
            }
        }
        if (!merged.hasNonNull("nodeThreadcount") && dispatch.hasNonNull("threadcount")) {
            merged.put("nodeThreadcount", dispatch.path("threadcount").asInt(1));
        }
        if (!merged.hasNonNull("nodeKeepgoing") && dispatch.hasNonNull("keepgoing")) {
            merged.put("nodeKeepgoing", dispatch.path("keepgoing").asBoolean(false));
        }
        return merged;
    }

    /** Raised when a step still holds a placeholder nothing supplied a value for. */
    static class UnresolvedInputException extends RuntimeException {
        UnresolvedInputException(String message) {
            super(message);
        }
    }

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    /**
     * Substitutes {@code {{Variable}}} in a step's text from the run's inputs.
     *
     * <p>Values arrive here already validated by {@code NativeInputValidator} —
     * against the pattern, bounds and options the workflow declared — which is
     * what makes it safe to place them into a command line at all. This method
     * deliberately does no escaping of its own: it would be a second, weaker
     * guard, and the honest control is the declared pattern.
     *
     * <p>Every textual field is walked, not just {@code value}, because a
     * connection name or a working directory is as likely to be parameterised
     * as the command itself.
     */
    private JsonNode resolve(JsonNode node, Map<String, Object> inputs, String label) {
        if (inputs.isEmpty() || !node.isObject()) {
            return requireResolved(node, label);
        }
        ObjectNode copy = node.deepCopy();
        copy.fieldNames().forEachRemaining(field -> {
            JsonNode value = copy.get(field);
            if (value != null && value.isTextual()) {
                copy.put(field, substitute(value.asText(), inputs));
            }
        });
        return requireResolved(copy, label);
    }

    private String substitute(String text, Map<String, Object> inputs) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = inputs.get(matcher.group(1));
            matcher.appendReplacement(out, value == null
                    ? Matcher.quoteReplacement(matcher.group(0))
                    : Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private JsonNode requireResolved(JsonNode node, String label) {
        List<String> missing = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual()) {
                Matcher matcher = PLACEHOLDER.matcher(value.asText());
                while (matcher.find()) {
                    missing.add(matcher.group(1));
                }
            }
        });
        if (!missing.isEmpty()) {
            throw new UnresolvedInputException("Step \"" + label + "\" needs "
                    + String.join(", ", missing.stream().distinct().toList())
                    + ", which this run supplied no value for. Nothing was executed.");
        }
        return node;
    }
}
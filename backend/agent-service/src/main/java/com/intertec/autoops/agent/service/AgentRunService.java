package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.intertec.autoops.agent.client.AutomationClient;
import com.intertec.autoops.agent.client.ModelCredentialsClient;
import com.intertec.autoops.agent.client.RuntimeClient;
import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.domain.Agent;
import com.intertec.autoops.agent.domain.AgentRun;
import com.intertec.autoops.agent.domain.AgentRunStep;
import com.intertec.autoops.agent.exception.AgentException;
import com.intertec.autoops.agent.loop.AgentToolbox;
import com.intertec.autoops.agent.loop.ChatMessage;
import com.intertec.autoops.agent.loop.ChatModel;
import com.intertec.autoops.agent.loop.ChatModels;
import com.intertec.autoops.agent.loop.ChatResponse;
import com.intertec.autoops.agent.loop.ToolCall;
import com.intertec.autoops.agent.loop.ToolResult;
import com.intertec.autoops.agent.loop.TranscriptCodec;
import com.intertec.autoops.agent.modelsdk.ModelVendor;
import com.intertec.autoops.agent.repo.AgentRepository;
import com.intertec.autoops.agent.repo.AgentRunRepository;
import com.intertec.autoops.agent.repo.AgentRunStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The agent loop.
 *
 * <p>Ask what to do. If the answer is a tool, run the tool and ask again. Stop
 * when there is nothing left to ask, when the step budget runs out, or when a
 * tool needs a human.
 *
 * <h2>Two ways of answering the first question</h2>
 * {@link #driveWithRuntime} sends the run's state to the Python reasoning
 * runtime and gets back a directive — that is the normal path, and it is where
 * phased agents, per-phase tool narrowing and the evidence ledger live. The
 * older path below asks a {@code ChatModel} directly and is kept behind
 * {@code autoops.agent.loop.runtime-enabled} as a kill switch, plus for Huawei,
 * which has an adapter here and none in Python.
 *
 * <p>Everything that makes the loop SAFE is in this class either way: the step
 * budget, the approval park, attaching to the run an approval started, and an
 * audit row per step. None of it crosses to Python, because all of it depends
 * on the database and the approvals inbox.
 *
 * <h2>Why this is not a while-loop in memory</h2>
 * A run can PAUSE for a human and not move again for two days. So the loop's
 * state is the {@code transcript} column, rewritten after every step, and
 * "resume" means reading it back. Nothing important lives on the stack.
 *
 * <h2>The approval contract</h2>
 * core-service decides whether a target needs a human; this service never
 * re-derives that. When it does, the run parks in AWAITING_APPROVAL with the
 * approval id and the id of the tool call that raised it. An admin approving
 * it in the normal approvals inbox STARTS THE RUN — and this service attaches
 * to that run rather than starting a second one. That is the whole reason the
 * agent path reuses core's approvals instead of inventing its own: one inbox,
 * one decision, one run.
 *
 * <h2>Partially answered turns</h2>
 * A model can ask for several tools in one turn, and vendors require every one
 * of them to be answered together. If the second of three needs approval, the
 * results collected SO FAR are parked and the resume path works out what is
 * still outstanding, so no result is ever emitted twice.
 *
 * <p>The two paths park that state in different places, for one reason. The
 * legacy loop owns its transcript, so a partial turn is just a partial message
 * on it and needs no extra column. Under the runtime the transcript is an
 * opaque blob this service must not parse, so the same bookkeeping lives in
 * {@code pending_calls} and {@code pending_results} — see the V5 migration.
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    /**
     * What the model is told beyond the agent's own persona.
     *
     * <p>Deliberately short. It states what is TRUE about this runtime — tools
     * really do execute, the allow-list really is closed, approvals really do
     * happen — because a model that does not know a tool has real effects will
     * treat calling one as free.
     */
    private static final String RUNTIME_PREAMBLE = """
            You are operating inside AutoOps, an IT automation platform.

            The tools you have are real automations belonging to this customer.
            Calling one EXECUTES it against their live infrastructure. There is
            no dry run and nothing is simulated.

            You can use only the tools listed. There is no way to reach anything
            else, so if the task needs something you have not been given, say so
            plainly instead of improvising around it.

            Some automations require a human to approve them. When one does, your
            call will pause until a person decides, and you will be told what they
            decided. A rejection is an answer, not an error to route around.

            Check what you need to before acting, act once you know, and finish by
            reporting what you actually did and what it returned. Do not claim an
            outcome a tool did not report.
            """;

    private final AgentRepository agentRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunStepRepository stepRepository;
    private final AgentToolbox toolbox;
    private final ChatModels chatModels;
    private final ModelCredentialsClient credentials;
    private final AutomationClient automations;
    private final RuntimeClient runtime;
    private final TranscriptCodec transcripts;
    private final SubscriptionGate gate;
    private final ObjectMapper objectMapper;
    private final RunScopeService scopes;
    private final FindingIngestService findingIngest;
    private final TaskExecutor loopExecutor;
    private final AgentProperties.Loop config;

    public AgentRunService(AgentRepository agentRepository,
                           AgentRunRepository runRepository,
                           AgentRunStepRepository stepRepository,
                           AgentToolbox toolbox,
                           ChatModels chatModels,
                           ModelCredentialsClient credentials,
                           AutomationClient automations,
                           RuntimeClient runtime,
                           TranscriptCodec transcripts,
                           SubscriptionGate gate,
                           ObjectMapper objectMapper,
                           RunScopeService scopes,
                           FindingIngestService findingIngest,
                           @Qualifier("agentLoopExecutor") TaskExecutor loopExecutor,
                           AgentProperties properties) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.toolbox = toolbox;
        this.chatModels = chatModels;
        this.credentials = credentials;
        this.automations = automations;
        this.runtime = runtime;
        this.transcripts = transcripts;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.scopes = scopes;
        this.findingIngest = findingIngest;
        this.loopExecutor = loopExecutor;
        this.config = properties.getLoop();
    }

    // ------------------------------------------------------------- start ---

    /**
     * Queues a run and returns immediately.
     *
     * <p>Asynchronous because the loop takes as long as the automations take —
     * minutes is normal. Holding an HTTP request open for that would tie a run
     * to a socket, and the first proxy timeout would lose the answer to work
     * that had already happened.
     */
    @Transactional
    public AgentRun start(String tenantId, String actor, String accessToken, Long agentId,
                          String input) {
        if (input == null || input.isBlank()) {
            throw AgentException.badRequest("input_required",
                    "Tell the agent what to do.");
        }
        Agent agent = agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> AgentException.notFound("agent_not_found", "No such agent"));
        if (!agent.isEnabled()) {
            throw AgentException.conflict("agent_disabled",
                    "This agent is disabled. Enable it before running it.");
        }
        gate.requireActive(accessToken);

        // Resolved NOW, before anything is queued, so a workspace with no
        // usable AI connection is told at the click rather than by a run that
        // appears and immediately fails.
        ModelCredentialsClient.Resolved resolved =
                credentials.resolve(tenantId, agent.getModel());
        if (!chatModels.isRunnable(resolved.vendor())) {
            throw AgentException.badRequest("vendor_not_runnable",
                    "Agents cannot run on " + resolved.vendor() + " yet.");
        }

        AgentRun run = new AgentRun();
        run.setTenantId(tenantId);
        run.setAgentId(agent.getId());
        run.setProjectId(agent.getProjectId());
        run.setInput(input.trim());
        run.setModel(resolved.model());
        run.setVendor(resolved.vendor().name());
        run.setMaxSteps(config.getMaxSteps());
        run.setCreatedBy(actor);
        run.setTranscript(transcripts.write(List.of(new ChatMessage.User(input.trim()))));
        AgentRun saved = runRepository.save(run);

        log.info("Tenant {} queued agent run {} (agent {}, model {})",
                tenantId, saved.getId(), agent.getId(), resolved.model());

        submitAfterCommit(saved.getId());
        return saved;
    }

    /**
     * The loop must not see the run before its row is committed.
     *
     * <p>{@link #start} is transactional, so handing the id straight to the
     * executor races the commit: the loop thread does its own
     * {@code findById} and, losing that race, finds nothing and silently does
     * no work. Waiting for the commit is the difference between a run that
     * starts and a run that vanishes.
     */
    private void submitAfterCommit(Long runId) {
        Runnable submit = () -> loopExecutor.execute(() -> drive(runId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit.run();
                        }
                    });
        } else {
            submit.run();
        }
    }

    // -------------------------------------------------------------- loop ---

    /**
     * Drives one run to its next stopping point: finished, or parked on a
     * human.
     *
     * <p>Runs on the loop executor, outside any transaction. Each write below
     * is its own short transaction — a run that spends ten minutes waiting on
     * a job must not hold a database connection for those ten minutes.
     */
    void drive(Long runId) {
        AgentRun run = runRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus().isTerminal()) {
            return;
        }

        try {
            Agent agent = agentRepository.findById(run.getAgentId()).orElse(null);
            if (agent == null) {
                finish(run, AgentRun.Status.FAILED, null,
                        "The agent was deleted while this run was in progress.");
                return;
            }

            if (run.getStatus() == AgentRun.Status.PENDING) {
                run.setStatus(AgentRun.Status.RUNNING);
                run.setStartedAt(Instant.now());
                run = save(run);
            }

            AgentToolbox.Toolbox tools = toolbox.build(agent);
            ModelCredentialsClient.Resolved resolved =
                    credentials.resolve(run.getTenantId(), run.getModel());

            if (usesRuntime(resolved.vendor())) {
                driveWithRuntime(run, agent, tools, resolved);
                return;
            }

            ChatModel model = chatModels.forVendor(resolved.vendor());

            List<ChatMessage> messages = transcripts.read(run.getTranscript());

            // Resume path: the transcript already ends in an assistant turn
            // whose tool calls are not all answered. Finish that turn before
            // asking the model anything new.
            if (endsWithUnansweredToolCalls(messages)) {
                run = continueToolCalls(run, agent, tools, messages);
                if (run.getStatus() != AgentRun.Status.RUNNING) {
                    return;
                }
            }

            while (true) {
                if (run.getStepCount() >= run.getMaxSteps()) {
                    finish(run, AgentRun.Status.FAILED, lastAssistantText(messages),
                            "Reached the limit of " + run.getMaxSteps() + " steps without "
                                    + "finishing. The work it did up to that point is in the "
                                    + "steps below.");
                    return;
                }

                long began = System.currentTimeMillis();
                ChatResponse response = model.chat(new ChatModel.Request(
                        resolved.vendor(), resolved.credentials(), run.getModel(),
                        systemPrompt(agent, tools), messages, tools.specs(),
                        config.getMaxTokens()));

                messages.add(new ChatMessage.Assistant(response.text(), response.toolCalls()));

                run.setStepCount(run.getStepCount() + 1);
                run.setPromptTokens(run.getPromptTokens() + response.promptTokens());
                run.setCompletionTokens(run.getCompletionTokens() + response.completionTokens());
                run.setTranscript(transcripts.write(messages));
                run = save(run);

                recordStep(run, AgentRunStep.Kind.MODEL_CALL, null, null, null,
                        "step " + run.getStepCount() + " of " + run.getMaxSteps(),
                        describe(response), false, System.currentTimeMillis() - began);

                if (response.stopReason() != ChatResponse.StopReason.TOOL_CALLS) {
                    finishOnStopReason(run, response);
                    return;
                }
                if (response.toolCalls().isEmpty()) {
                    // TOOL_CALLS with nothing to call. Continuing would ask the
                    // same question again and get the same answer forever.
                    finish(run, AgentRun.Status.FAILED, response.text(),
                            "The model asked to use a tool but named none.");
                    return;
                }

                run = continueToolCalls(run, agent, tools, messages);
                if (run.getStatus() != AgentRun.Status.RUNNING) {
                    return;
                }
            }
        } catch (AgentException ex) {
            log.warn("Agent run {} failed: {}", runId, ex.getMessage());
            failQuietly(runId, ex.getMessage());
        } catch (Exception ex) {
            log.error("Agent run {} failed unexpectedly", runId, ex);
            failQuietly(runId, "The run stopped unexpectedly: " + ex.getMessage());
        }
    }

    // ----------------------------------------------------- python runtime ---

    /**
     * Whether this run's reasoning happens in the Python runtime.
     *
     * <p>Two ways to end up on the legacy loop. The kill switch, which is
     * meant to be flipped at 3am by someone who does not want to think — and
     * Huawei, whose ModelArts endpoint has a Java adapter here and no LangChain
     * one. Routing Huawei back rather than failing it keeps a capability the
     * platform already had; silently running it on another vendor would not.
     */
    private boolean usesRuntime(ModelVendor vendor) {
        return config.isRuntimeEnabled() && vendor != ModelVendor.HUAWEI;
    }

    /**
     * The loop, with the thinking done elsewhere.
     *
     * <p>Structurally the same as the legacy one above and deliberately so:
     * ask what to do, do it, ask again. What changed is only WHO answers the
     * first question. Everything that makes this loop safe — the step budget,
     * the approval park, attaching to the run an approval started, the audit
     * row per step — is still here, because all of it depends on the database
     * and the approvals inbox, and neither of those crosses to Python.
     */
    /**
     * The phases a console-authored agent declared, in order.
     *
     * <p>Stored as a comma-separated string because it is an ordered list of a
     * handful of short enum names that nothing joins against — a child table
     * would buy referential integrity over a vocabulary that lives in Python
     * and cannot be a foreign key anyway.
     *
     * <p>Empty for every agent that exists today, which is what keeps them on
     * the un-phased loop their personas were written for. Unknown names are
     * passed through rather than filtered: the runtime is the authority on
     * which phases this build has, and dropping one here would hide a
     * catalog-ahead-of-deployment problem that it reports clearly.
     */
    private static List<String> declaredPhases(Agent agent) {
        String declared = agent.getPhases();
        if (declared == null || declared.isBlank()) {
            return List.of();
        }
        List<String> phases = new ArrayList<>();
        for (String name : declared.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && !phases.contains(trimmed)) {
                phases.add(trimmed);
            }
        }
        return phases;
    }

    private void driveWithRuntime(AgentRun run, Agent agent, AgentToolbox.Toolbox tools,
                                  ModelCredentialsClient.Resolved resolved) {
        // Finishes any turn left half-answered by an approval. Null means the
        // run is parked again, or ended while the approval was being resolved.
        Turn resumed = resumeTurn(run, agent, tools);
        if (resumed.halted()) {
            return;
        }
        run = resumed.run();
        RuntimeClient.Event event = resumed.event();

        while (true) {
            if (run.getStepCount() >= run.getMaxSteps()) {
                finish(run, AgentRun.Status.FAILED, run.getOutput(),
                        "Reached the limit of " + run.getMaxSteps() + " steps without "
                                + "finishing. The work it did up to that point is in the "
                                + "steps below.");
                return;
            }

            long began = System.currentTimeMillis();
            RuntimeClient.Reduction reduction = runtime.reduce(new RuntimeClient.Request(
                    run.getId(), run.getTenantId(), graphRefOf(agent), agent.getGraphVersion(),
                    run.getModel(), resolved.vendor(), resolved.credentials(),
                    // Only a legacy JSON agent has a persona in the tenant's
                    // row. A Python-authored one carries its own, in the
                    // runtime's image, and this is null.
                    agent.getGraphRef() == null ? agent.getInstructions() : null,
                    run.getTranscript(), event, tools.offered(), tools.skipped(),
                    // Only an agent with no module declares its own phases. A
                    // shipped agent's graph is part of what was reviewed and
                    // released, and a row that could append ACT to a read-only
                    // auditor would make a hand-edited record into a different
                    // agent running against production. The runtime enforces
                    // the same rule; sending nothing here means it never has to.
                    agent.getGraphRef() == null ? declaredPhases(agent) : List.of(),
                    // The stored allow-list, for the per-tool subject
                    // declarations inside it. The STORED one rather than the
                    // offered one on purpose — see RuntimeClient.subjectsOf.
                    agent.getTools(),
                    agent.getName()));

            run.setTranscript(reduction.state());
            run.setStateVersion(reduction.stateVersion());
            run.setPhase(reduction.phase());
            if (reduction.traceId() != null) {
                run.setTraceId(reduction.traceId());
            }
            // Advanced by the model calls the reduce ACTUALLY made, not by one.
            // A reduce can span several phases — triage straight into gather is
            // two — so counting reduces would let a phased agent make several
            // times the allowance the budget was set to give it.
            run.setStepCount(run.getStepCount() + reduction.modelCalls());
            run.setPromptTokens(run.getPromptTokens() + reduction.promptTokens());
            run.setCompletionTokens(run.getCompletionTokens() + reduction.completionTokens());
            run = save(run);

            // The run's opening coverage claim, on the first reduce that
            // reports one. Built from the agent's STATIC declarations, so it
            // does not depend on which tool happened to run first — a coverage
            // claim that varies with scheduling has no business near state
            // deletion. Everything after this may only narrow it.
            declareScopeOnce(run, reduction);

            recordStep(run, AgentRunStep.Kind.MODEL_CALL, null, null, null,
                    "step " + run.getStepCount() + " of " + run.getMaxSteps()
                            + " — phase " + reduction.phase(),
                    describe(reduction), reduction.failed(),
                    System.currentTimeMillis() - began);

            switch (reduction.directive()) {
                case FAIL -> {
                    finish(run, AgentRun.Status.FAILED, reduction.output(), reduction.error());
                    return;
                }
                case FINISH -> {
                    finishFromRuntime(run, reduction);
                    return;
                }
                case CALL_TOOLS -> {
                    Turn turn = dispatchAll(run, agent, tools, reduction);
                    if (turn.halted()) {
                        // Parked on a human. The partial results are stashed on
                        // the run; resumeTurn picks them up whenever the
                        // approval is decided.
                        return;
                    }
                    run = turn.run();
                    event = turn.event();
                }
            }
        }
    }

    /**
     * A turn's outcome, and the run row as it now stands.
     *
     * <p>The run travels back because {@code save} is a merge outside any
     * transaction, so it can hand back a DIFFERENT instance from the one it was
     * given. A caller that kept its own reference across a helper that saved
     * would go on reading a row that no longer reflects the database — and the
     * fields it would be wrong about are exactly the ones that decide whether a
     * tool runs again.
     *
     * @param event what to send the runtime next, or null when the run has
     *              halted — parked on a human, or already ended
     */
    private record Turn(AgentRun run, RuntimeClient.Event event) {

        boolean halted() {
            return event == null;
        }

        static Turn halted(AgentRun run) {
            return new Turn(run, null);
        }
    }

    /** Runs every tool the runtime asked for. */
    private Turn dispatchAll(AgentRun run, Agent agent, AgentToolbox.Toolbox tools,
                             RuntimeClient.Reduction reduction) {
        // Written down BEFORE the first tool runs. If this process dies
        // halfway through a three-tool turn, the resume path has to know what
        // was asked for — the runtime's state has it too, but that state is
        // opaque here, and re-asking the runtime would re-run the tools that
        // already went.
        run.setPendingCalls(writeJson(reduction.toolCalls()));
        run.setPendingResults(null);
        run = save(run);

        List<RuntimeClient.Result> results = new ArrayList<>();
        for (ToolCall call : reduction.toolCalls()) {
            Outcome outcome = invoke(run, agent, tools, call);
            if (outcome.parked()) {
                run.setStatus(AgentRun.Status.AWAITING_APPROVAL);
                run.setApprovalReference(String.valueOf(outcome.approvalId()));
                run.setPendingToolId(call.id());
                run.setPendingResults(writeJson(results));
                log.info("Agent run {} parked on approval {} for {}",
                        run.getId(), outcome.approvalId(), call.name());
                return Turn.halted(save(run));
            }
            results.add(resultOf(call.id(), outcome));
            // Saved after each one, so a crash costs at most the tool that was
            // in flight rather than every tool in the turn.
            run.setPendingResults(writeJson(results));
            run = save(run);
        }

        return new Turn(clearPending(run), new RuntimeClient.Event.ToolResults(results));
    }

    /**
     * Finishes an approval-interrupted turn, or reports there was none.
     *
     * @return the event to send the runtime, or null when the run cannot
     *         proceed right now — still parked, or already ended
     */
    private Turn resumeTurn(AgentRun run, Agent agent, AgentToolbox.Toolbox tools) {
        if (run.getPendingCalls() == null || run.getPendingCalls().isBlank()) {
            return new Turn(run, new RuntimeClient.Event.Start(run.getInput()));
        }

        List<ToolCall> calls = readCalls(run.getPendingCalls());
        List<RuntimeClient.Result> results = new ArrayList<>(readResults(run.getPendingResults()));
        Set<String> answered = new HashSet<>();
        results.forEach(result -> answered.add(result.callId()));

        // The call a human was deciding is resolved FIRST — that decision is
        // what the run was waiting for, and it belongs in this same turn.
        if (run.getApprovalReference() != null && run.getPendingToolId() != null) {
            ToolCall pending = find(calls, run.getPendingToolId());
            Resolution resolution = resolveApproval(run, pending);
            if (resolution.stillWaiting()) {
                return Turn.halted(run);
            }
            results.add(resultOf(run.getPendingToolId(), resolution));
            answered.add(run.getPendingToolId());
            run.setApprovalReference(null);
            run.setPendingToolId(null);
            run.setPendingResults(writeJson(results));
            run = save(run);

            // resolveApproval can END the run — an approval nobody decided in
            // time is abandoned there. Without this check the loop would carry
            // on and dispatch the REST of the turn's tool calls on a run that
            // is already FAILED, executing automations for a run that was
            // given up on.
            if (run.getStatus().isTerminal()) {
                return Turn.halted(run);
            }
        }

        for (ToolCall call : calls) {
            if (answered.contains(call.id())) {
                continue;
            }
            Outcome outcome = invoke(run, agent, tools, call);
            if (outcome.parked()) {
                run.setStatus(AgentRun.Status.AWAITING_APPROVAL);
                run.setApprovalReference(String.valueOf(outcome.approvalId()));
                run.setPendingToolId(call.id());
                run.setPendingResults(writeJson(results));
                return Turn.halted(save(run));
            }
            results.add(resultOf(call.id(), outcome));
            run.setPendingResults(writeJson(results));
            run = save(run);
        }

        return new Turn(clearPending(run), new RuntimeClient.Event.ToolResults(results));
    }

    /**
     * Ends a run the runtime says is finished, after checking its citations.
     *
     * <p>The runtime enforces the evidence rule itself; this is the second,
     * independent check, and it is the one that cannot be talked around. It
     * compares every cited id against the step rows THIS run actually wrote,
     * so an id the runtime somehow let through — a bug there, a stale state, a
     * model that guessed a plausible number — still cannot reach an operator
     * looking like a verified fact.
     */
    /**
     * Opens the run's coverage claim, once.
     *
     * <p>Failures here are logged and swallowed. A run whose scope could not be
     * recorded still produced a report somebody asked for, and the cost of the
     * missing claim is that nothing it found is ever reaped by absence — which
     * is the safe direction. Failing the run instead would trade a working
     * report for a coverage record nobody is yet reading.
     *
     * <p>Like {@link #completeScope}, that trade expires when the reaper does
     * not: a run that silently failed to declare still gets reaped against
     * <i>other</i> runs' claims, and nothing here would have said so.
     */
    private void declareScopeOnce(AgentRun run, RuntimeClient.Reduction reduction) {
        if (run.getScopeStatus() != null || reduction.declaredSubjectKinds().isEmpty()) {
            return;
        }
        try {
            ArrayNode intent = objectMapper.createArrayNode();
            for (String kind : reduction.declaredSubjectKinds()) {
                intent.addObject().put("kind", "all").put("subject_kind", kind);
            }
            scopes.declare(run.getId(), intent, Map.of());
        } catch (RuntimeException e) {
            log.warn("run {} could not declare its scope; nothing it finds will be "
                    + "reaped by absence: {}", run.getId(), e.getMessage());
        }
    }

    /**
     * Files the run's verdicts, and says how many were accepted.
     *
     * <p><b>Called while the run is still open</b> — see the ordering note at
     * the call site. Attribution refuses a verdict whose run has already settled
     * its coverage claim, so this cannot move below {@link #completeScope}.
     *
     * <p>Each verdict is ingested on its own transaction and its own
     * disposition: one malformed verdict must not cost a nightly sweep of four
     * hundred good ones. A rejection is logged rather than thrown for the same
     * reason the scope calls swallow — the report is what somebody asked for.
     *
     * @return how many were stored, which is what the coverage claim records as
     *         {@code verdicts_emitted}. A run that examined a thousand subjects
     *         and emitted nothing is a clean estate; one that emitted nothing
     *         because every verdict was refused is not, and the two are only
     *         distinguishable if this number is the ACCEPTED count.
     */
    private int ingestVerdicts(AgentRun run, RuntimeClient.Reduction reduction) {
        int accepted = 0;
        for (JsonNode verdict : reduction.verdicts()) {
            try {
                FindingIngestService.Result result =
                        findingIngest.ingest(run.getTenantId(), run.getProjectId(),
                                run.getId(), verdict);
                if (result.disposition() == FindingIngestService.Disposition.REJECTED) {
                    log.warn("run {} verdict {} refused ({}): {}", run.getId(),
                            result.verdictId(), result.reasonCode(), result.error());
                } else {
                    accepted++;
                }
            } catch (RuntimeException e) {
                log.warn("run {} could not file a verdict: {}", run.getId(), e.getMessage());
            }
        }
        return accepted;
    }

    /**
     * Records what the run actually covered, per subject kind.
     *
     * <p><b>This must be called BEFORE {@link #finish}, and the order is not
     * arbitrary.</b> Completion validation re-reads the run, and
     * {@code VerdictAttributionService} treats a run with {@code finished_at}
     * set as closed — so completing after finishing would have the run refuse
     * its own claim as arriving late. The symptom would be every agent's
     * completion rejected with {@code run_already_completed}, a reason code that
     * means "the agent raced its own completion" and would point the
     * investigation at agent timing while the cause sat in this method's
     * statement order. Do not reorder these two calls during a tidy-up.
     *
     * <p><b>Failures are logged and swallowed — and that has an expiry date.</b>
     * Today nothing reads coverage, so a refused claim costs this run its reap
     * and nothing else, while a thrown exception would cost the report somebody
     * actually asked for. That trade only holds while the reaper is off. Once it
     * is live, a silently swallowed scope failure becomes a run that LOOKS
     * covered and is not — which is the failure this whole subsystem exists to
     * prevent. Whoever enables the reaper has to revisit this: see the reaper
     * gate in {@code backend/MIGRATIONS.md}.
     */
    private void completeScope(AgentRun run, RuntimeClient.Reduction reduction, int emitted) {
        if (!reduction.carriesCoverage()) {
            return;
        }
        try {
            Map<String, Integer> evaluated = new LinkedHashMap<>();
            reduction.subjectScope().forEach(element ->
                    evaluated.put(element.path("subject_kind").asText(),
                            element.path("subject_id_count").asInt()));
            scopes.complete(run.getId(), reduction.subjectScope(), reduction.subjectIds(),
                    evaluated, emitted);
        } catch (RuntimeException e) {
            log.warn("run {} coverage claim refused: {}", run.getId(), e.getMessage());
        }
    }

    private void finishFromRuntime(AgentRun run, RuntimeClient.Reduction reduction) {
        List<String> problems = new ArrayList<>(reduction.uncitedClaims());

        if (!reduction.citations().isEmpty()) {
            Set<Long> real = new HashSet<>(stepRepository.findIdsByRunId(run.getId()));
            for (Long cited : reduction.citations()) {
                if (!real.contains(cited)) {
                    problems.add("[e:" + cited + "] is not a step of this run.");
                }
            }
        }

        String output = reduction.output();
        if (!problems.isEmpty()) {
            run.setUncitedClaims(String.join("\n", problems));
            log.warn("Agent run {} reported {} unsupported claim(s)", run.getId(), problems.size());
        }
        // ORDER IS LOAD-BEARING, and tighter than it looks: verdicts FIRST,
        // then coverage, then finish.
        //
        // VerdictAttributionService.isClosed() is true as soon as scope_status
        // moves past RUNNING — not only when finished_at is set. So ingesting
        // after completeScope() rejects EVERY verdict as run_already_completed,
        // a reason code meaning "the agent raced its own completion", which
        // would send the investigation after agent timing while the cause sat
        // in these three lines. Ingesting after finish() does the same thing for
        // the other reason. Do not reorder.
        int emitted = ingestVerdicts(run, reduction);
        completeScope(run, reduction, emitted);

        // Still a SUCCESS. The run did the work and produced a report; the
        // report simply carries a visible warning about the parts it could not
        // substantiate. Failing it would leave the operator with nothing
        // during exactly the incident they needed it for.
        finish(run, AgentRun.Status.SUCCEEDED, output, null);
    }

    private AgentRun clearPending(AgentRun run) {
        run.setPendingCalls(null);
        run.setPendingResults(null);
        return save(run);
    }

    /** A tool that ran on its own, in the shape the evidence ledger wants. */
    private static RuntimeClient.Result resultOf(String callId, Outcome outcome) {
        ToolResult result = outcome.result();
        return new RuntimeClient.Result(callId, !result.isError(), result.content(),
                outcome.evidenceId(), null, null);
    }

    /**
     * A tool that went through the approvals inbox.
     *
     * <p>The verdict travels ON the result rather than as its own event,
     * because a turn can hold several tool calls and every vendor requires
     * them answered together — the turn where a human rejected the second of
     * three still has to carry the other two. The runtime lifts the decision
     * off the result and routes on it: a rejection goes to the report, never
     * back to planning a different way in.
     */
    private static RuntimeClient.Result resultOf(String callId, Resolution resolution) {
        ToolResult result = resolution.result();
        return new RuntimeClient.Result(callId, !result.isError(), result.content(),
                resolution.evidenceId(),
                Boolean.TRUE.equals(resolution.approved()) ? "APPROVED" : "REJECTED",
                resolution.decidedBy());
    }

    private List<ToolCall> readCalls(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCall>>() { });
        } catch (Exception ex) {
            log.warn("Unreadable pending tool calls on a run: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<RuntimeClient.Result> readResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RuntimeClient.Result>>() { });
        } catch (Exception ex) {
            log.warn("Unreadable pending tool results on a run: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Which module in the runtime's registry runs this agent. */
    private static String graphRefOf(Agent agent) {
        return agent.getGraphRef() == null || agent.getGraphRef().isBlank()
                ? null : agent.getGraphRef();
    }

    private String describe(RuntimeClient.Reduction reduction) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("phase", reduction.phase());
        summary.put("directive", reduction.directive().name());
        summary.put("output", reduction.output());
        summary.put("error", reduction.error());
        summary.put("toolCalls", reduction.toolCalls().stream()
                .map(call -> Map.of("name", call.name(), "arguments", call.arguments()))
                .toList());
        summary.put("promptTokens", reduction.promptTokens());
        summary.put("completionTokens", reduction.completionTokens());
        summary.put("modelCalls", reduction.modelCalls());
        summary.put("traceId", reduction.traceId());
        return writeJson(summary);
    }

    /**
     * Answers every outstanding tool call of the latest assistant turn.
     *
     * <p>Serves both the first attempt and the resume: the outstanding set is
     * derived from the transcript, so neither path needs to know which one it
     * is. Returns with the run still RUNNING when the turn is complete, or
     * parked in AWAITING_APPROVAL when a call needs a human.
     */
    private AgentRun continueToolCalls(AgentRun run, Agent agent, AgentToolbox.Toolbox tools,
                                       List<ChatMessage> messages) {
        ChatMessage.Assistant assistant = lastAssistant(messages);
        if (assistant == null) {
            return run;
        }

        List<ToolResult> results = new ArrayList<>(takePendingResults(messages));
        Set<String> answered = new HashSet<>();
        results.forEach(result -> answered.add(result.toolCallId()));

        // A run parked on an approval resolves THAT call first — the human's
        // decision is what it was waiting for, and it belongs in the same
        // turn's results.
        if (run.getApprovalReference() != null && run.getPendingToolId() != null) {
            ToolCall pending = find(assistant.toolCalls(), run.getPendingToolId());
            Resolution resolution = resolveApproval(run, pending);
            if (resolution.stillWaiting()) {
                // Nothing changed. Put the partial results back and stay parked.
                stash(run, messages, results);
                return run;
            }
            results.add(resolution.result());
            answered.add(run.getPendingToolId());
            run.setApprovalReference(null);
            run.setPendingToolId(null);
            run = save(run);

            // resolveApproval can END the run — an approval nobody decided in
            // time is abandoned there. Without this check the loop would carry
            // straight on and dispatch the REST of the turn's tool calls on a
            // run that is already FAILED, executing automations against a
            // customer's infrastructure for a run that was given up on.
            if (run.getStatus().isTerminal()) {
                stash(run, messages, results);
                return run;
            }
        }

        for (ToolCall call : assistant.toolCalls()) {
            if (answered.contains(call.id())) {
                continue;
            }
            Outcome outcome = invoke(run, agent, tools, call);
            if (outcome.parked()) {
                run.setStatus(AgentRun.Status.AWAITING_APPROVAL);
                run.setApprovalReference(String.valueOf(outcome.approvalId()));
                run.setPendingToolId(call.id());
                stash(run, messages, results);
                log.info("Agent run {} parked on approval {} for {}",
                        run.getId(), outcome.approvalId(), call.name());
                return run;
            }
            results.add(outcome.result());
            answered.add(call.id());
        }

        // The turn is complete: replace any partial results with the full set.
        stash(run, messages, results);
        return run;
    }

    /** Writes the results back onto the transcript as ONE tool-results turn. */
    private void stash(AgentRun run, List<ChatMessage> messages, List<ToolResult> results) {
        if (!messages.isEmpty() && messages.getLast() instanceof ChatMessage.ToolResults) {
            messages.removeLast();
        }
        messages.add(new ChatMessage.ToolResults(List.copyOf(results)));
        run.setTranscript(transcripts.write(messages));
        save(run);
    }

    // -------------------------------------------------------------- tool ---

    /**
     * What a tool call produced.
     *
     * @param approvalId set only when {@code parked}
     * @param evidenceId the step row recording the result, which the report is
     *                   allowed to cite. Null when the call never reached a
     *                   tool — an unknown name, say — because there is then no
     *                   observation to point at, and a citation an operator
     *                   cannot open is worse than none.
     */
    private record Outcome(ToolResult result, Long approvalId, Long evidenceId) {

        boolean parked() {
            return approvalId != null;
        }

        static Outcome of(ToolResult result) {
            return new Outcome(result, null, null);
        }

        static Outcome of(Observed observed) {
            return new Outcome(observed.result(), null, observed.evidenceId());
        }

        static Outcome park(Long approvalId) {
            return new Outcome(null, approvalId, null);
        }
    }

    /** A tool result together with the audit row that proves it happened. */
    private record Observed(ToolResult result, Long evidenceId) {
    }

    private Outcome invoke(AgentRun run, Agent agent, AgentToolbox.Toolbox tools, ToolCall call) {
        AgentToolbox.Tool tool = tools.resolve(call.name());
        if (tool == null) {
            // The allow-list is the whole authority. A name that is not on it
            // is answered, not looked up: the model gets a real error it can
            // recover from, and nothing outside the list is ever reachable.
            recordStep(run, AgentRunStep.Kind.TOOL_CALL, null, null, call.name(),
                    writeJson(call.arguments()), "no such tool", true, null);
            return Outcome.of(ToolResult.error(call.id(),
                    "There is no tool called \"" + call.name() + "\". Available: "
                            + String.join(", ", tools.byName().keySet())));
        }

        long began = System.currentTimeMillis();
        recordStep(run, AgentRunStep.Kind.TOOL_CALL, tool.type(), tool.targetId(),
                tool.targetName(), writeJson(call.arguments()), null, false, null);

        AutomationClient.Dispatch dispatch;
        try {
            dispatch = automations.dispatch(run.getTenantId(), actorOf(agent), tool.type(),
                    tool.targetId(), call.arguments());
        } catch (AgentException ex) {
            // A refusal from core — a missing required input, an approval
            // already queued — is the model's to fix. It comes back as a tool
            // error rather than killing the run.
            recordStep(run, AgentRunStep.Kind.TOOL_RESULT, tool.type(), tool.targetId(),
                    tool.targetName(), null, ex.getMessage(), true,
                    System.currentTimeMillis() - began);
            return Outcome.of(ToolResult.error(call.id(), ex.getMessage()));
        }

        if (dispatch.needsApproval()) {
            recordStep(run, AgentRunStep.Kind.APPROVAL_REQUESTED, tool.type(), tool.targetId(),
                    tool.targetName(), null,
                    "Approval #" + dispatch.approvalId() + " is waiting for an admin.",
                    false, System.currentTimeMillis() - began);
            return Outcome.park(dispatch.approvalId());
        }

        return Outcome.of(watch(run, tool, call.id(), dispatch.runId(), began));
    }

    /**
     * Waits for a started run and turns it into something worth telling a
     * model.
     *
     * <p>The wait is a poll rather than a callback because core-service has no
     * way to call back into a specific paused loop, and adding one would mean
     * this service could only be run as a single instance.
     */
    /** Where the adaptive tool poll starts before doubling toward the configured cap. */
    private static final Duration MIN_TOOL_POLL = Duration.ofMillis(250);

    private Observed watch(AgentRun run, AgentToolbox.Tool tool, String toolCallId,
                             Long targetRunId, long began) {
        Instant deadline = Instant.now().plus(config.getToolTimeout());
        AutomationClient.RunState state = null;

        // The interval GROWS rather than staying flat. Agent tools are mostly
        // short — a boto3 read finishes in six to eight seconds — and a fixed
        // three-second poll finds them, on average, a second and a half after
        // they were already done. Across two tools that is three seconds of a
        // demo spent waiting for a timer rather than for work. Starting at a
        // quarter second and doubling to the configured interval costs a
        // handful of extra local calls on a short run and leaves a
        // twenty-minute one polling exactly as often as it does today.
        Duration cap = config.getToolPollInterval();
        Duration wait = MIN_TOOL_POLL.compareTo(cap) > 0 ? cap : MIN_TOOL_POLL;

        while (Instant.now().isBefore(deadline)) {
            state = automations.runState(run.getTenantId(), targetRunId);
            if (state.terminal()) {
                break;
            }
            if (!sleep(wait)) {
                break;
            }
            Duration next = wait.multipliedBy(2);
            wait = next.compareTo(cap) > 0 ? cap : next;
        }

        long took = System.currentTimeMillis() - began;

        if (state == null || !state.terminal()) {
            // Honest, and specifically NOT reported as a failure: the run is
            // still going. Telling the model it failed would invite it to
            // "retry" work that is currently running.
            // Worded to CLOSE the turn, not to hand the model an opening.
            //
            // The first version said only "it has not failed — it is still in
            // progress and can be watched in the Runs view", and a model read
            // that as an invitation: it called the same automation again,
            // starting a second expensive run of a workflow that was still
            // executing. Telling it what is true is not enough; the sentence
            // has to say what to DO, and rule out the obvious wrong move.
            String message = "Run #" + targetRunId + " for \"" + tool.targetName()
                    + "\" is STILL RUNNING after " + config.getToolTimeout().toMinutes()
                    + " minutes. It has NOT failed and it has NOT finished.\n\n"
                    + "DO NOT call this tool again. A second call starts a SECOND run of the "
                    + "same automation while the first is still going — it does not retry it "
                    + "and it does not speed it up.\n\n"
                    + "Stop here. Tell the operator the automation is still running, give them "
                    + "run #" + targetRunId + " to watch in the Runs view, and say that the "
                    + "result will be there when it finishes.";
            Long evidenceId = recordStep(run, AgentRunStep.Kind.TOOL_RESULT, tool.type(),
                    tool.targetId(), tool.targetName(), null, message, false, took);
            return new Observed(ToolResult.ok(toolCallId, message), evidenceId);
        }

        String summary = "Run #" + targetRunId + " for \"" + tool.targetName() + "\" finished "
                + state.status() + " (" + state.stepCompleted() + " of " + state.stepTotal()
                + " steps)."
                + (state.error() == null || state.error().isBlank()
                        ? "" : "\nError: " + state.error())
                + "\nLog:\n" + (state.log() == null || state.log().isBlank()
                        ? "(no output)" : state.log());

        // Two audiences, one event. The model gets `summary` below — it is
        // diagnosing, and the engine's detail occasionally distinguishes a
        // missing credential from a wrong one. What is STORED, and therefore
        // what the customer reads in the run timeline, is the same events with
        // the engine's bookkeeping removed: no node ids, no execution ids, no
        // internal project slug, no Java data-context types.
        String cleanedLog = CustomerFacingLog.clean(state.log());
        String display = "Run #" + targetRunId + " for \"" + tool.targetName() + "\" finished "
                + state.status() + " (" + state.stepCompleted() + " of " + state.stepTotal()
                + " steps)."
                + (cleanedLog.isBlank() ? "" : "\n" + cleanedLog);

        Long evidenceId = recordStep(run, AgentRunStep.Kind.TOOL_RESULT, tool.type(),
                tool.targetId(), tool.targetName(), null, display, !state.succeeded(), took);

        // A failed automation is reported through is_error so the model treats
        // it as a failure rather than as text that happens to mention one. It
        // still carries an evidence id: "the check failed" is an observation,
        // and an agent must be able to cite it rather than reporting a host as
        // healthy because the collection errored.
        return new Observed(state.succeeded()
                ? ToolResult.ok(toolCallId, summary)
                : ToolResult.error(toolCallId, summary), evidenceId);
    }

    // ---------------------------------------------------------- approval ---

    private record Resolution(ToolResult result, Long evidenceId, Boolean approved,
                              String decidedBy) {

        Resolution(ToolResult result) {
            this(result, null, null, null);
        }

        boolean stillWaiting() {
            return result == null;
        }

        static Resolution waiting() {
            return new Resolution(null, null, null, null);
        }
    }

    /** Who decided, or a neutral stand-in when core did not record a name. */
    private static String decidedBy(AutomationClient.ApprovalState approval) {
        return approval.decidedBy() == null ? "An admin" : approval.decidedBy();
    }

    private Resolution resolveApproval(AgentRun run, ToolCall pending) {
        if (pending == null) {
            // The column names a tool call the transcript does not contain, so
            // the two disagree. Answering the id anyway would send a vendor a
            // result for a call it never saw, which comes back as an opaque
            // 400 several frames away from the actual problem.
            finish(run, AgentRun.Status.FAILED, null,
                    "This run was waiting on a tool call that is no longer in its saved "
                            + "conversation, so it cannot be resumed. The approval is still "
                            + "in the inbox and can be decided there.");
            return new Resolution(ToolResult.error(run.getPendingToolId(), "unresumable"));
        }
        String toolCallId = pending.id();

        AutomationClient.ApprovalState approval;
        try {
            approval = automations.approvalState(run.getTenantId(),
                    Long.valueOf(run.getApprovalReference()));
        } catch (RuntimeException ex) {
            log.warn("Approval {} unreadable for run {}: {}", run.getApprovalReference(),
                    run.getId(), ex.getMessage());
            return Resolution.waiting();
        }

        if (approval.pending()) {
            if (run.getStartedAt() != null && Instant.now()
                    .isAfter(run.getStartedAt().plus(config.getApprovalTimeout()))) {
                finish(run, AgentRun.Status.FAILED, null,
                        "Nobody decided the approval within "
                                + config.getApprovalTimeout().toDays() + " days, so the run "
                                + "was abandoned. The approval is still in the inbox.");
                return new Resolution(ToolResult.error(toolCallId, "abandoned"));
            }
            return Resolution.waiting();
        }

        if (!approval.approved()) {
            String who = approval.decidedBy() == null ? "An admin" : approval.decidedBy();
            Long evidenceId = recordStep(run, AgentRunStep.Kind.APPROVAL_GRANTED, null, null, null,
                    null, who + " rejected the request.", true, null);
            return new Resolution(ToolResult.error(toolCallId,
                    who + " REJECTED this request, so the automation did not run. Do not try "
                            + "to run it again — report the rejection."), evidenceId, false, who);
        }

        Long grantedId = recordStep(run, AgentRunStep.Kind.APPROVAL_GRANTED, null, null, null, null,
                (approval.decidedBy() == null ? "An admin" : approval.decidedBy())
                        + " approved the request; run #" + approval.runId() + " started.",
                false, null);

        if (approval.runId() == null) {
            return new Resolution(ToolResult.error(toolCallId,
                    "The request was approved but no run was recorded against it."),
                    grantedId, true, decidedBy(approval));
        }

        // Approving STARTED the run in core-service. Attaching to it is the
        // point: starting another here would run the automation twice.
        AgentToolbox.Tool tool = new AgentToolbox.Tool("approved", null, null,
                approval.targetName() == null
                        ? "the approved automation" : approval.targetName(), true);
        Observed observed = watch(run, tool, toolCallId, approval.runId(),
                System.currentTimeMillis());
        return new Resolution(observed.result(), observed.evidenceId(), true, decidedBy(approval));
    }

    /**
     * Wakes every run parked on a human and sees whether they decided.
     *
     * <p>Polling rather than a callback, and it is called from a scheduler so
     * that a run parked when this process last restarted still gets picked up.
     * A run whose approval is still pending costs one internal GET per tick.
     */
    public void resumeApproved() {
        List<AgentRun> parked = runRepository.findByStatus(AgentRun.Status.AWAITING_APPROVAL);
        for (AgentRun run : parked) {
            if (run.getApprovalReference() == null) {
                continue;
            }
            try {
                AutomationClient.ApprovalState approval = automations.approvalState(
                        run.getTenantId(), Long.valueOf(run.getApprovalReference()));
                boolean expired = run.getStartedAt() != null && Instant.now()
                        .isAfter(run.getStartedAt().plus(config.getApprovalTimeout()));
                if (approval.pending() && !expired) {
                    continue;
                }
                // Back to RUNNING before handing it to the loop: two ticks
                // must not drive the same run at once.
                run.setStatus(AgentRun.Status.RUNNING);
                AgentRun released = save(run);
                loopExecutor.execute(() -> drive(released.getId()));
            } catch (RuntimeException ex) {
                log.warn("Could not check approval {} for agent run {}: {}",
                        run.getApprovalReference(), run.getId(), ex.getMessage());
            }
        }
    }

    // --------------------------------------------------------- lifecycle ---

    @Transactional
    public AgentRun cancel(String tenantId, Long runId) {
        AgentRun run = runRepository.findByIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> AgentException.notFound("run_not_found", "No such run"));
        if (run.getStatus().isTerminal()) {
            throw AgentException.conflict("run_finished",
                    "This run has already finished.");
        }
        run.setStatus(AgentRun.Status.CANCELLED);
        run.setFinishedAt(Instant.now());
        // Deliberately honest: an automation already started in core-service
        // keeps going. Saying otherwise would have an operator believe a
        // reboot was stopped when it was not.
        run.setError("Cancelled. Any automation already started keeps running — "
                + "stop it from the Runs view.");
        return runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public AgentRun get(String tenantId, Long runId) {
        return runRepository.findByIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> AgentException.notFound("run_not_found", "No such run"));
    }

    @Transactional(readOnly = true)
    public List<AgentRun> listForAgent(String tenantId, Long agentId) {
        agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> AgentException.notFound("agent_not_found", "No such agent"));
        return runRepository.findTop100ByAgentIdAndTenantIdOrderByIdDesc(agentId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<AgentRunStep> steps(String tenantId, Long runId) {
        AgentRun run = get(tenantId, runId);
        return stepRepository.findByRunIdOrderBySeqAsc(run.getId());
    }

    // ---------------------------------------------------------- plumbing ---

    private void finishOnStopReason(AgentRun run, ChatResponse response) {
        switch (response.stopReason()) {
            case END_TURN -> finish(run, AgentRun.Status.SUCCEEDED, response.text(), null);

            // Not a success. The answer is cut off mid-sentence, and half an
            // answer about what happened to a production server is worse than
            // an honest failure.
            case MAX_TOKENS -> finish(run, AgentRun.Status.FAILED, response.text(),
                    "The model's reply hit the length limit and was cut off.");

            case REFUSAL -> finish(run, AgentRun.Status.FAILED, response.text(),
                    "The model declined to answer.");

            default -> finish(run, AgentRun.Status.FAILED, response.text(),
                    "The model stopped for an unrecognised reason.");
        }
    }

    private void finish(AgentRun run, AgentRun.Status status, String output, String error) {
        run.setStatus(status);
        run.setOutput(output);
        run.setError(error);
        run.setFinishedAt(Instant.now());
        save(run);
        log.info("Agent run {} finished {} after {} step(s)", run.getId(), status,
                run.getStepCount());
    }

    /** Last-resort failure write; never throws over the original problem. */
    private void failQuietly(Long runId, String message) {
        try {
            runRepository.findById(runId).ifPresent(run -> {
                if (!run.getStatus().isTerminal()) {
                    finish(run, AgentRun.Status.FAILED, run.getOutput(), message);
                }
            });
        } catch (RuntimeException ex) {
            log.error("Could not record the failure of agent run {}", runId, ex);
        }
    }

    /**
     * Plain repository writes, NOT {@code @Transactional} methods.
     *
     * <p>They are called from inside this class, where Spring's proxy would
     * not apply the annotation anyway — an annotation that silently does
     * nothing is worse than none. Each is a single-entity save, which
     * {@code SimpleJpaRepository} already wraps in its own transaction, and
     * that is exactly the granularity the loop wants: a run parked for two
     * days must not be holding one open.
     */
    private AgentRun save(AgentRun run) {
        return runRepository.save(run);
    }

    /**
     * @return the id of the row just written — the EVIDENCE ID.
     *
     * <p>A TOOL_RESULT row's primary key is what the Python runtime's evidence
     * ledger cites, and what an operator clicks through to from a report. That
     * is the whole reason the ledger needs no table of its own: it indexes rows
     * this loop was already writing for the audit trail.
     */
    private Long recordStep(AgentRun run, AgentRunStep.Kind kind, String toolType,
                              Long toolTargetId, String toolName, String request,
                              String response, boolean error, Long durationMs) {
        AgentRunStep step = new AgentRunStep();
        step.setRunId(run.getId());
        step.setSeq((int) stepRepository.countByRunId(run.getId()) + 1);
        step.setKind(kind);
        step.setToolType(toolType);
        step.setToolTargetId(toolTargetId);
        step.setToolName(toolName);
        step.setRequest(request);
        step.setResponse(response);
        step.setError(error);
        step.setDurationMs(durationMs);
        return stepRepository.save(step).getId();
    }

    /**
     * The persona, plus what the runtime itself has to say.
     *
     * <p>Tools that could not be offered are NAMED here rather than omitted
     * silently. An agent whose only relevant tool has been deleted should say
     * "I cannot do that any more", not quietly do something else instead.
     */
    private String systemPrompt(Agent agent, AgentToolbox.Toolbox tools) {
        StringBuilder prompt = new StringBuilder(RUNTIME_PREAMBLE);
        if (agent.getInstructions() != null && !agent.getInstructions().isBlank()) {
            prompt.append("\n\n---\n\n").append(agent.getInstructions().trim());
        }
        if (!tools.skipped().isEmpty()) {
            prompt.append("\n\n---\n\nUnavailable right now:\n");
            for (String reason : tools.skipped()) {
                prompt.append("- ").append(reason).append('\n');
            }
        }
        if (tools.isEmpty()) {
            prompt.append("\n\nYou have NO tools in this run. Answer from what you know and "
                    + "say clearly that you could not act.");
        }
        return prompt.toString();
    }

    /** How this agent appears in core-service's run history and audit trail. */
    private String actorOf(Agent agent) {
        return "agent:" + agent.getName() + "#" + agent.getId();
    }

    private boolean endsWithUnansweredToolCalls(List<ChatMessage> messages) {
        ChatMessage.Assistant assistant = lastAssistant(messages);
        if (assistant == null || assistant.toolCalls().isEmpty()) {
            return false;
        }
        Set<String> answered = new HashSet<>();
        takePendingResults(messages).forEach(result -> answered.add(result.toolCallId()));
        return assistant.toolCalls().stream().anyMatch(call -> !answered.contains(call.id()));
    }

    /** Results already recorded against the latest assistant turn, if any. */
    private List<ToolResult> takePendingResults(List<ChatMessage> messages) {
        if (!messages.isEmpty() && messages.getLast() instanceof ChatMessage.ToolResults results) {
            return results.results();
        }
        return List.of();
    }

    private ChatMessage.Assistant lastAssistant(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ChatMessage.Assistant assistant) {
                return assistant;
            }
        }
        return null;
    }

    private String lastAssistantText(List<ChatMessage> messages) {
        ChatMessage.Assistant assistant = lastAssistant(messages);
        return assistant == null ? null : assistant.text();
    }

    private static ToolCall find(List<ToolCall> calls, String id) {
        return calls.stream().filter(call -> call.id().equals(id)).findFirst().orElse(null);
    }

    /** @return false if the wait was interrupted */
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String describe(ChatResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stopReason", response.stopReason().name());
        summary.put("text", response.text());
        summary.put("toolCalls", response.toolCalls().stream()
                .map(call -> Map.of("name", call.name(), "arguments", call.arguments()))
                .toList());
        summary.put("promptTokens", response.promptTokens());
        summary.put("completionTokens", response.completionTokens());
        return writeJson(summary);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}

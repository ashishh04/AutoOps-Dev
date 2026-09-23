package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.EntitlementClient;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.execution.StepExecutor;
import com.intertec.autoops.core.repo.CommandRecordRepository;
import com.intertec.autoops.core.repo.ConnectorRepository;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import com.intertec.autoops.core.repo.SecretRepository;
import com.intertec.autoops.core.repo.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Secrets, webhooks, library, commands, connectors against H2. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SecretService.class, WebhookService.class, LibraryService.class,
        CommandService.class, ConnectorService.class, CredentialCrypto.class,
        SubscriptionGate.class, ProjectService.class, JobService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantFeaturesServiceTest {

    /** JobService now mirrors jobs onto the engine; this slice has no rundeck-service. */
    @MockBean
    private com.intertec.autoops.core.client.RundeckJobClient rundeckJobClient;

    private static final String TENANT = "acme-corp-cafe0123";
    private static final String ACTOR = "admin@acme.io";
    private static final String TOKEN = "test-access-token";
    private static final EntitlementClient.Decision OK =
            new EntitlementClient.Decision(true, "ok", null, null);
    private static final EntitlementClient.Decision NO_FEATURE =
            new EntitlementClient.Decision(false, "feature_not_in_plan", null, null);

    @Autowired
    private SecretService secretService;
    @Autowired
    private WebhookService webhookService;
    @Autowired
    private LibraryService libraryService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ConnectorService connectorService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JobService jobService;
    @Autowired
    private SecretRepository secretRepository;
    @Autowired
    private WebhookRepository webhookRepository;
    @Autowired
    private LibraryItemRepository libraryRepository;
    @Autowired
    private CommandRecordRepository commandRepository;
    @Autowired
    private ConnectorRepository connectorRepository;
    @Autowired
    private com.intertec.autoops.core.repo.JobRepository jobRepository;
    @Autowired
    private com.intertec.autoops.core.repo.ProjectRepository projectRepository;
    @MockBean
    private EntitlementClient entitlementClient;
    /** WebhookService resolves WORKFLOW targets through workflow-service now. */
    @MockBean
    private WorkflowClient workflowClient;
    /**
     * LibraryService reads live rollout counts from both services that hold
     * delivered copies. Mocked to nothing here: these tests are about the
     * catalog rows, and an unmocked client would make them depend on a
     * cross-service call.
     */
    @MockBean
    private com.intertec.autoops.core.client.AgentClient agentClient;
    @MockBean
    private RunService runService;
    /**
     * ProjectService now tells the execution engine when a project is created,
     * renamed or archived. Mocked rather than wired: these tests are about the
     * tenant's own data, and the real client would try to reach rundeck-service.
     */
    @MockBean
    private com.intertec.autoops.core.client.RundeckProjectClient rundeckProjectClient;

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CoreProperties coreProperties() {
            return new CoreProperties();
        }

        /** Deterministic executor: echoes the command back as output. */
        @Bean
        StepExecutor stepExecutor() {
            return (tenantId, projectId, step) -> StepExecutor.StepOutcome.ok(
                    "ran: " + step.raw().path("value").asText(), 5);
        }
    }

    @BeforeEach
    void reset() {
        webhookRepository.deleteAll();
        secretRepository.deleteAll();
        libraryRepository.deleteAll();
        commandRepository.deleteAll();
        connectorRepository.deleteAll();
        jobRepository.deleteAll();
        projectRepository.deleteAll();
        when(entitlementClient.checkActive(any())).thenReturn(OK);
        when(entitlementClient.checkQuota(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(OK);
        when(entitlementClient.checkTenant(any())).thenReturn(OK);
        when(entitlementClient.checkFeature(any(), any())).thenReturn(OK);
    }

    // ------ secrets ------

    @Test
    void secretValuesAreEncryptedAndNeverReadable() {
        secretService.create(TENANT, ACTOR, TOKEN, "apps/prod/api-key", "opaque", "s3cr3t");
        var stored = secretRepository.findByTenantIdOrderByPathAsc(TENANT).get(0);
        assertFalse(stored.getValueEnc().contains("s3cr3t"), "plaintext must never persist");

        CoreException dup = assertThrows(CoreException.class, () ->
                secretService.create(TENANT, ACTOR, TOKEN, "apps/prod/api-key", "opaque", "x"));
        assertEquals("secret_exists", dup.getError());

        CoreException badPath = assertThrows(CoreException.class, () ->
                secretService.create(TENANT, ACTOR, TOKEN, "bad path!", "opaque", "x"));
        assertEquals("invalid_path", badPath.getError());
    }

    // ------ webhooks ------

    @Test
    void webhookFireStartsTheBoundJobWhenEntitled() {
        var project = projectService.create(TENANT, ACTOR, TOKEN, "Alpha", null);
        var job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "deploy",
                null, null, "{\"steps\":[]}", null, null);
        var webhook = webhookService.create(TENANT, ACTOR, TOKEN, "gh-hook", "job", job.getId());
        assertEquals(43, webhook.getToken().length());

        Run fakeRun = new Run();
        when(runService.runFromWebhook(any(com.intertec.autoops.core.domain.Job.class), any()))
                .thenReturn(fakeRun);

        assertNotNull(webhookService.fire(webhook.getToken()));
        var stamped = webhookRepository.findByIdAndTenantId(webhook.getId(), TENANT).orElseThrow();
        assertEquals("accepted", stamped.getLastStatus());
    }

    @Test
    void webhookFireIsBlockedForUnentitledTenants() {
        var project = projectService.create(TENANT, ACTOR, TOKEN, "Alpha", null);
        var job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "deploy",
                null, null, "{\"steps\":[]}", null, null);
        var webhook = webhookService.create(TENANT, ACTOR, TOKEN, "gh-hook", "job", job.getId());

        when(entitlementClient.checkTenant(TENANT)).thenReturn(
                new EntitlementClient.Decision(false, "trial_expired", null, null));
        CoreException ex = assertThrows(CoreException.class,
                () -> webhookService.fire(webhook.getToken()));
        assertEquals("trial_expired", ex.getError());

        var stamped = webhookRepository.findByIdAndTenantId(webhook.getId(), TENANT).orElseThrow();
        assertTrue(stamped.getLastStatus().startsWith("denied:"));
    }

    @Test
    void unknownOrDisabledTokensAre404() {
        assertThrows(CoreException.class, () -> webhookService.fire("nope"));
    }

    // ------ library ------

    /**
     * Scripts are the only catalog type a customer imports — workflows and
     * agents are delivered by a rollout instead — so the premium gate is
     * asserted on a script.
     */
    @Test
    void premiumTemplateCloneIsFeatureGated() {
        var item = libraryService.createPlatform("autoops", "K8s restart", null,
                "script", "Kubernetes", "{\"steps\":[]}", true);
        when(entitlementClient.checkFeature(eq(TOKEN), eq("PREMIUM_TEMPLATES")))
                .thenReturn(NO_FEATURE);

        CoreException ex = assertThrows(CoreException.class,
                () -> libraryService.clone(TENANT, ACTOR, TOKEN, item.getId()));
        assertEquals("feature_not_in_plan", ex.getError());

        when(entitlementClient.checkFeature(eq(TOKEN), eq("PREMIUM_TEMPLATES"))).thenReturn(OK);
        var copy = libraryService.clone(TENANT, ACTOR, TOKEN, item.getId());
        assertEquals(TENANT, copy.getTenantId());
        assertEquals(1, libraryRepository.findByIdAndTenantIdIsNull(item.getId())
                .orElseThrow().getInstalls());

        CoreException dup = assertThrows(CoreException.class,
                () -> libraryService.clone(TENANT, ACTOR, TOKEN, item.getId()));
        assertEquals("template_owned", dup.getError());
    }

    /**
     * An agent can be published from the console at all.
     *
     * <p>The bug this pins was total and silent. {@code validateDefinition}
     * demanded {@code steps[]} or {@code nodes[]} of EVERY catalog item, and an
     * agent's definition — a persona, a model and an allow-list — has neither.
     * So every publish from the provider's agent builder was refused with
     * "Template definition must be JSON with a steps[] or nodes[] array", a
     * message about a shape an agent is not supposed to have.
     *
     * <p>It went unnoticed for a reason worth recording: none of the agents in
     * the catalog arrived this way. Every one was inserted by
     * {@code agents/_schema/publish.py}, which writes the row directly and
     * never reaches this check. The builder's own tests passed against a mocked
     * API. The whole path was complete except for the save.
     */
    @Test
    void anAgentDefinitionCanBePublishedToTheCatalog() {
        var item = libraryService.createPlatform("autoops", "Cost Analyst", "Finds waste",
                "agent", "AutoOps",
                "{\"description\":\"d\",\"model\":\"m\",\"instructions\":\"i\","
                        + "\"tools\":[{\"type\":\"WORKFLOW\",\"ref\":\"RD-136\"}],"
                        + "\"phases\":[\"GATHER\",\"REPORT\"]}", false);

        assertEquals(com.intertec.autoops.core.domain.LibraryItem.Type.AGENT, item.getType());
        assertTrue(item.getDefinition().contains("GATHER"));
    }

    @Test
    void aCodeAuthoredAgentIsPublishableWithNoToolsOfItsOwn() {
        // A PYTHON agent carries a reference to a module in the runtime's image
        // and deliberately no persona and no allow-list. Requiring tools[] of
        // every agent would make exactly the agents with the strongest sealing
        // the ones that cannot be published.
        var item = libraryService.createPlatform("autoops", "RCA", "Investigates",
                "agent", "AWS",
                "{\"kind\":\"PYTHON\",\"ref\":\"aws.incident_rca_analyst\","
                        + "\"version\":\"1.0.0\"}", false);

        assertEquals(com.intertec.autoops.core.domain.LibraryItem.Type.AGENT, item.getType());
    }

    @Test
    void anAgentDefinitionThatNamesNothingRunnableIsStillRefused() {
        // The check is now per type, not absent. An agent with neither an
        // allow-list nor a module reference can do nothing at all, and a
        // catalog row that cannot do anything is rollable — the customer is the
        // one who finds out.
        CoreException ex = assertThrows(CoreException.class,
                () -> libraryService.createPlatform("autoops", "Hollow", null, "agent",
                        "Ops", "{\"description\":\"nothing here\"}", false));

        assertEquals("invalid_definition", ex.getError());
    }

    @Test
    void aWorkflowStillHasToCarryItsGraph() {
        CoreException ex = assertThrows(CoreException.class,
                () -> libraryService.createPlatform("autoops", "Empty", null, "workflow",
                        "Ops", "{\"tools\":[]}", false));

        assertEquals("invalid_definition", ex.getError());
    }

    /**
     * Importing a workflow or agent template would hand the tenant an editable
     * copy of the provider's design — the whole thing the provider-authored
     * model prevents. Refused ahead of the plan check, because no plan tier
     * unlocks it: those arrive by rollout or not at all.
     */
    @Test
    void workflowAndAgentTemplatesCannotBeImportedAtAll() {
        when(entitlementClient.checkFeature(eq(TOKEN), eq("PREMIUM_TEMPLATES"))).thenReturn(OK);

        // Each type gets a definition of its OWN shape. They used to share
        // {"nodes":[]} because the definition check was one rule for every
        // type — the same leniency that made a real agent, which has no
        // nodes[], impossible to publish from the console.
        for (String type : new String[] {"workflow", "agent"}) {
            String definition = "agent".equals(type) ? "{\"tools\":[]}" : "{\"nodes\":[]}";
            var item = libraryService.createPlatform("autoops", "Managed " + type, null,
                    type, "Ops", definition, false);

            CoreException ex = assertThrows(CoreException.class,
                    () -> libraryService.clone(TENANT, ACTOR, TOKEN, item.getId()));
            assertEquals("rollout_only", ex.getError(), type + " must not be importable");
        }
    }

    /**
     * Writing a script needs a live subscription and nothing else. It was once
     * behind PRIVATE_TEMPLATES, which could not hold: any plan may import a
     * free catalog script and rewrite it, so the flag only produced a 403 on
     * the honest path to the same result.
     */
    @Test
    void authoringAScriptNeedsNoPlanFeature() {
        when(entitlementClient.checkFeature(eq(TOKEN), eq("PRIVATE_TEMPLATES")))
                .thenReturn(NO_FEATURE);

        var mine = libraryService.createOwn(TENANT, ACTOR, TOKEN, "Mine", null,
                "script", null, "{\"steps\":[]}");

        assertNotNull(mine.getId());
        assertEquals(TENANT, mine.getTenantId());
    }

    /** ...but a dead subscription still stops both writing and adapting. */
    @Test
    void anExpiredSubscriptionStopsScriptAuthoring() {
        when(entitlementClient.checkActive(TOKEN)).thenReturn(
                new EntitlementClient.Decision(false, "trial_expired", null, null));

        CoreException ex = assertThrows(CoreException.class,
                () -> libraryService.createOwn(TENANT, ACTOR, TOKEN, "Mine", null,
                        "script", null, "{\"steps\":[]}"));
        assertEquals("trial_expired", ex.getError());
    }

    @Test
    void lockedStateIsComputedFromThePlan() {
        libraryService.createPlatform("autoops", "Premium thing", null, "script",
                "Cloud", "{\"steps\":[]}", true);
        when(entitlementClient.checkFeature(eq(TOKEN), eq("PREMIUM_TEMPLATES")))
                .thenReturn(NO_FEATURE);
        var views = libraryService.list(TENANT, TOKEN);
        assertTrue(views.get(0).locked(), "premium + no feature = locked");
    }

    /**
     * Adapting an imported script is the POINT of importing one — and the
     * provider's original must not move when a customer edits their copy.
     */
    @Test
    void editingAnImportedScriptLeavesTheCatalogOriginalAlone() {
        var original = libraryService.createPlatform("autoops", "Disk check", "Provider copy",
                "script", "Ops", "{\"steps\":[{\"value\":\"df -h\"}]}", false);
        var copy = libraryService.clone(TENANT, ACTOR, TOKEN, original.getId());

        libraryService.update(TENANT, TOKEN, copy.getId(), "Disk check (ours)", "Adapted",
                "Ops", "{\"steps\":[{\"value\":\"df -h /data\"}]}");

        var reloadedCopy = libraryRepository.findByIdAndTenantId(copy.getId(), TENANT)
                .orElseThrow();
        assertEquals("Disk check (ours)", reloadedCopy.getTitle());
        assertTrue(reloadedCopy.getDefinition().contains("/data"));

        var reloadedOriginal = libraryRepository.findByIdAndTenantIdIsNull(original.getId())
                .orElseThrow();
        assertEquals("Disk check", reloadedOriginal.getTitle(), "catalog original was edited");
        assertFalse(reloadedOriginal.getDefinition().contains("/data"));
    }

    /**
     * The tenant boundary, from the other side: a catalog row is reachable by
     * id, so the only thing stopping a customer editing the provider's
     * original is that update reads by (id, tenantId) together.
     */
    @Test
    void aCustomerCannotEditTheCatalogOriginal() {
        var original = libraryService.createPlatform("autoops", "Untouchable", null,
                "script", "Ops", "{\"steps\":[]}", false);

        CoreException ex = assertThrows(CoreException.class,
                () -> libraryService.update(TENANT, TOKEN, original.getId(), "Hijacked",
                        null, null, null));
        assertEquals("template_not_found", ex.getError());
    }

    /** Only scripts are a customer's to write — the same rule clone enforces. */
    @Test
    void aCustomerCannotAuthorWorkflowsOrAgents() {
        for (String type : new String[] {"workflow", "agent"}) {
            CoreException ex = assertThrows(CoreException.class,
                    () -> libraryService.createOwn(TENANT, ACTOR, TOKEN, "Mine " + type, null,
                            type, null, "{\"nodes\":[]}"));
            assertEquals("script_only", ex.getError(), type + " must not be authorable");
        }
    }

    /**
     * Every paying plan may adapt an imported script — a script a customer may
     * take but may not edit is not one they own.
     */
    @Test
    void adaptingAnImportedScriptNeedsOnlyALiveSubscription() {
        var original = libraryService.createPlatform("autoops", "Log cleanup", null,
                "script", "Ops", "{\"steps\":[{\"value\":\"rm -rf /tmp/*\"}]}", false);
        var copy = libraryService.clone(TENANT, ACTOR, TOKEN, original.getId());

        // The plan that cannot author its own templates can still adapt this.
        when(entitlementClient.checkFeature(eq(TOKEN), eq("PRIVATE_TEMPLATES")))
                .thenReturn(NO_FEATURE);

        libraryService.update(TENANT, TOKEN, copy.getId(), null, null, null,
                "{\"steps\":[{\"value\":\"rm -rf /tmp/cache\"}]}");

        assertTrue(libraryRepository.findByIdAndTenantId(copy.getId(), TENANT).orElseThrow()
                .getDefinition().contains("/tmp/cache"));
    }

    /** A rename must not force the caller to resend the whole script body. */
    @Test
    void aPartialEditKeepsWhatItDoesNotSend() {
        var mine = libraryService.createOwn(TENANT, ACTOR, TOKEN, "Backup", "Nightly",
                "script", "Ops", "{\"steps\":[{\"value\":\"pg_dump\"}]}");

        libraryService.update(TENANT, TOKEN, mine.getId(), "Backup (weekly)", null, null, null);

        var reloaded = libraryRepository.findByIdAndTenantId(mine.getId(), TENANT).orElseThrow();
        assertEquals("Backup (weekly)", reloaded.getTitle());
        assertEquals("Nightly", reloaded.getDescription());
        assertTrue(reloaded.getDefinition().contains("pg_dump"), "script body was lost");
    }

    // ------ commands ------

    @Test
    void dispatchRunsTheCommandAndStoresHistory() {
        var record = commandService.dispatch(TENANT, ACTOR, TOKEN, "uptime");
        assertEquals(com.intertec.autoops.core.domain.CommandRecord.Status.SUCCEEDED,
                record.getStatus());
        assertTrue(record.getOutput().contains("ran: uptime"));
        assertEquals(1, commandService.history(TENANT).size());
    }

    // ------ connectors ------

    @Test
    void connectorConfigIsValidatedPerKindAndEncrypted() {
        CoreException bad = assertThrows(CoreException.class, () ->
                connectorService.create(TENANT, ACTOR, TOKEN, "slack_webhook", "Team Slack",
                        "{\"url\":\"http://insecure\"}"));
        assertEquals("invalid_config", bad.getError());

        var connector = connectorService.create(TENANT, ACTOR, TOKEN, "github", "Repo",
                "{\"repo\":\"acme/infra\",\"token\":\"ghp_x\"}");
        var stored = connectorRepository.findByIdAndTenantId(connector.getId(), TENANT)
                .orElseThrow();
        assertFalse(stored.getConfigEnc().contains("ghp_x"), "config must be encrypted");
        assertEquals(null, stored.getLastTestOk(), "never tested = no invented status");
    }
}

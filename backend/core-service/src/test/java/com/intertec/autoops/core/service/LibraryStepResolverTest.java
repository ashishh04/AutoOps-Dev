package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The link between the library and execution.
 *
 * <p>Before this, a job step could hold a pasted copy of a script's body and
 * nothing else — the library row and the thing actually running were two
 * unrelated strings. These cases pin the three properties that make a
 * reference safe: it resolves to the right body, it cannot reach outside the
 * workspace's own rows, and a broken reference stops the run rather than being
 * skipped.
 */
class LibraryStepResolverTest {

    private static final String TENANT = "acme-corp-cafe0123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LibraryItemRepository repository;
    private LibraryStepResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryItemRepository.class);
        resolver = new LibraryStepResolver(repository, MAPPER);
    }

    private static LibraryItem script(long id, String title, String body) {
        LibraryItem item = new LibraryItem();
        setId(item, id);
        item.setTenantId(TENANT);
        item.setTitle(title);
        item.setCategory("AWS");
        item.setType(LibraryItem.Type.SCRIPT);
        item.setDefinition("{\"steps\":[{\"label\":\"" + title + "\",\"value\":"
                + MAPPER.valueToTree(body) + "}]}");
        return item;
    }

    /** The id is database-generated; these cases need one without a database. */
    private static void setId(LibraryItem item, long id) {
        try {
            Field field = LibraryItem.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------ resolution --

    @Test
    void rewritesALibraryReferenceIntoARunnableStep() throws Exception {
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(
                Optional.of(script(42, "AWS Certificate Expiry Monitor",
                        "#Requires -Version 5.1\nparam([string]$Region)")));

        String resolved = resolver.resolve(TENANT, """
                {"steps":[{"type":"library","libraryItemId":42,"label":"Check certs"}]}
                """);

        JsonNode step = MAPPER.readTree(resolved).path("steps").get(0);
        assertThat(step.path("type").asText()).isEqualTo("powershell");
        assertThat(step.path("value").asText()).contains("#Requires -Version 5.1");
        assertThat(step.path("label").asText()).isEqualTo("Check certs");
        // Provenance survives into the snapshot so the run log and audit trail
        // can name the script, not just replay an anonymous body.
        assertThat(step.path("libraryItemId").asLong()).isEqualTo(42L);
        assertThat(step.path("libraryTitle").asText())
                .isEqualTo("AWS Certificate Expiry Monitor");
    }

    @Test
    void emitsThePathTheLibrarysImportModuleLineExpects() throws Exception {
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(
                Optional.of(script(42, "AWS Certificate Expiry Monitor", "[CmdletBinding()]")));

        String resolved = resolver.resolve(TENANT,
                "{\"steps\":[{\"type\":\"library\",\"libraryItemId\":42}]}");

        // Two levels deep, because every catalog script reaches for its module
        // at $PSScriptRoot/../../Modules — a script at the workspace root has
        // no such directory and fails on its first statement.
        assertThat(MAPPER.readTree(resolved).path("steps").get(0).path("scriptPath").asText())
                .isEqualTo("Scripts/AWS/AWS_Certificate_Expiry_Monitor.ps1");
    }

    @Test
    void keepsTheStepsOwnPolicyFields() throws Exception {
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(
                Optional.of(script(42, "Cleanup", "[CmdletBinding()]")));

        String resolved = resolver.resolve(TENANT, """
                {"steps":[{"type":"library","libraryItemId":42,"retries":3,
                           "continueOnError":true,"connection":"prod-aws"}]}
                """);

        JsonNode step = MAPPER.readTree(resolved).path("steps").get(0);
        // The library supplies WHAT runs. Everything the author decided about
        // HOW it runs is the step's own and must survive the rewrite.
        assertThat(step.path("retries").asInt()).isEqualTo(3);
        assertThat(step.path("continueOnError").asBoolean()).isTrue();
        assertThat(step.path("connection").asText()).isEqualTo("prod-aws");
    }

    @Test
    void labelsAnUnlabelledStepWithTheScriptsTitle() throws Exception {
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(
                Optional.of(script(42, "Cleanup", "[CmdletBinding()]")));

        String resolved = resolver.resolve(TENANT,
                "{\"steps\":[{\"type\":\"library\",\"libraryItemId\":42}]}");

        assertThat(MAPPER.readTree(resolved).path("steps").get(0).path("label").asText())
                .isEqualTo("Cleanup");
    }

    @Test
    void resolvesWorkflowNodesAsWellAsJobSteps() throws Exception {
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(
                Optional.of(script(42, "Cleanup", "[CmdletBinding()]")));

        // Jobs execute steps[], workflows execute nodes[] — the run engine reads
        // a different key for each, so both have to be walked.
        String resolved = resolver.resolve(TENANT,
                "{\"nodes\":[{\"type\":\"library\",\"libraryItemId\":42}]}");

        assertThat(MAPPER.readTree(resolved).path("nodes").get(0).path("type").asText())
                .isEqualTo("powershell");
    }

    // --------------------------------------------------------------- refusal --

    @Test
    void refusesAnIdThisWorkspaceDoesNotOwn() {
        // Premium catalog items are gated at import. A step that could name a
        // catalog id directly would run premium content without passing that
        // gate — a paywall bypass reachable by editing one number.
        when(repository.findByIdAndTenantId(eq(99L), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TENANT,
                "{\"steps\":[{\"type\":\"library\",\"libraryItemId\":99,\"label\":\"X\"}]}"))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("not in this workspace");
    }

    @Test
    void refusesAReferenceToAWorkflowOrAgent() {
        LibraryItem workflow = script(42, "Patch Fleet", "irrelevant");
        workflow.setType(LibraryItem.Type.WORKFLOW);
        when(repository.findByIdAndTenantId(42L, TENANT)).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> resolver.resolve(TENANT,
                "{\"steps\":[{\"type\":\"library\",\"libraryItemId\":42}]}"))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("rather than a script");
    }

    @Test
    void refusesAStepThatNamesNoScript() {
        assertThatThrownBy(() -> resolver.resolve(TENANT,
                "{\"steps\":[{\"type\":\"library\",\"label\":\"Unfinished\"}]}"))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("names no script");
    }

    @Test
    void refusesRatherThanSkippingABrokenReference() {
        // Dropping the step and carrying on would produce a job that reports
        // success having silently skipped the work it exists to do — the worst
        // outcome available here.
        when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TENANT, """
                {"steps":[{"type":"command","value":"echo one"},
                          {"type":"library","libraryItemId":7}]}
                """)).isInstanceOf(CoreException.class);
    }

    // ------------------------------------------------------------ pass-through --

    @Test
    void leavesADefinitionWithNoLibraryStepsAlone() {
        String definition = "{\"steps\":[{\"type\":\"command\",\"value\":\"echo hi\"}]}";

        // Same instance back, not an equal one: every run of every job passes
        // through here and the overwhelming majority reference nothing.
        assertThat(resolver.resolve(TENANT, definition)).isSameAs(definition);
    }

    @Test
    void leavesNullAndUnparseableDefinitionsAlone() {
        assertThat(resolver.resolve(TENANT, null)).isNull();
        // The engine reports on an unreadable definition with far better
        // context than a resolver could.
        assertThat(resolver.resolve(TENANT, "\"library\" but not json"))
                .isEqualTo("\"library\" but not json");
    }
}

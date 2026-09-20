package com.intertec.autoops.jobs.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.jobs.config.JobProperties;
import com.intertec.autoops.jobs.sandbox.StepSandbox;
import com.intertec.autoops.jobs.sandbox.StepWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout half of the library-to-execution path, plus the guard that keeps
 * the module shipped in the image identical to the one authored in the repo.
 */
class ScriptLayoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * CI runs as root with no step-user pool, where the production policy is to
     * refuse the step outright. Tests opt out of that refusal; nothing else may.
     */
    private static StepSandbox sandbox() {
        JobProperties properties = new JobProperties();
        properties.getSandbox().setAllowRootSteps(true);
        return new StepSandbox(properties);
    }

    // ---------------------------------------------------------------- paths --

    @Test
    void acceptsAPlainRelativePath() {
        assertEquals(Path.of("Scripts", "AWS", "Cert_Expiry.ps1"),
                ScriptLayout.safeRelative("Scripts/AWS/Cert_Expiry.ps1"));
    }

    @Test
    void normalizesWindowsSeparators() {
        // core-service emits forward slashes, but a definition hand-edited on a
        // Windows box is the obvious way backslashes arrive.
        assertEquals(Path.of("Scripts", "AWS", "Cert.ps1"),
                ScriptLayout.safeRelative("Scripts\\AWS\\Cert.ps1"));
    }

    @Test
    void refusesTraversalRatherThanStrippingIt() {
        // Rejected, not sanitized: silently rewriting the destination is harder
        // to notice than a refusal, and this platform never emits such a path.
        assertNull(ScriptLayout.safeRelative("../../etc/cron.d/x.ps1"));
        assertNull(ScriptLayout.safeRelative("Scripts/../../x.ps1"));
    }

    @Test
    void refusesAbsolutePaths() {
        assertNull(ScriptLayout.safeRelative("/etc/profile.d/x.ps1"));
        assertNull(ScriptLayout.safeRelative("C:\\Windows\\x.ps1"));
    }

    @Test
    void treatsAbsenceAsNoLayoutWanted() {
        assertNull(ScriptLayout.safeRelative(null));
        assertNull(ScriptLayout.safeRelative("   "));
    }

    // ----------------------------------------------------------- materialize --

    @Test
    void writesTheScriptWhereTheLibraryExpectsToFindItsModule(@TempDir Path root)
            throws Exception {
        Path modules = Files.createDirectories(root.resolve("library/Modules"));
        Files.writeString(modules.resolve("IT-Automation-Common.psm1"), "# module");
        System.setProperty(ScriptLayout.LIBRARY_ROOT_PROPERTY,
                root.resolve("library").toString());

        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace,
                    MAPPER.readTree("{\"scriptPath\":\"Scripts/AWS/Cert.ps1\"}"),
                    "Write-Host hi", ".ps1");

            assertEquals("Write-Host hi", Files.readString(script));
            assertEquals(workspace.workingDirectory().resolve("Scripts/AWS/Cert.ps1"), script);
            // `../../Modules` from Scripts/AWS resolves to the workspace root's
            // Modules directory — which is the entire point of the two-deep
            // layout, and the line every catalog script opens with.
            assertTrue(Files.exists(script.getParent()
                    .resolve("../../Modules/IT-Automation-Common.psm1").normalize()));
        } finally {
            System.clearProperty(ScriptLayout.LIBRARY_ROOT_PROPERTY);
        }
    }

    @Test
    void fallsBackToAFlatFileWithoutLibraryProvenance() throws Exception {
        // A hand-written step references no module tree, so building one for it
        // would be waste. This is the pre-existing behaviour, unchanged.
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace, MAPPER.readTree("{}"),
                    "Write-Host hi", ".ps1");

            assertEquals("Write-Host hi", Files.readString(script));
            assertTrue(script.getFileName().toString().endsWith(".ps1"));
            assertFalse(Files.exists(workspace.workingDirectory().resolve("Scripts")));
        }
    }

    @Test
    void keepsAnEscapingPathInsideTheWorkspace() throws Exception {
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace,
                    MAPPER.readTree("{\"scriptPath\":\"../../../escaped.ps1\"}"),
                    "Write-Host hi", ".ps1");

            // safeRelative refuses the path, so this degrades to a flat file
            // inside the workspace. The assertion that matters is containment,
            // not which branch produced it.
            assertTrue(script.toAbsolutePath().normalize().startsWith(
                    workspace.workingDirectory().getParent().toAbsolutePath().normalize()),
                    "step escaped its workspace: " + script);
        }
    }

    // -------------------------------------------------------------- launcher --

    @Test
    void writesTheScriptExactlyAsAuthored() throws Exception {
        // The setup must NOT be prepended. A param() block and its
        // [CmdletBinding()] / [OutputType()] attributes have to be the first
        // statement in a PowerShell file, so anything above them fails at PARSE
        // time with "Unexpected attribute 'OutputType'". That is not a corner
        // case: it is what a real run of a real catalog script did before the
        // launcher existed, and 119 of the 213 catalog scripts are param()
        // scripts. Writing the body verbatim also keeps the run's audit
        // snapshot honest — the file on disk is the script the catalog holds.
        String body = "#Requires -Version 5.1\n[CmdletBinding()]\nparam([string]$X)\n";
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace,
                    MAPPER.readTree("{\"scriptPath\":\"Scripts/Sec/X.ps1\"}"), body, ".ps1");

            assertEquals(body, Files.readString(script));
        }
    }

    @Test
    void launcherInvokesTheScriptAndForwardsArguments() throws Exception {
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace,
                    MAPPER.readTree("{\"scriptPath\":\"Scripts/Sec/X.ps1\"}"), "param()", ".ps1");
            String launcher = Files.readString(ScriptLayout.launcher(workspace, script));

            assertTrue(launcher.contains("@args"), launcher);
            assertTrue(launcher.contains(ScriptLayout.quote(script.toAbsolutePath().toString())),
                    "the launcher does not invoke the script it was given");
        }
    }

    @Test
    void launcherStillTurnsAThrownErrorIntoAFailedStep() throws Exception {
        // pwsh exits 0 after a terminating error under -File, so without this a
        // failed automation would be recorded as a success.
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace, MAPPER.readTree("{}"),
                    "param()", ".ps1");
            String launcher = Files.readString(ScriptLayout.launcher(workspace, script));

            assertTrue(launcher.contains("$ErrorActionPreference = 'Stop'"));
            assertTrue(launcher.contains("exit 1"));
        }
    }

    @Test
    void launcherWrapsImportModuleAsASimpleGlobalFunction() throws Exception {
        // `&` runs the script in a CHILD scope, so the override has to be
        // global to be visible there. And it must be a SIMPLE function: @args
        // then re-binds the caller's arguments exactly, where an advanced one
        // would collect them into an array and turn `-Force` into a positional
        // string.
        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace, MAPPER.readTree("{}"),
                    "param()", ".ps1");
            String launcher = Files.readString(ScriptLayout.launcher(workspace, script));

            int start = launcher.indexOf("function global:Import-Module {");
            assertTrue(start >= 0, "the launcher no longer defines the wrapper");

            String body = launcher.substring(start + "function global:Import-Module {".length());
            assertEquals("try {", body.strip().lines().findFirst().orElse("").strip(),
                    "the wrapper gained a param block; @args no longer re-binds faithfully");
            // Not a swallow: a module that genuinely does not exist must fail,
            // or a script runs on undefined functions and reports success.
            assertTrue(body.contains("throw"));
        }
    }

    @Test
    void launcherMatchesTheLibrarysActualImportLine() throws Exception {
        // This regex is what turns the catalog's
        // '..\..\Modules\IT-Automation-Common.psm1' into a module NAME to
        // re-resolve, which is the whole rescue on Linux. If it stops matching
        // that shape, 213 scripts break and nothing else would notice.
        String argument = "..\\..\\Modules\\IT-Automation-Common.psm1";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([^\\\\/]+)\\.psm1$").matcher(argument);
        assertTrue(matcher.find());
        assertEquals("IT-Automation-Common", matcher.group(1));

        try (StepWorkspace workspace = sandbox().acquire()) {
            Path script = ScriptLayout.materialize(workspace, MAPPER.readTree("{}"),
                    "param()", ".ps1");
            assertTrue(Files.readString(ScriptLayout.launcher(workspace, script))
                    .contains("([^\\\\/]+)\\.psm1$"));
        }
    }

    @Test
    void quotesAPathWithAnApostropheForPowerShell() {
        // The launcher invokes the script through a single-quoted literal, so a
        // workspace path containing an apostrophe would otherwise end the
        // string early and produce a parse error rather than a run.
        assertEquals("'/w/O''Brien/x.ps1'", ScriptLayout.quote("/w/O'Brien/x.ps1"));
    }

    @Test
    void suffixFollowsTheStepType() {
        assertEquals(".ps1", ScriptLayout.suffixFor("powershell"));
        assertEquals(".ps1", ScriptLayout.suffixFor("PWSH"));
        assertEquals(".py", ScriptLayout.suffixFor("pyscript"));
        assertEquals(".sh", ScriptLayout.suffixFor("script"));
        assertEquals(".sh", ScriptLayout.suffixFor(null));
    }

    // ------------------------------------------------------------------ drift --

    @Test
    void theShippedModuleMatchesTheAuthoredOne() throws Exception {
        Path shipped = Path.of("library/Modules/IT-Automation-Common.psm1");
        Path authored = Path.of("../../Scripts/Modules/IT-Automation-Common.psm1");
        if (!Files.exists(authored)) {
            // The Docker build's context is backend/job-service, so the repo's
            // Scripts/ tree is not reachable from there. Nothing to compare;
            // this check does its job when the suite runs from a full checkout.
            return;
        }
        assertTrue(Files.exists(shipped),
                "backend/job-service/library/Modules/IT-Automation-Common.psm1 is missing — "
                        + "the image ships that copy, and without it every catalog script "
                        + "fails on its Import-Module line.");
        assertTrue(java.util.Arrays.equals(
                        Files.readAllBytes(shipped), Files.readAllBytes(authored)),
                "backend/job-service/library/Modules/IT-Automation-Common.psm1 has drifted from "
                        + "Scripts/Modules/IT-Automation-Common.psm1. The image ships the former; "
                        + "copy the latter over it.");
    }
}

package com.intertec.autoops.jobs.execution.powershell;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passing a library script its parameters.
 *
 * <p><b>Why this is load-bearing rather than a convenience.</b> The catalog's
 * scripts are {@code param()} scripts: 119 of the 213 declare at least one
 * {@code [Parameter(Mandatory)]}, and {@code -NonInteractive} turns a missing
 * mandatory parameter into an immediate failure rather than a prompt. Of the
 * scripts that need no PowerShell module and tolerate a missing config — the
 * only ones this deployment could otherwise run today — <b>not one</b> is
 * runnable without arguments. A library step that could not carry them could
 * not run a single catalog script.
 */
class PowerShellRunnerArgumentsTest {

    private static final Path SCRIPT = Path.of("/w/Scripts/Sec/Cert.ps1");

    @Test
    void passesParametersAfterTheScriptPath() {
        List<String> command = PowerShellRunner.interpreter(SCRIPT,
                "-Endpoint example.com -WarnDays 30");

        int file = command.indexOf("-File");
        assertTrue(file >= 0, command.toString());
        // Everything after `-File <path>` is bound to the script's own
        // parameters, which is exactly the binding wanted.
        assertEquals("-Endpoint", command.get(file + 2));
        assertEquals("example.com", command.get(file + 3));
        assertEquals("-WarnDays", command.get(file + 4));
        assertEquals("30", command.get(file + 5));
    }

    @Test
    void keepsAQuotedValueAsOneArgument() {
        // `-Reason` on the cleanup workflow has a 10..256 character pattern, so
        // its value is a sentence. Naive whitespace splitting would pass four
        // arguments and the script would reject them.
        List<String> command = PowerShellRunner.interpreter(SCRIPT,
                "-Reason \"unattached volume cleanup\"");

        assertTrue(command.contains("unattached volume cleanup"),
                "a quoted value was split: " + command);
    }

    @Test
    void cannotBeInjectedInto() {
        // ProcessSupport execs an argv ARRAY — there is no shell to inject
        // into — so a value full of shell metacharacters stays one literal
        // argument and reaches PowerShell as a string.
        List<String> command = PowerShellRunner.interpreter(SCRIPT,
                "-Name \"; rm -rf / #\"");

        assertTrue(command.contains("; rm -rf / #"), command.toString());
        assertFalse(command.contains("rm"), "the value was split into argv: " + command);
    }

    @Test
    void addsNothingWhenAStepDeclaresNoArguments() {
        List<String> bare = PowerShellRunner.interpreter(SCRIPT);
        assertEquals(bare, PowerShellRunner.interpreter(SCRIPT, null));
        assertEquals(bare, PowerShellRunner.interpreter(SCRIPT, "   "));
    }

    @Test
    void keepsTheFlagsThatMakeAnAutomationHostSafe() {
        List<String> command = PowerShellRunner.interpreter(SCRIPT, "-X 1");

        // -NoProfile: a machine-local profile must not change how an automation
        // behaves. -NonInteractive: a prompt fails fast instead of hanging
        // until the step's timeout.
        assertTrue(command.contains("-NoProfile"));
        assertTrue(command.contains("-NonInteractive"));
    }
}

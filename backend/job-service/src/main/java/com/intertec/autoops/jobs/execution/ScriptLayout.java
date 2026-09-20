package com.intertec.autoops.jobs.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.intertec.autoops.jobs.sandbox.StepWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Builds, inside a step's private workspace, the directory layout the script
 * library was written against.
 *
 * <h2>The problem this solves</h2>
 *
 * Every one of the library's 213 PowerShell automations opens with:
 *
 * <pre>{@code
 * Import-Module (Join-Path -Path $PSScriptRoot -ChildPath '..\..\Modules\IT-Automation-Common.psm1') -Force
 * }</pre>
 *
 * That line encodes a repository layout — {@code Scripts/<area>/x.ps1} with a
 * sibling {@code Modules/} two levels up — which existed on the author's
 * machine and nowhere in this platform. A script written to a flat temp file
 * has no {@code ../../Modules}, so it failed on its first statement, before
 * any automation logic ran at all. The interpreter was installed, the runner
 * was correct, and every script still failed.
 *
 * <p>So: reproduce the layout. The step's body is written to the path
 * core-service resolved for it ({@code scriptPath}), and the shared module and
 * config tree are placed where that path expects to find them.
 *
 * <h2>The second problem, which the layout alone does not solve</h2>
 *
 * On Linux a backslash is an ordinary filename character, not a separator, so
 * {@code Join-Path /ws/Scripts/AWS '..\..\Modules\IT-Automation-Common.psm1'}
 * yields one absurd filename rather than a path two levels up — correct layout
 * or not. Rewriting the script text would fix it and is rejected: the text is
 * the customer's, it is what the audit snapshot records as having run, and a
 * regex that edits automation source on its way to the interpreter is a
 * footgun with no floor.
 *
 * <p>{@link #launcher} handles it instead, in the session rather than in the
 * file — see there. The script itself is written byte-for-byte as authored.
 *
 * <h2>Everything stays inside the workspace</h2>
 *
 * The module tree is COPIED per step, never mounted or shared. A step runs as
 * its own throwaway OS user and may write anywhere it can reach; a shared
 * module directory would be a channel between two tenants' steps, and a
 * writable one would let a step edit the module the next step imports.
 */
public final class ScriptLayout {

    private static final Logger log = LoggerFactory.getLogger(ScriptLayout.class);

    /** Where the image keeps the pristine library tree. Overridable for tests. */
    public static final String LIBRARY_ROOT_PROPERTY = "autoops.jobs.library-root";
    private static final String DEFAULT_LIBRARY_ROOT = "/opt/autoops/library";

    private ScriptLayout() {
    }

    /**
     * Writes {@code body} into {@code workspace} at the step's declared path
     * and provisions the library tree beside it.
     *
     * @param declaredPath the {@code scriptPath} core-service resolved from the
     *                     library item, e.g. {@code Scripts/AWS/Cert_Expiry.ps1}.
     *                     Null or unsafe input falls back to a flat file, which
     *                     is exactly how an ad-hoc (non-library) step behaves.
     * @return the file to execute
     */
    public static Path materialize(StepWorkspace workspace, JsonNode raw, String body,
                                   String suffix) throws IOException {
        String declaredPath = raw == null ? null : raw.path("scriptPath").asText(null);
        Path working = workspace.workingDirectory();
        Path relative = safeRelative(declaredPath);

        if (relative == null || working == null) {
            // No library provenance, or no workspace to build in. A flat file
            // is the correct answer for a hand-written step: it references no
            // module tree, so building one for it would be waste.
            Path flat = workspace.createFile("autoops-step-", suffix);
            Files.writeString(flat, body, StandardCharsets.UTF_8);
            return flat;
        }

        Path script = working.resolve(relative).normalize();
        // Belt and braces. safeRelative already rejected traversal; this
        // catches anything that survived normalization against a symlinked
        // working directory.
        if (!script.startsWith(working.normalize())) {
            throw new IOException("Refusing to write a step outside its workspace: "
                    + declaredPath);
        }
        Files.createDirectories(script.getParent());
        Files.writeString(script, body, StandardCharsets.UTF_8);
        provisionLibrary(working);
        // One handOver over the whole tree: a file created inside an
        // already-handed-over directory belongs to the service, and the step
        // user would get "permission denied" reading its own module.
        workspace.handOver(working);
        return script;
    }

    /**
     * Copies {@code Modules/} and {@code Config/} from the image into the
     * workspace. Missing on a dev box, and that is not fatal — a script that
     * needs the module will say so far more clearly than a startup check could.
     */
    private static void provisionLibrary(Path working) throws IOException {
        Path source = Path.of(System.getProperty(LIBRARY_ROOT_PROPERTY, DEFAULT_LIBRARY_ROOT));
        if (!Files.isDirectory(source)) {
            log.debug("No script library at {}; steps that import it will fail with "
                    + "PowerShell's own message.", source);
            return;
        }
        for (String directory : new String[]{"Modules", "Config"}) {
            Path from = source.resolve(directory);
            if (Files.isDirectory(from)) {
                copyTree(from, working.resolve(directory));
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * A relative path that cannot escape the workspace, or null when the input
     * is absent or refuses to be made safe.
     *
     * <p>Rejects rather than sanitizes. A {@code scriptPath} containing
     * {@code ..} is not a path this platform produced, and quietly stripping
     * the segments would turn an attempted escape into a silently different
     * destination — which is harder to notice than a refusal.
     */
    static Path safeRelative(String declared) {
        if (declared == null || declared.isBlank()) {
            return null;
        }
        String cleaned = declared.replace('\\', '/').trim();
        if (cleaned.startsWith("/") || cleaned.contains("..")
                || cleaned.matches("(?i)^[a-z]:.*")) {
            log.warn("Ignoring unsafe scriptPath '{}'", declared);
            return null;
        }
        try {
            Path path = Path.of(cleaned);
            return path.isAbsolute() || path.getNameCount() == 0 ? null : path;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Writes the launcher that actually runs a PowerShell step, and returns it.
     *
     * <h3>Why a separate file rather than a preamble</h3>
     *
     * The first version of this prepended its setup to the script body. That is
     * invalid PowerShell for exactly the scripts this exists to serve: a
     * {@code param()} block and its {@code [CmdletBinding()]} /
     * {@code [OutputType()]} attributes must be the FIRST statement in a file,
     * so inserting anything above them fails at PARSE time —
     * {@code Unexpected attribute 'OutputType'} — before a line runs. 119 of
     * the catalog's 213 scripts are param() scripts, so this was not an edge
     * case; it was most of the library.
     *
     * <p>The script is therefore written byte-for-byte as authored, and the
     * setup moves into a launcher that invokes it. That also keeps the run's
     * audit snapshot honest: the file on disk is the script the catalog holds,
     * not a variant this class synthesised.
     *
     * <h3>What the launcher does</h3>
     *
     * <ol>
     *   <li>{@code $ErrorActionPreference='Stop'} plus a trap, because pwsh
     *       exits 0 after a terminating error under {@code -File} and a failed
     *       automation would be recorded as a success.</li>
     *   <li>Puts the workspace's {@code Modules/} on {@code $PSModulePath}.</li>
     *   <li>Replaces {@code Import-Module} GLOBALLY with a wrapper that retries a
     *       failed PATH by the module's base name. This is what rescues the
     *       library's {@code '..\..\Modules\IT-Automation-Common.psm1'} on
     *       Linux, where a backslash is a filename character rather than a
     *       separator, so that path cannot resolve however the directories sit.
     *       A module that genuinely does not exist still throws — the fallback
     *       is a retry, not a swallow.</li>
     *   <li>Invokes the real script, splatting the step's arguments through.</li>
     * </ol>
     *
     * <p>{@code global:} on both the function and the root variable matters:
     * {@code &} runs the script in a child scope, and a plain function would be
     * visible there by dynamic scoping but a plain variable referenced from
     * inside the function would not reliably be.
     */
    public static Path launcher(StepWorkspace workspace, Path script) throws IOException {
        Path working = workspace.workingDirectory();
        Path launcher = working != null
                ? working.resolve("autoops-run.ps1")
                : workspace.createFile("autoops-run-", ".ps1");
        String body = """
                $ErrorActionPreference = 'Stop'
                trap { Write-Error $_; exit 1 }

                $global:__autoopsRoot = $PSScriptRoot
                $__modules = Join-Path $global:__autoopsRoot 'Modules'
                if (Test-Path $__modules) {
                    $env:PSModulePath = $__modules + [IO.Path]::PathSeparator + $env:PSModulePath
                }

                # A simple function (deliberately NOT an advanced one) so that
                # @args re-binds the caller's arguments exactly as written; an
                # advanced function would collect them into an array first and
                # turn `-Force` into a positional string.
                function global:Import-Module {
                    try {
                        Microsoft.PowerShell.Core\\Import-Module @args
                    } catch {
                        $__name = $null
                        foreach ($__a in $args) {
                            if ($__a -is [string] -and $__a -match '([^\\\\/]+)\\.psm1$') {
                                $__name = $Matches[1]
                                break
                            }
                        }
                        if (-not $__name -or -not $global:__autoopsRoot) { throw }
                        # Resolved by base name off the filesystem rather than via
                        # $PSModulePath: name lookup needs the
                        # <Modules>/<Name>/<Name>.psm1 convention, and the library
                        # ships its module as a flat file.
                        $__file = Get-ChildItem -Path (Join-Path $global:__autoopsRoot 'Modules') `
                            -Filter "$__name.psm1" -Recurse -File -ErrorAction SilentlyContinue |
                            Select-Object -First 1
                        if (-not $__file) { throw }
                        Microsoft.PowerShell.Core\\Import-Module -Name $__file.FullName `
                            -Force -ErrorAction Stop
                    }
                }

                & %SCRIPT% @args
                """.replace("%SCRIPT%", quote(script.toAbsolutePath().toString()));
        Files.writeString(launcher, body, StandardCharsets.UTF_8);
        return launcher;
    }

    /** A PowerShell single-quoted literal: only the quote itself needs doubling. */
    static String quote(String path) {
        return "'" + path.replace("'", "''") + "'";
    }

    /** {@code .ps1}, {@code .py}, {@code .sh} — by the step's type. */
    public static String suffixFor(String stepType) {
        return switch (stepType == null ? "" : stepType.toLowerCase(Locale.ROOT)) {
            case "powershell", "pwsh" -> ".ps1";
            case "pyscript", "python" -> ".py";
            default -> ".sh";
        };
    }
}

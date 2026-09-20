package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * The missing link between the script library and execution.
 *
 * <p>Before this existed, a library script could be browsed, imported, edited
 * and previewed — and then nothing. No job step could name one, so the only way
 * to run library content was to paste its body into a step by hand, at which
 * point the library row and the thing actually running were two unrelated
 * copies. The catalog held 213 scripts that the platform had no path to
 * execute.
 *
 * <p>A job step may now say:
 *
 * <pre>{@code {"type": "library", "libraryItemId": 42, "label": "Certificate expiry"}}</pre>
 *
 * and this class rewrites it, at the moment a run is queued, into the concrete
 * step the engine already knows how to run:
 *
 * <pre>{@code {"type": "powershell", "value": "<the script body>", "scriptPath": "…"}}</pre>
 *
 * <h2>Why the rewrite happens at queue time, not at execution time</h2>
 *
 * A run snapshots its definition — that snapshot is the audit record of what
 * ran. Resolving here puts the <em>exact body that executed</em> into the
 * snapshot, so editing the library script tomorrow cannot change the record of
 * what happened today, and a run in flight cannot have the script swapped out
 * underneath it. Resolving in {@code ExecutionEngine} instead would leave the
 * snapshot holding an id, and the answer to "what did run 8814 actually
 * execute?" would be "whatever that row says now".
 *
 * <h2>Why only the tenant's own rows</h2>
 *
 * The reference resolves against rows this workspace owns — never the platform
 * catalog directly, however the id was obtained. Two reasons, and the second is
 * the one that matters:
 *
 * <ul>
 *   <li>It keeps the existing flow honest: you import a catalog script, then
 *       use it. That is what {@code installs} counts and what the Inventory
 *       screen shows.</li>
 *   <li>Premium catalog items are gated behind PREMIUM_TEMPLATES at import
 *       ({@link LibraryService#clone}). A step that could name a catalog id
 *       directly would run premium content without ever passing that gate —
 *       a paywall bypass reachable by editing one number in a job definition.</li>
 * </ul>
 *
 * <p>An unresolvable reference <b>fails the run before anything executes</b>.
 * The alternative — dropping the step and carrying on — produces a job that
 * reports success having silently skipped the work it exists to do, which is
 * the worst outcome available here.
 */
@Service
public class LibraryStepResolver {

    private static final Logger log = LoggerFactory.getLogger(LibraryStepResolver.class);

    /** The step type that means "run the library script with this id". */
    public static final String STEP_TYPE = "library";

    private final LibraryItemRepository libraryRepository;
    private final ObjectMapper objectMapper;

    public LibraryStepResolver(LibraryItemRepository libraryRepository,
                               ObjectMapper objectMapper) {
        this.libraryRepository = libraryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Rewrites every library reference in a definition, returning the
     * definition unchanged when it holds none.
     *
     * <p>Unparseable input is returned untouched rather than rejected: this
     * runs on the queue path for every trigger, and a definition this cannot
     * read is one the engine will report on with far better context than a
     * resolver could.
     */
    @Transactional(readOnly = true)
    public String resolve(String tenantId, String definition) {
        if (definition == null || definition.isBlank()
                || !definition.contains("\"" + STEP_TYPE + "\"")) {
            // The string check is a cheap gate on the hot path: every run of
            // every job passes through here, and the overwhelming majority
            // reference no library script at all.
            return definition;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(definition);
        } catch (Exception ex) {
            return definition;
        }
        if (!(root instanceof ObjectNode object)) {
            return definition;
        }
        boolean changed = rewrite(tenantId, object.path("steps"))
                | rewrite(tenantId, object.path("nodes"));
        return changed ? object.toString() : definition;
    }

    /** @return true when at least one step in the array was rewritten. */
    private boolean rewrite(String tenantId, JsonNode steps) {
        if (!(steps instanceof ArrayNode array)) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) instanceof ObjectNode step && isLibraryStep(step)) {
                array.set(i, resolveStep(tenantId, step));
                changed = true;
            }
        }
        return changed;
    }

    /** {@code id} is accepted alongside {@code type}: the designer writes both. */
    private boolean isLibraryStep(ObjectNode step) {
        String type = step.path("type").asText(step.path("id").asText(""));
        return STEP_TYPE.equalsIgnoreCase(type);
    }

    private ObjectNode resolveStep(String tenantId, ObjectNode step) {
        String label = step.path("label").asText("library step");
        long id = step.path("libraryItemId").asLong(0);
        if (id <= 0) {
            throw CoreException.badRequest("library_step_unbound",
                    "Step \"" + label + "\" is a library step that names no script. "
                            + "Open the job and choose one.");
        }
        LibraryItem item = libraryRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.badRequest("library_item_not_found",
                        "Step \"" + label + "\" references script #" + id + ", which is not in "
                                + "this workspace. Import it from the library first, or pick "
                                + "another script."));
        if (item.getType() != LibraryItem.Type.SCRIPT) {
            throw CoreException.badRequest("library_item_not_script",
                    "Step \"" + label + "\" references \"" + item.getTitle() + "\", which is a "
                            + item.getType().name().toLowerCase(Locale.ROOT)
                            + " rather than a script. Only scripts run as job steps.");
        }
        LibraryScript.Resolved resolved = LibraryScript.resolve(item, objectMapper)
                .orElseThrow(() -> CoreException.badRequest("library_item_empty",
                        "Step \"" + label + "\" references \"" + item.getTitle()
                                + "\", which has no script body to run."));

        // Everything the author put on the step is kept — retries,
        // continueOnError, connection, nodeFilter, timeout. Only the fields
        // that describe WHAT runs are supplied by the library.
        ObjectNode out = step.deepCopy();
        out.put("type", resolved.stepType());
        out.remove("id");
        out.put("value", resolved.body());
        // Carried into the snapshot so a run's log and the audit trail can name
        // the script by id and title, not just replay an anonymous body.
        out.put("libraryItemId", item.getId());
        out.put("libraryTitle", item.getTitle());
        // The path the body is written to on the execution host. Catalog
        // PowerShell reaches for its shared module at
        // `$PSScriptRoot/../../Modules/…`, so a script dropped at the root of a
        // workspace cannot find it; this reproduces the two-deep layout those
        // scripts were authored against. See job-service's PowerShellRunner.
        out.put("scriptPath", scriptPath(item, resolved.stepType()));
        if (!out.hasNonNull("label") || out.path("label").asText().isBlank()) {
            out.put("label", item.getTitle());
        }
        log.debug("Resolved library step #{} ('{}') to a {} step", item.getId(),
                item.getTitle(), resolved.stepType());
        return out;
    }

    /**
     * {@code Scripts/<category>/<title>.<ext>} — the layout the library's
     * scripts were written for, rebuilt per step.
     */
    private String scriptPath(LibraryItem item, String stepType) {
        String extension = switch (stepType) {
            case "powershell", "pwsh" -> ".ps1";
            case "pyscript" -> ".py";
            default -> ".sh";
        };
        return "Scripts/" + safe(item.getCategory()) + "/" + safe(item.getTitle()) + extension;
    }

    /** Anything that is not a plain filename character becomes an underscore. */
    private String safe(String text) {
        if (text == null || text.isBlank()) {
            return "General";
        }
        String cleaned = text.trim().replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isBlank() ? "General"
                : cleaned.substring(0, Math.min(cleaned.length(), 80));
    }

    /**
     * The runnable form of one library item, for callers that want to preview
     * or validate a script without queueing a run.
     */
    @Transactional(readOnly = true)
    public Optional<LibraryScript.Resolved> preview(String tenantId, Long id) {
        return libraryRepository.findByIdAndTenantId(id, tenantId)
                .flatMap(item -> LibraryScript.resolve(item, objectMapper));
    }
}

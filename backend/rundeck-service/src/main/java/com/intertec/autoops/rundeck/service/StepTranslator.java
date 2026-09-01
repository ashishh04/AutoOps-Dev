package com.intertec.autoops.rundeck.service;

import com.intertec.autoops.rundeck.exception.RundeckException;
import com.intertec.autoops.rundeck.web.dto.StepExecutionRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an AutoOps step into a shell script the execution runtime can run.
 *
 * <p><strong>Everything becomes one bash script.</strong> Rundeck's ad-hoc API
 * offers a command endpoint too, and using both would be marginally tidier in
 * Rundeck's own UI — but a single code path is the difference between "every
 * step type injects credentials the same way" and "ten step types each get it
 * slightly wrong". Uniformity here is a safety property, not a style choice.
 *
 * <h2>How credentials get in, and what that costs</h2>
 * Credentials are emitted as {@code export} lines at the top of the generated
 * script. job-service passed them as process environment and never wrote them
 * anywhere; Rundeck's ad-hoc endpoint has no equivalent — secure options exist
 * only for saved jobs, and Key Storage would mean copying the vault into the
 * engine.
 *
 * <p>So this is a genuine, deliberate regression, mitigated rather than solved:
 * the script disables tracing before the exports, the values are single-quoted
 * with escaping, and Rundeck deletes the uploaded script after the run. It is
 * called out in the service README under Known gaps. Anyone tempted to run the
 * engine at {@code loglevel=DEBUG} should read that first.
 */
@Component
public class StepTranslator {

    /**
     * Heredoc terminator for embedded bodies (python, HCL, manifests). Quoted
     * at the open (<<'DELIM') so the shell performs NO expansion inside — a
     * script containing {@code $HOME} or backticks reaches the interpreter
     * exactly as the author wrote it.
     */
    private static final String HEREDOC = "AUTOOPS_STEP_EOF_9f3c1";

    /** The venv interpreter the runtime image installs (boto3, requests). */
    private static final String PYTHON = "autoops-python";

    /** What we hand Rundeck: a bash script plus the interpreter to run it with. */
    public record Script(String body, String interpreter, String fileExtension) {
    }

    /**
     * Step types whose body IS the work. An empty one is always a
     * misconfiguration, never a no-op.
     */
    private static final List<String> BODY_REQUIRED = List.of(
            "command", "agent", "script", "pyscript", "ssh", "rest",
            "terraform", "kubernetes", "awslambda", "lambda", "azurefn", "azurefunction");

    public Script translate(StepExecutionRequest request) {
        String type = request.stepType() == null
                ? "" : request.stepType().trim().toLowerCase(Locale.ROOT);
        String value = request.value() == null ? "" : request.value();

        // job-service's ScriptRunner refused an empty body outright, and that
        // guard has to survive the swap. Without it an unfilled step produces a
        // script of nothing, exits 0, and the run log says "ok" — a job that
        // silently does NOTHING while reporting success. That is strictly worse
        // than a failure, and a live run against real job definitions is exactly
        // where it was caught.
        if (BODY_REQUIRED.contains(type) && value.isBlank()) {
            throw RundeckException.badRequest("empty_step",
                    "Step has no content — edit the job and fill in the "
                            + ("script".equals(type) || "pyscript".equals(type)
                                    ? "script body" : type + " step"));
        }

        StringBuilder out = new StringBuilder();
        out.append("#!/bin/bash\n");
        // Before ANY credential is written. If tracing were on, every export
        // below would land in the execution log in clear.
        out.append("set +x\n");
        emitCredentials(out, request.credentials());
        out.append("\n");

        // NOTE: `set -euo pipefail` is deliberately NOT imposed on the script.
        //
        // It is the obvious hardening and it is wrong here. job-service ran a
        // step body as a plain bash script, so the automation library is written
        // against those semantics — and a great deal of it pipes into `head`,
        // `grep -q` or `tail`, all of which close the pipe early and hand the
        // upstream command a SIGPIPE (exit 141). Under `pipefail` that becomes a
        // FAILED STEP for a script that has behaved correctly for years.
        //
        // The step's exit status is the exit status of its last command, exactly
        // as before. Where THIS class generates a multi-command sequence, the
        // emitter turns on `set -e` for its own lines — see emitGenerated().

        switch (type) {
            case "command", "agent" -> out.append(value).append('\n');
            case "script" -> out.append(value).append('\n');
            case "pyscript" -> emitPython(out, value);
            case "ssh" -> { emitGenerated(out); emitSsh(out, value); }
            case "rest" -> { emitGenerated(out); emitRest(out, value); }
            case "terraform" -> { emitGenerated(out); emitTerraform(out, value, request.raw()); }
            case "kubernetes" -> { emitGenerated(out); emitKubernetes(out, value, request.credentials()); }
            case "awslambda", "lambda" -> { emitGenerated(out); emitLambda(out, value, request.raw()); }
            case "azurefn", "azurefunction" -> {
                emitGenerated(out);
                emitAzureFunction(out, value, request.credentials());
            }
            case "test" -> out.append("echo ").append(quote(value)).append('\n');
            case "powershell" ->
                // Refused rather than attempted. pwsh on Linux does not have the
                // Windows-only cmdlets the AutoOps script library is written
                // against, so "installed" would mean failing deep inside a
                // script instead of here, with a message nobody can act on.
                    throw RundeckException.badRequest("step_type_unsupported",
                            "PowerShell steps need a Windows execution node, which this "
                                    + "deployment does not have yet. Convert the step to a "
                                    + "script, or add a Windows node to the engine.");
            default -> throw RundeckException.badRequest("step_type_unsupported",
                    "Unknown step type \"" + request.stepType() + "\"");
        }
        return new Script(out.toString(), "/bin/bash", "sh");
    }

    /**
     * Turns on fail-fast for the lines THIS class generates.
     *
     * <p>A generated sequence is ours: `terraform init` must not be followed by
     * `terraform apply` when init failed, and a kubeconfig that could not be
     * written must not be followed by a kubectl that silently targets the
     * wrong cluster. That reasoning does not extend to a body the customer
     * wrote, which is why this is called per-type rather than in the preamble.
     */
    private void emitGenerated(StringBuilder out) {
        out.append("set -e\n");
    }

    // ---- per-type emitters -------------------------------------------------

    private void emitPython(StringBuilder out, String body) {
        // Written to a file and run, rather than piped to stdin: a traceback
        // then names real line numbers instead of "<stdin>", which is the
        // difference between a debuggable failure and a shrug.
        out.append("__step_py=\"$(mktemp -t autoops-step-XXXXXX.py)\"\n");
        out.append("trap 'rm -f \"$__step_py\"' EXIT\n");
        heredoc(out, "\"$__step_py\"", body);
        out.append(PYTHON).append(" \"$__step_py\"\n");
    }

    /**
     * {@code user@host command...} — the same value shape job-service parsed.
     *
     * <p>BatchMode means a host that would prompt for a password fails fast
     * instead of hanging until the step timeout. Key material is still the open
     * gap it was in job-service; see the README.
     */
    private void emitSsh(StringBuilder out, String value) {
        String trimmed = value.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            throw RundeckException.badRequest("invalid_step",
                    "An ssh step needs \"user@host <command>\"");
        }
        String destination = trimmed.substring(0, space);
        String remoteCommand = trimmed.substring(space + 1);
        out.append("ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new ")
                .append(quote(destination)).append(' ')
                .append(quote(remoteCommand)).append('\n');
    }

    /**
     * {@code [METHOD ]url} on line one, optional body on the lines after.
     *
     * <p>{@code --fail-with-body} is what makes an HTTP 4xx/5xx fail the STEP
     * while still printing what the server said. Plain {@code --fail} would
     * exit non-zero and swallow the response, which is the half of the answer
     * an operator actually needs.
     */
    private void emitRest(StringBuilder out, String value) {
        String[] lines = value.split("\\R", 2);
        String first = lines[0].trim();
        String body = lines.length > 1 ? lines[1] : null;

        String method = "GET";
        String url = first;
        int space = first.indexOf(' ');
        if (space > 0 && first.substring(0, space).matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD")) {
            method = first.substring(0, space).toUpperCase(Locale.ROOT);
            url = first.substring(space + 1).trim();
        } else if (body != null && !body.isBlank()) {
            method = "POST";
        }

        if (body != null && !body.isBlank()) {
            out.append("__step_body=\"$(mktemp -t autoops-body-XXXXXX)\"\n");
            out.append("trap 'rm -f \"$__step_body\"' EXIT\n");
            heredoc(out, "\"$__step_body\"", body);
            out.append("curl -sS --fail-with-body -X ").append(method)
                    .append(" -H 'Content-Type: application/json'")
                    .append(" --data-binary @\"$__step_body\" ")
                    .append(quote(url)).append('\n');
        } else {
            out.append("curl -sS --fail-with-body -X ").append(method).append(' ')
                    .append(quote(url)).append('\n');
        }
    }

    /**
     * HCL in, {@code init} + the requested action out.
     *
     * <p>{@code -input=false} everywhere: a provider that decides to prompt
     * would otherwise block until the step timeout with no output explaining
     * why. Same reason apply/destroy carry {@code -auto-approve} — there is no
     * terminal to approve at, and the human gate already happened in AutoOps'
     * own approval step.
     */
    private void emitTerraform(StringBuilder out, String hcl, Map<String, Object> raw) {
        String action = str(raw, "action", "apply").toLowerCase(Locale.ROOT);
        if (!List.of("init", "plan", "apply", "destroy").contains(action)) {
            throw RundeckException.badRequest("invalid_step",
                    "terraform action must be init, plan, apply or destroy");
        }
        out.append("__step_wd=\"$(mktemp -d -t autoops-tf-XXXXXX)\"\n");
        out.append("trap 'rm -rf \"$__step_wd\"' EXIT\n");
        heredoc(out, "\"$__step_wd/main.tf\"", hcl);
        out.append("cd \"$__step_wd\"\n");
        out.append("terraform init -input=false -no-color\n");
        switch (action) {
            case "init" -> { /* init already ran; nothing further */ }
            case "plan" -> out.append("terraform plan -input=false -no-color\n");
            case "destroy" -> out.append("terraform destroy -auto-approve -input=false -no-color\n");
            default -> out.append("terraform apply -auto-approve -input=false -no-color\n");
        }
    }

    /**
     * {@code kubectl <args>}, or {@code apply} with a manifest on the following
     * lines (piped in on stdin, so no file leaves the step's temp dir).
     *
     * <p>The kubeconfig is written 0600 into a private temp file and removed on
     * exit — the same handling job-service gave it.
     */
    private void emitKubernetes(StringBuilder out, String value, Map<String, String> credentials) {
        String kubeconfig = credentials == null ? null : credentials.get("kubeconfig");
        if (kubeconfig != null && !kubeconfig.isBlank()) {
            out.append("__step_kube=\"$(mktemp -t autoops-kube-XXXXXX)\"\n");
            out.append("chmod 600 \"$__step_kube\"\n");
            out.append("trap 'rm -f \"$__step_kube\"' EXIT\n");
            heredoc(out, "\"$__step_kube\"", kubeconfig);
            out.append("export KUBECONFIG=\"$__step_kube\"\n");
        }

        String[] lines = value.split("\\R", 2);
        String args = lines[0].trim();
        String manifest = lines.length > 1 ? lines[1] : null;

        if (manifest != null && !manifest.isBlank()) {
            if (manifest.contains(HEREDOC)) {
                throw RundeckException.badRequest("invalid_step",
                        "Manifest contains the internal delimiter " + HEREDOC
                                + " — remove it; it would truncate the manifest.");
            }
            // Manifest on stdin: kubectl reads `-f -`, nothing is left behind.
            out.append("kubectl ").append(args).append(" -f - <<'").append(HEREDOC).append("'\n");
            out.append(manifest);
            if (!manifest.endsWith("\n")) {
                out.append('\n');
            }
            out.append(HEREDOC).append('\n');
        } else {
            out.append("kubectl ").append(args).append('\n');
        }
    }

    /**
     * {@code aws lambda invoke}. Rundeck's native Lambda step is Enterprise-only,
     * so the CLI in the runtime image is what backs this.
     *
     * <p>The response payload is printed because that IS the step's output —
     * `invoke` writes it to a file and says nothing useful on stdout, so a step
     * that skipped the `cat` would succeed silently and tell the operator
     * nothing.
     */
    private void emitLambda(StringBuilder out, String value, Map<String, Object> raw) {
        String[] lines = value.split("\\R", 2);
        String function = lines[0].trim();
        String payload = lines.length > 1 ? lines[1] : null;
        if (function.isEmpty()) {
            throw RundeckException.badRequest("invalid_step",
                    "An awslambda step needs a function name or ARN on the first line");
        }

        out.append("__step_out=\"$(mktemp -t autoops-lambda-XXXXXX.json)\"\n");
        // The CLI's own response envelope ({StatusCode, ExecutedVersion,
        // FunctionError}) is captured rather than printed. An operator reading
        // a run log wants the FUNCTION's answer, not the transport's receipt —
        // but the envelope cannot simply be discarded, because FunctionError is
        // the only signal that the function ran and threw. `aws lambda invoke`
        // exits 0 for a handled function error: the invocation succeeded, the
        // code failed. Without the check below that is a GREEN step for a
        // Lambda that raised.
        out.append("__step_meta=\"$(mktemp -t autoops-lambda-meta-XXXXXX.json)\"\n");
        out.append("trap 'rm -f \"$__step_out\" \"$__step_meta\"' EXIT\n");
        out.append("aws lambda invoke --function-name ").append(quote(function));

        String region = str(raw, "region", null);
        if (region != null) {
            out.append(" --region ").append(quote(region));
        }
        String qualifier = str(raw, "qualifier", null);
        if (qualifier != null) {
            out.append(" --qualifier ").append(quote(qualifier));
        }
        String invocationType = str(raw, "invocationType", null);
        if (invocationType != null) {
            out.append(" --invocation-type ").append(quote(invocationType));
        }
        String endpoint = str(raw, "endpoint", null);
        if (endpoint != null) {
            out.append(" --endpoint-url ").append(quote(endpoint));
        }
        if (payload != null && !payload.isBlank()) {
            out.append(" --cli-binary-format raw-in-base64-out --payload ")
                    .append(quote(payload.trim()));
        }
        out.append(" \"$__step_out\" > \"$__step_meta\"\n");
        out.append("cat \"$__step_out\"\n");
        // Printed AFTER the payload: the payload is the error detail, and an
        // operator reads top-down.
        out.append("if grep -q '\"FunctionError\"' \"$__step_meta\"; then\n");
        out.append("    echo \"\" >&2\n");
        out.append("    echo \"The function was invoked successfully and then FAILED "
                + "(FunctionError) — its error response is above.\" >&2\n");
        out.append("    exit 1\n");
        out.append("fi\n");
    }

    /** HTTP-trigger call; the function key rides as the documented header. */
    private void emitAzureFunction(StringBuilder out, String value,
                                   Map<String, String> credentials) {
        String[] lines = value.split("\\R", 2);
        String first = lines[0].trim();
        String body = lines.length > 1 ? lines[1] : null;

        String method = body != null && !body.isBlank() ? "POST" : "GET";
        String url = first;
        int space = first.indexOf(' ');
        if (space > 0 && first.substring(0, space).matches("(?i)GET|POST|PUT|PATCH|DELETE")) {
            method = first.substring(0, space).toUpperCase(Locale.ROOT);
            url = first.substring(space + 1).trim();
        }

        boolean hasBody = body != null && !body.isBlank();
        // The body file is written BEFORE the curl line, in order. Building the
        // curl line first and splicing the setup in afterwards worked until a
        // step happened to contain the string "curl -sS" in its own payload.
        if (hasBody) {
            out.append("__step_body=\"$(mktemp -t autoops-body-XXXXXX)\"\n");
            out.append("trap 'rm -f \"$__step_body\"' EXIT\n");
            heredoc(out, "\"$__step_body\"", body);
        }

        String functionKey = credentials == null ? null : credentials.get("functionKey");
        out.append("curl -sS --fail-with-body -X ").append(method);
        if (functionKey != null && !functionKey.isBlank()) {
            // Header rather than ?code= so the key is not in the URL, which is
            // the part Rundeck records and logs.
            out.append(" -H \"x-functions-key: $AZURE_FUNCTION_KEY\"");
        }
        if (hasBody) {
            out.append(" -H 'Content-Type: application/json'")
                    .append(" --data-binary @\"$__step_body\"");
        }
        out.append(' ').append(quote(url)).append('\n');
    }

    // ---- credential handling ----------------------------------------------

    /**
     * Maps the decrypted credential bundle onto the environment variable names
     * each toolchain already reads, so a step author writes ordinary
     * terraform/boto3/kubectl and it simply works — exactly as it did under
     * job-service.
     */
    private void emitCredentials(StringBuilder out, Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return;
        }
        export(out, "AWS_ACCESS_KEY_ID", first(credentials, "accessId", "accessKey", "accessKeyId"));
        export(out, "AWS_SECRET_ACCESS_KEY",
                first(credentials, "secret", "secretKey", "secretAccessKey"));
        // Some integrations are role-assumed and carry a session token. Omitting
        // it turns a valid temporary credential into an opaque
        // "InvalidClientTokenId" several frames from the cause.
        export(out, "AWS_SESSION_TOKEN", first(credentials, "sessionToken", "token"));
        export(out, "AWS_DEFAULT_REGION", first(credentials, "region"));
        export(out, "AWS_REGION", first(credentials, "region"));

        String clientId = first(credentials, "clientId");
        String clientSecret = first(credentials, "clientSecret");
        String tenantId = first(credentials, "tenantId");
        String subscriptionId = first(credentials, "subscriptionId");

        export(out, "ARM_CLIENT_ID", clientId);
        export(out, "ARM_CLIENT_SECRET", clientSecret);
        export(out, "ARM_TENANT_ID", tenantId);
        export(out, "ARM_SUBSCRIPTION_ID", subscriptionId);

        // What the Azure SDKs and Connect-AzAccount actually read. Terraform
        // takes the ARM_ names; everything else takes these.
        export(out, "AZURE_CLIENT_ID", clientId);
        export(out, "AZURE_CLIENT_SECRET", clientSecret);
        export(out, "AZURE_TENANT_ID", tenantId);
        export(out, "AZURE_SUBSCRIPTION_ID", subscriptionId);
        export(out, "AZURE_FUNCTION_KEY", first(credentials, "functionKey"));

        export(out, "GOOGLE_CREDENTIALS",
                first(credentials, "serviceAccount", "serviceAccountJson"));
        export(out, "GOOGLE_PROJECT", first(credentials, "projectId"));
    }

    /**
     * The first of several accepted spellings that is actually present.
     *
     * <p>Not cosmetic. The console stores an AWS key under {@code accessId} and
     * its secret under {@code secretKey} (Integrations.jsx), while other
     * integrations and imported definitions use {@code accessKeyId} /
     * {@code secretAccessKey}. job-service accepted every spelling; reading only
     * one here meant the export was silently skipped and the step failed deep
     * inside the toolchain with "Unable to locate credentials" — a message that
     * names neither the step nor the integration. Kept in step with
     * {@code CloudCredentialEnv} in job-service, which is the other half of this
     * mapping for as long as both executors exist.
     */
    private String first(Map<String, String> credentials, String... keys) {
        for (String key : keys) {
            String value = credentials.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void export(StringBuilder out, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.append("export ").append(name).append('=').append(quote(value)).append('\n');
    }

    // ---- shell helpers -----------------------------------------------------

    /**
     * Single-quotes a value for bash, the only form with no expansion at all
     * inside it. An embedded quote is closed, escaped and reopened — the
     * standard {@code '\''} dance — so a password containing a quote cannot
     * terminate the literal and turn the rest of itself into commands.
     */
    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Writes {@code body} to {@code targetExpr} via a quoted heredoc.
     *
     * <p>Refuses a body that contains the terminator rather than emitting a
     * script that silently truncates there. That is a correctness cliff: the
     * step would run a fragment of the author's code and report success.
     */
    private void heredoc(StringBuilder out, String targetExpr, String body) {
        if (body != null && body.contains(HEREDOC)) {
            throw RundeckException.badRequest("invalid_step",
                    "Step content contains the internal delimiter " + HEREDOC
                            + " — remove it; it would truncate the script.");
        }
        out.append("cat > ").append(targetExpr).append(" <<'").append(HEREDOC).append("'\n");
        if (body != null) {
            out.append(body);
            if (!body.isEmpty() && !body.endsWith("\n")) {
                out.append('\n');
            }
        }
        out.append(HEREDOC).append('\n');
    }

    private static String str(Map<String, Object> raw, String key, String fallback) {
        if (raw == null) {
            return fallback;
        }
        Object value = raw.get(key);
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value);
    }
}

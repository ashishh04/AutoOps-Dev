# Authoring a workflow

The JSON under `workflows/<DOMAIN>/` is the published artefact — `publish.py`
reads it and nothing else. It is **generated** from the two halves here:

```
_authoring/
  bodies/<name>.py      the automation itself, as a real Python file
  generate.py           bodies + declared contract -> workflows/<DOMAIN>/*.json
  validate_bodies.py    the checks that must pass before one can publish
```

## Why the body is a file and not a JSON string

A 150-line boto3 script embedded in `"value"` as one line with `\n` escapes
cannot be read in a diff, cannot be linted, and cannot be syntax-checked before
it runs against a customer's account. As a `.py` file it is all three, and it is
exercised against a stubbed AWS in
`backend/agent-runtime/tests/test_workflow_bodies.py`.

The cost is a generated file, and the usual risk with a generated file is that
someone edits the output. `generate.py --check` re-renders in memory and fails if
the committed JSON differs, and that check runs in `pytest` and in CI. So an edit
to either half without the other is a build failure rather than a surprise during
an incident.

## Adding one

1. Write `bodies/<name>.py`. Read inputs as `"{{VariableName}}"` at the top and
   **re-validate every one of them** before using it.
2. Add an entry to `WORKFLOWS_TO_BUILD` in `generate.py` declaring the inputs,
   the `requires`, and the file it renders to.
3. Run `python generate.py`, then `python validate_bodies.py`.
4. Run the schema validator, which covers the agents tree too:
   `python ../../agents/_schema/validate.py`
5. `pytest` in `backend/agent-runtime`.

## The `pattern` on a string input is a security control

`NativeInputValidator` enforces the declared `pattern` on the path every run
takes, and a value that passes is then substituted **verbatim** into the script
body. The substitution does no escaping of its own, deliberately — a second,
weaker guard would only obscure which one was doing the work. The pattern is
therefore what stands between a tool argument and arbitrary code execution inside
the automation, and `validate_bodies.py` refuses a string input without one.

The body re-checks the value anyway. That is defence in depth, not redundancy: a
script that runs `boto3` should not depend on something upstream having been
configured correctly in order to avoid executing whatever it was handed.

## Things that will bite

- **Never write a literal `{{` that is not a placeholder.** The engine's regex is
  `{{Identifier}}`; anything else is left unsubstituted and reaches the
  interpreter as text. `validate_bodies.py` catches it.
- **`retries` is 0 on every one of these.** A read-only audit gains nothing from
  a retry that boto3 already does internally, and a destructive one must never be
  retried automatically.
- **Distinguish "not configured" from "we were denied".** Every audit here does,
  and it is the single most important thing in them: an audit reporting
  "not encrypted" when it was merely refused the read sends someone to fix a
  bucket that was fine, and implies coverage it never had.
- **A `select` input's options become an `enum` in the model's tool schema.**
  They are a contract; anything else fails inside the engine with a message the
  model cannot act on.

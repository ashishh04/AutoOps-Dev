"""A stand-in for AWS, so an automation body can be tested without an account.

These bodies are the only code in this repository that runs against a
customer's production cloud, and one of them deletes disks. "It parses" is not
an adequate gate for that, so the real logic — which bucket counts as public,
which open port has something behind it, whether a re-attached volume is still
a deletion candidate — is exercised here against scripted API responses.

**Why a hand-written stub rather than moto or botocore's Stubber.** Neither
boto3 nor botocore is a dependency of this service: the bodies run on the
execution host, inside job-service's image, and adding the AWS SDK here to test
them would be a heavy dependency acquired for a test. The bodies use a small,
flat slice of the API — a dozen calls, all of them Describe/Get plus three
writes — and a stub that covers exactly that slice is smaller than the shim
either library would need anyway.

The stub deliberately raises the REAL shapes: ``ClientError`` carries a
``response`` dict with an error code, because every body branches on that code
to tell "this feature is not configured" from "we were not allowed to look",
and a stub that collapsed the two would hide the bug that distinction exists
to prevent.
"""

from __future__ import annotations

import io
import pathlib
import re
import sys
import types
from contextlib import redirect_stdout
from dataclasses import dataclass, field
from typing import Any, Callable

#: tests/ -> agent-runtime/ -> backend/, where agent-service sits beside us.
WORKFLOWS = (
    pathlib.Path(__file__).resolve().parents[2] / "agent-service" / "workflows"
)
PLACEHOLDER = re.compile(r"\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}")


# ------------------------------------------------------------- exceptions ---


class ClientError(Exception):
    """botocore's, in the shape the bodies actually read."""

    def __init__(self, code: str, message: str = "denied", operation: str = "Op"):
        super().__init__(f"An error occurred ({code}): {message}")
        self.response = {"Error": {"Code": code, "Message": message}}
        self.operation_name = operation


class NoCredentialsError(Exception):
    """Raised when nothing put AWS credentials in the environment."""


# ------------------------------------------------------------------ stubs ---


@dataclass
class Paginator:
    pages: list[dict]

    def paginate(self, **kwargs):
        return list(self.pages)


@dataclass
class FakeClient:
    """One AWS service client. Every call is looked up by name in ``responses``.

    A response may be a value (returned), an exception instance (raised), or a
    callable (invoked with the kwargs). A call the test did not script raises
    ``AssertionError`` rather than returning an empty dict — an unscripted call
    means the body did something the test did not expect, and that IS the
    finding.
    """

    service: str
    responses: dict[str, Any] = field(default_factory=dict)
    #: Every call made, in order, as (operation, kwargs). Assertions about what
    #: a destructive body DID NOT do are read from here.
    calls: list[tuple[str, dict]] = field(default_factory=list)

    def __getattr__(self, name: str) -> Callable:
        if name.startswith("_"):
            raise AttributeError(name)

        def call(**kwargs):
            self.calls.append((name, kwargs))
            if name not in self.responses:
                raise AssertionError(
                    f"{self.service}.{name}() was called but the test scripted no response. "
                    f"Calls so far: {[c[0] for c in self.calls]}"
                )
            answer = self.responses[name]
            if isinstance(answer, list) and answer and not isinstance(answer[0], (str, int)):
                # A queue: successive calls get successive answers, which is how
                # a poll-until-complete loop is exercised.
                answer = answer.pop(0) if len(answer) > 1 else answer[0]
            if isinstance(answer, BaseException):
                raise answer
            if callable(answer):
                return answer(**kwargs)
            return answer

        return call

    def get_paginator(self, operation: str) -> Paginator:
        self.calls.append((f"paginate:{operation}", {}))
        pages = self.responses.get(f"paginate:{operation}")
        if pages is None:
            raise AssertionError(
                f"{self.service} paginator for {operation!r} was requested but not scripted."
            )
        if isinstance(pages, BaseException):
            raise pages
        return Paginator(pages=pages)

    def operations(self) -> list[str]:
        return [name for name, _ in self.calls]

    def kwargs_for(self, operation: str) -> list[dict]:
        return [args for name, args in self.calls if name == operation]


class FakeBoto3(types.ModuleType):
    """The ``boto3`` the body imports."""

    def __init__(self, clients: dict[str, FakeClient]):
        super().__init__("boto3")
        self._clients = clients

    def client(self, service: str, **kwargs):
        if service not in self._clients:
            raise AssertionError(f"the body asked for an unscripted {service!r} client")
        client = self._clients[service]
        client.calls.append(("client", kwargs))
        return client


# -------------------------------------------------------------- the runner ---


@dataclass
class Result:
    stdout: str
    exit_message: str | None
    clients: dict[str, FakeClient]

    def line(self, prefix: str) -> str:
        """The first output line starting with ``prefix``, for assertions."""
        for line in self.stdout.splitlines():
            if line.startswith(prefix):
                return line
        raise AssertionError(
            f"no output line starts with {prefix!r}.\n--- stdout ---\n{self.stdout}"
        )

    def has(self, fragment: str) -> bool:
        return fragment in self.stdout


def body_of(workflow: str, node: int = 0) -> str:
    """The script a workflow's node would run, straight out of the published JSON.

    Read from the JSON rather than from ``_authoring/bodies/`` on purpose: the
    JSON is what publishes, and a test that read the source file would pass
    while the thing that actually ships was stale.
    """
    import json

    document = json.loads((WORKFLOWS / workflow).read_text(encoding="utf-8"))
    return document["nodes"][node]["value"]


def substitute(script: str, values: dict[str, Any]) -> str:
    """What ExecutionEngine does to a step before it runs: textual, no escaping."""
    missing = set(PLACEHOLDER.findall(script)) - set(values)
    if missing:
        raise AssertionError(f"the test supplied no value for {sorted(missing)}")
    return PLACEHOLDER.sub(lambda m: str(values[m.group(1)]), script)


def run_body(workflow: str, values: dict[str, Any], clients: dict[str, FakeClient]) -> Result:
    """Executes one automation body against stubbed AWS and captures its output.

    ``sys.exit`` is caught rather than allowed to propagate: a body exits to
    fail a step, and the message it exits WITH is the thing an operator reads,
    so it is a result to assert on and not an error for the harness.
    """
    script = substitute(body_of(workflow), values)

    botocore = types.ModuleType("botocore")
    config_module = types.ModuleType("botocore.config")
    config_module.Config = lambda **kwargs: kwargs
    exceptions_module = types.ModuleType("botocore.exceptions")
    exceptions_module.ClientError = ClientError
    exceptions_module.NoCredentialsError = NoCredentialsError

    injected = {
        "boto3": FakeBoto3(clients),
        "botocore": botocore,
        "botocore.config": config_module,
        "botocore.exceptions": exceptions_module,
    }
    saved = {name: sys.modules.get(name) for name in injected}
    sys.modules.update(injected)

    buffer = io.StringIO()
    exit_message: str | None = None
    try:
        with redirect_stdout(buffer):
            exec(compile(script, workflow, "exec"), {"__name__": "__main__"})
    except SystemExit as exc:
        exit_message = None if exc.code in (0, None) else str(exc.code)
    finally:
        for name, module in saved.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    return Result(stdout=buffer.getvalue(), exit_message=exit_message, clients=clients)


# ------------------------------------------------------------- the clock ---


def freeze_clock(monkeypatch, start: float = 1_000_000.0) -> Callable[[], float]:
    """Makes ``time.sleep`` advance ``time.time`` instead of really waiting.

    Patching only ``sleep`` is the obvious move and it is wrong: the reclaim
    body polls ``while time.time() < deadline``, so a no-op sleep turns a
    five-minute snapshot wait into a five-minute BUSY LOOP in the test suite.
    Both have to move together, which is what this does.

    Returns the reader, so a test can assert how much simulated time a wait
    actually consumed.
    """
    now = [start]

    def sleep(seconds: float) -> None:
        now[0] += seconds

    monkeypatch.setattr("time.sleep", sleep)
    monkeypatch.setattr("time.time", lambda: now[0])
    return lambda: now[0]

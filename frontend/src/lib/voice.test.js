import { describe, expect, it, vi } from "vitest";
import { fetchSequence, response } from "../test/setup";
import { createVoiceSession, fetchVoiceConfig, requestMicrophone, VoiceError } from "./voice";

/** Installs a fake getUserMedia; jsdom has no mediaDevices at all. */
function stubMediaDevices(impl) {
  Object.defineProperty(navigator, "mediaDevices", {
    value: impl === undefined ? undefined : { getUserMedia: impl },
    configurable: true,
    writable: true,
  });
}

describe("fetchVoiceConfig", () => {
  it("reports whether this deployment has voice credentials", async () => {
    const fetchMock = fetchSequence(response(200, { enabled: true, agentName: "Aegis-01" }));

    await expect(fetchVoiceConfig()).resolves.toEqual({ enabled: true, agentName: "Aegis-01" });
    expect(fetchMock).toHaveBeenCalledWith("/api/voice/config");
  });

  it("raises a VoiceError when the backend is unreachable", async () => {
    fetchSequence(response(503, { message: "The voice agent is not configured" }));

    await expect(fetchVoiceConfig()).rejects.toBeInstanceOf(VoiceError);
  });
});

describe("createVoiceSession", () => {
  it("POSTs and returns the signed url", async () => {
    const fetchMock = fetchSequence(
      response(200, { signedUrl: "wss://api.elevenlabs.io/x?token=abc", expiresInSeconds: 900 }),
    );

    const session = await createVoiceSession();

    expect(session.signedUrl).toBe("wss://api.elevenlabs.io/x?token=abc");
    expect(fetchMock).toHaveBeenCalledWith("/api/voice/session", { method: "POST" });
  });

  it("surfaces the server's rate-limit wording verbatim", async () => {
    fetchSequence(
      response(429, { error: "rate_limited", message: "Aegis-01 is at capacity right now" }),
    );

    await expect(createVoiceSession()).rejects.toMatchObject({
      message: "Aegis-01 is at capacity right now",
      status: 429,
    });
  });

  it("keeps the Retry-After the server sent", async () => {
    // Without it the caller can only say "shortly", which is the same sentence
    // for a ten-second pause and a ten-minute lockout.
    fetchSequence(
      response(429, { message: "Please try again shortly" }, { "Retry-After": "600" }),
    );

    await expect(createVoiceSession()).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 600,
    });
  });

  it("leaves the wait undefined when the server did not name one", async () => {
    fetchSequence(response(503, { message: "The voice agent is not available right now" }));

    await expect(createVoiceSession()).rejects.toMatchObject({
      status: 503,
      retryAfterSeconds: undefined,
    });
  });

  it("still fails cleanly when the error body is not JSON", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error("not json");
      },
    }));

    await expect(createVoiceSession()).rejects.toMatchObject({
      message: "The voice agent is not available right now",
      status: 502,
    });
  });
});

describe("requestMicrophone", () => {
  it("resolves and releases the consent stream", async () => {
    const track = { stop: vi.fn() };
    stubMediaDevices(vi.fn(async () => ({ getTracks: () => [track] })));

    await expect(requestMicrophone()).resolves.toBeUndefined();
    // The SDK opens its own capture stream; leaving ours live would keep the
    // browser's recording indicator on for the whole visit.
    expect(track.stop).toHaveBeenCalled();
  });

  it("explains a denied permission in one sentence", async () => {
    stubMediaDevices(
      vi.fn(async () => {
        throw Object.assign(new Error("denied"), { name: "NotAllowedError" });
      }),
    );

    await expect(requestMicrophone()).rejects.toMatchObject({
      message: "Microphone access is needed to talk with Aegis-01.",
    });
  });

  it("names a missing microphone rather than blaming permissions", async () => {
    stubMediaDevices(
      vi.fn(async () => {
        throw Object.assign(new Error("none"), { name: "NotFoundError" });
      }),
    );

    await expect(requestMicrophone()).rejects.toMatchObject({
      message: "No microphone was found on this device.",
    });
  });

  it("points at the missing https when getUserMedia does not exist", async () => {
    // Exactly what a visitor on plain http:// sees.
    stubMediaDevices(undefined);

    await expect(requestMicrophone()).rejects.toMatchObject({
      message: expect.stringContaining("secure https connection"),
    });
  });

  it("does not leak an unexpected browser error to the visitor", async () => {
    stubMediaDevices(
      vi.fn(async () => {
        throw new Error("AbortError: internal 0x8000ffff");
      }),
    );

    await expect(requestMicrophone()).rejects.toMatchObject({
      message: "Could not start the microphone.",
    });
  });
});

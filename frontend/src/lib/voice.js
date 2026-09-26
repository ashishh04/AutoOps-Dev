// Landing-page voice agent (Aegis-01) transport.
//
// Deliberately separate from lib/api.js: these two endpoints are anonymous —
// a visitor has no token yet — and they must never pick up the offline mock
// layer that api.js applies to non-/auth paths.
//
// The browser never sees the ElevenLabs API key. voice-agent (behind the
// gateway) holds it and hands back a signed WebSocket URL that is scoped to a
// single conversation and expires in 15 minutes.
const API_BASE = import.meta.env.VITE_API_URL || "/api";

export class VoiceError extends Error {
  constructor(message, status, retryAfterSeconds) {
    super(message);
    this.name = "VoiceError";
    this.status = status;
    /** Seconds until this is worth attempting again; only a 429 carries one. */
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * The server sends Retry-After alongside a 429, and it is the only part of that
 * response that tells a visitor whether to wait ten seconds or ten minutes.
 * Dropping it leaves "try again shortly", which invites the immediate retry
 * that is guaranteed to fail.
 */
function retryAfter(res) {
  const header = res.headers?.get?.("Retry-After");
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
}

async function readError(res, fallback) {
  const wait = retryAfter(res);
  try {
    const data = await res.json();
    return new VoiceError(data?.message || data?.error || fallback, res.status, wait);
  } catch {
    return new VoiceError(fallback, res.status, wait);
  }
}

/**
 * Whether this deployment has ElevenLabs credentials at all. A landing page
 * with no key should show no talk button rather than a dead one.
 * @returns {Promise<{enabled: boolean, agentName: string}>}
 */
export async function fetchVoiceConfig() {
  const res = await fetch(`${API_BASE}/voice/config`);
  if (!res.ok) {
    throw await readError(res, "Could not reach the voice agent");
  }
  return res.json();
}

/**
 * Mints one conversation. Rate-limited server-side, so a 429 here is expected
 * traffic shaping, not a bug — its message is already visitor-facing.
 * @returns {Promise<{signedUrl: string, expiresInSeconds: number}>}
 */
export async function createVoiceSession() {
  const res = await fetch(`${API_BASE}/voice/session`, { method: "POST" });
  if (!res.ok) {
    throw await readError(res, "The voice agent is not available right now");
  }
  return res.json();
}

/**
 * Asks for the microphone before connecting, so a denied permission surfaces
 * as one clear sentence instead of a silent, connected-but-mute session.
 */
export async function requestMicrophone() {
  if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
    // getUserMedia only exists in a secure context — http:// on anything other
    // than localhost lands here, which is worth saying out loud.
    throw new VoiceError(
      "Your browser cannot use the microphone on this page (it needs a secure https connection).",
    );
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // The SDK opens its own capture stream; this one only proves consent.
    stream.getTracks().forEach((track) => track.stop());
  } catch (e) {
    if (e?.name === "NotAllowedError" || e?.name === "SecurityError") {
      throw new VoiceError("Microphone access is needed to talk with Aegis-01.");
    }
    if (e?.name === "NotFoundError" || e?.name === "OverconstrainedError") {
      throw new VoiceError("No microphone was found on this device.");
    }
    throw new VoiceError("Could not start the microphone.");
  }
}

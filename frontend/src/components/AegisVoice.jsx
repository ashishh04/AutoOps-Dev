import React, { useCallback, useEffect, useRef, useState } from "react";
import { ConversationProvider, useConversation } from "@elevenlabs/react";
import { createVoiceSession, fetchVoiceConfig, requestMicrophone } from "../lib/voice";

/**
 * Aegis-01 — the live voice agent on the landing page.
 *
 * Tap the pill, grant the microphone, and the browser opens a WebSocket
 * straight to ElevenLabs using a signed URL minted by voice-agent. The API key
 * stays on the server; this component only ever handles a 15-minute URL.
 *
 * The pill always renders, so the landing page looks the same in every
 * environment. /api/voice/config only decides whether it can actually dial: an
 * un-keyed deployment leaves it inert and says so when tapped, rather than
 * spending a round trip on a call that could only fail.
 */
export default function AegisVoice() {
  const [config, setConfig] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetchVoiceConfig()
      .then((c) => {
        if (!cancelled) setConfig(c);
      })
      .catch(() => {
        // Backend down or not deployed: the pill stays up but inert.
        if (!cancelled) setConfig({ enabled: false });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <ConversationProvider>
      <VoicePill
        agentName={config?.agentName || "Aegis-01"}
        // Optimistic while /config is still in flight, so an early tap takes
        // the normal path and gets the server's own answer.
        configured={config?.enabled ?? true}
      />
    </ConversationProvider>
  );
}

/** "9:58" for a long wait, "45s" for a short one. */
function waitLabel(seconds) {
  if (seconds >= 60) {
    const minutes = Math.floor(seconds / 60);
    return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
  }
  return `${seconds}s`;
}

function VoicePill({ agentName, configured }) {
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState("");
  /**
   * Seconds left on a refusal the server made on purpose, or null.
   *
   * Kept apart from `error` because the two ask for opposite things. An error
   * is worth another tap; a 429 is not — retrying inside the window cannot
   * succeed, so painting it red under "Tap to try again" invites exactly the
   * behaviour that cannot work, and a deliberate limit reads as a broken
   * product. The server's wording goes to `notice` and outlives the countdown;
   * this holds only the clock.
   */
  const [cooldown, setCooldown] = useState(null);
  const [caption, setCaption] = useState("");
  // Tracks the click→connected gap: startSession() is fire-and-forget, so the
  // pill would otherwise sit on "Tap to speak" while the socket opens.
  const [starting, setStarting] = useState(false);
  const startingRef = useRef(false);
  const captionRef = useRef(null);
  const [captionOverflows, setCaptionOverflows] = useState(false);

  const conversation = useConversation({
    onConnect: () => {
      startingRef.current = false;
      setStarting(false);
      setError(null);
    },
    onDisconnect: () => {
      startingRef.current = false;
      setStarting(false);
      setCaption("");
    },
    onMessage: ({ message, source, role }) => {
      // Show only what the agent says — echoing the visitor's own words back
      // at them reads as a transcription demo, not a conversation.
      if ((role || source) === "agent" || source === "ai") setCaption(message);
    },
    onError: (message) => {
      startingRef.current = false;
      setStarting(false);
      setError(typeof message === "string" ? message : "Aegis-01 dropped the call.");
    },
  });

  const { status, isSpeaking, startSession, endSession } = conversation;
  const connected = status === "connected";

  const toggle = useCallback(async () => {
    if (connected) {
      endSession();
      return;
    }
    // No credentials here. Answering locally keeps the pill honest without
    // sending voice-agent off to ElevenLabs with a key it does not have.
    if (!configured) {
      setNotice(`${agentName} is not connected in this environment yet.`);
      return;
    }
    // Double-tap while the socket is opening would mint a second session and
    // burn a second slice of the rate limit.
    if (startingRef.current) return;
    // Inside a server-declared cooldown the request is refused before it
    // reaches ElevenLabs, so sending it only confirms what we were already
    // told. The countdown on the pill is the answer.
    if (cooldown) return;

    startingRef.current = true;
    setStarting(true);
    setError(null);
    setNotice("");
    setCaption("");
    try {
      await requestMicrophone();
      const { signedUrl } = await createVoiceSession();
      startSession({ signedUrl });
    } catch (e) {
      startingRef.current = false;
      setStarting(false);
      if (e?.status === 429) {
        // The server's own sentence, in the informational slot rather than the
        // red one. No Retry-After is not a reason to leave the pill dead:
        // without a number there is nothing to count down to, so the tap stays
        // live and the server gets to answer again.
        setNotice(e.message);
        setCooldown(e.retryAfterSeconds ?? null);
      } else {
        setError(e?.message || "Could not reach Aegis-01 right now.");
      }
    }
  }, [agentName, configured, connected, cooldown, endSession, startSession]);

  // The cooldown counts itself down and then lets go. Nothing else clears it:
  // a pill that stays disabled after the window has passed is the same outage
  // the window was meant to prevent.
  useEffect(() => {
    if (cooldown === null) return undefined;
    if (cooldown <= 0) {
      setCooldown(null);
      return undefined;
    }
    const timer = setTimeout(() => setCooldown((s) => (s === null ? s : s - 1)), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  // Hang up if the visitor navigates away mid-sentence.
  useEffect(() => () => endSession(), [endSession]);

  // A reply arrives whole, not token by token, so the card starts at the top of
  // each new one. The fade only appears when there is genuinely more below —
  // drawn unconditionally it would wash out the last line of a short caption.
  useEffect(() => {
    const el = captionRef.current;
    if (!el) return;
    el.scrollTop = 0;
    setCaptionOverflows(el.scrollHeight > el.clientHeight + 1);
  }, [caption]);

  const connecting = starting || status === "connecting";
  const listening = connected && !isSpeaking;

  let label = `Tap to speak with ${agentName}`;
  let tone = "bg-slate-900 text-white hover:bg-blue-600";
  let dot = "bg-green-500";

  if (cooldown > 0) {
    // Amber, not red, and it names the wait. "Tap to try again" over a limit
    // that refuses every tap is the product telling the visitor to do the one
    // thing that cannot work.
    label = `Back in ${waitLabel(cooldown)}`;
    tone = "bg-amber-500 text-white";
    dot = "bg-white/70";
  } else if (error) {
    label = "Tap to try again";
    tone = "bg-rose-600 text-white hover:bg-rose-700";
    dot = "bg-white/70";
  } else if (connecting) {
    label = "Connecting…";
    tone = "bg-slate-700 text-white";
    dot = "bg-amber-300";
  } else if (connected && isSpeaking) {
    label = `${agentName} is speaking…`;
    tone = "bg-blue-600 text-white hover:bg-blue-700";
    dot = "bg-white/80";
  } else if (listening) {
    label = "Listening… tap to end";
    tone = "bg-[#765F52] text-white";
    dot = "bg-white/70";
  }

  // One stacking column rather than three independently offset absolutes: the
  // caption and the error used to be pinned 1rem apart and overlapped whenever
  // a call dropped mid-sentence, and every length change needed a new offset.
  return (
    <div className="absolute -bottom-10 left-1/2 z-30 flex w-[320px] max-w-[86vw] -translate-x-1/2 flex-col items-center gap-2.5">
      <button
        type="button"
        onClick={toggle}
        disabled={connecting || cooldown > 0}
        aria-live="polite"
        aria-label={label}
        className={`flex w-full transform-gpu cursor-pointer items-center justify-center gap-3 rounded-full py-3.5 text-[15px] font-bold shadow-2xl transition-transform hover:-translate-y-1 disabled:cursor-wait disabled:hover:translate-y-0 ${tone}`}
      >
        <span className={`h-2 w-2 rounded-full animate-pulse ${dot}`} />
        {label}
      </button>

      {/* What Aegis-01 just said. Speech runs longer than any fixed box, so the
          card caps at three lines and stays scrollable — a mid-word ellipsis
          reads as broken text, where a soft fade reads as more to come. */}
      {connected && caption && (
        <div className="animate-fade-in relative w-full rounded-2xl border border-slate-200 bg-white/85 shadow-lg shadow-slate-300/30 backdrop-blur-sm">
          <div
            ref={captionRef}
            aria-live="polite"
            className="no-scrollbar max-h-[5rem] overflow-y-auto px-4 py-2.5"
          >
            <p className="text-center text-[13px] font-medium leading-5 text-slate-600">
              {caption}
            </p>
          </div>
          {captionOverflows && (
            <span className="pointer-events-none absolute inset-x-px bottom-px h-7 rounded-b-2xl bg-gradient-to-t from-white via-white/80 to-transparent" />
          )}
        </div>
      )}

      {/* A failed call is red; a deliberate limit and "no credentials here"
          are information, so neither is. */}
      {(error || notice) && (
        <p
          className={`text-center text-[12px] font-medium ${
            error ? "text-rose-600" : "text-slate-500"
          }`}
        >
          {error || notice}
        </p>
      )}
    </div>
  );
}

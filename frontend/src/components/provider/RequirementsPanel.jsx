import React, { useEffect, useState } from "react";
import { SmallButton } from "../app/appui";
import Icon from "../Icon";
import { api } from "../../lib/api";

/**
 * What a customer has to grant before this automation can do anything.
 *
 * <h2>Why this had to be built rather than written</h2>
 * Every workflow in the catalog already declared its requirements — platform,
 * a sentence of plain English, and the exact API permissions — and none of it
 * reached a screen. A customer received an automation and discovered what
 * access it wanted by running it and reading a permissions error, which reads
 * as the automation being broken rather than unconfigured.
 *
 * An AGENT is the case that matters, and the one a human cannot answer by
 * reading: it declares nothing itself, so its real requirement is the union of
 * what its tools need. The FinOps analyst holds three workflows and therefore
 * wants five permissions across EC2, Cost Explorer and CloudTrail.
 *
 * <h2>The policy is the useful part</h2>
 * A list of API names says WHAT to grant, not HOW. The pasteable IAM policy is
 * the thing that turns this panel from documentation into an instruction —
 * and it is least-privilege by construction, containing exactly the calls the
 * automation declares.
 */
export default function RequirementsPanel({ itemId }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [copied, setCopied] = useState(null);

  useEffect(() => {
    if (!itemId) return undefined;
    let dropped = false;
    setData(null);
    setError(null);
    api
      .libraryRequirements(itemId)
      .then((r) => !dropped && setData(r))
      // Said, not swallowed. "Needs nothing" is a real answer for an
      // automation that reads only the platform's own record, so a failed
      // request must not be able to look like one.
      .catch((e) => !dropped && setError(e.message || "Could not read what this needs."))
    ;
    return () => {
      dropped = true;
    };
  }, [itemId]);

  const copy = (text, key) => {
    navigator.clipboard?.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(null), 1500);
  };

  if (error) {
    return (
      <p className="text-sm text-amber-700" role="alert">
        {error}
      </p>
    );
  }
  if (!data) {
    return <p className="text-sm text-slate-500">Checking what this needs…</p>;
  }

  return (
    <div className="space-y-3">
      {data.connections.length === 0 && data.unresolved.length === 0 && (
        // A genuine and common answer: the platform-plane automations read
        // AutoOps's own record and need no vendor credential at all. Worth
        // stating plainly, because "no requirements" is a selling point.
        <p className="text-sm text-slate-600">
          No cloud account needed. This runs against the platform&rsquo;s own record.
        </p>
      )}

      {data.connections.map((c) => (
        <div key={c.platform} className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="mb-2 flex items-center gap-2">
            <Icon name="cloud" size={15} />
            <span className="text-sm font-semibold text-slate-900">
              {c.platform} account
            </span>
          </div>

          {c.customerText && (
            // The tool author's own sentence, verbatim. Whoever wrote the
            // automation knows why it wants the access; a generated line here
            // would be a guess presented as documentation.
            <p className="mb-3 text-sm leading-relaxed text-slate-600">{c.customerText}</p>
          )}

          <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
            Permissions
          </p>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {c.permissions.map((p) => (
              <code
                key={p}
                className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-700"
              >
                {p}
              </code>
            ))}
          </div>

          {c.neededBy?.length > 0 && (
            // So a customer can see what withholding one costs them, rather
            // than facing an all-or-nothing grant.
            <p className="mt-2 text-[11px] text-slate-500">
              Needed by: {c.neededBy.join(", ")}
            </p>
          )}

          {c.policyDocument && (
            <div className="mt-3">
              <div className="mb-1 flex items-center justify-between">
                <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                  Paste this as an IAM policy
                </p>
                <button
                  type="button"
                  onClick={() => copy(c.policyDocument, c.platform)}
                  className="text-[11px] font-medium text-violet-600 hover:underline"
                >
                  {copied === c.platform ? "Copied" : "Copy"}
                </button>
              </div>
              <pre className="max-h-56 overflow-auto rounded-lg border border-slate-800 bg-slate-950 p-3 font-mono text-[10px] leading-relaxed text-slate-200">
                {c.policyDocument}
              </pre>
              <p className="mt-1.5 text-[11px] leading-relaxed text-slate-500">
                AWS console → IAM → Policies → Create policy → JSON. Attach it to the
                role or user whose keys you connect here. It grants these calls and
                nothing else.
              </p>
            </div>
          )}
        </div>
      ))}

      {data.unresolved.length > 0 && (
        // Named rather than dropped. A permission list that looks complete and
        // is short is worse than an honest gap: the customer grants it, still
        // hits a permissions error, and stops trusting the next list.
        <p className="text-[11px] leading-relaxed text-amber-700" role="alert">
          {data.unresolved.length} tool(s) are not in this catalog, so their access
          needs are unknown and NOT included above:{" "}
          <code className="font-mono">{data.unresolved.join(", ")}</code>
        </p>
      )}
    </div>
  );
}

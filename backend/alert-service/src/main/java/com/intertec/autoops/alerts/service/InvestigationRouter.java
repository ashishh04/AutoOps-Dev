package com.intertec.autoops.alerts.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which engine should investigate this incident.
 *
 * <p>Two engines, because neither can do the other's job:
 *
 * <ul>
 *   <li><b>the AWS agent</b> joins CloudWatch alarm state against the CloudTrail
 *       change timeline — "of the forty things that changed in the last six
 *       hours, which one touched a resource in that cluster four minutes before
 *       it went red". It runs on the tenant's own AWS credentials, which are
 *       already stored per tenant and encrypted;</li>
 *   <li><b>the investigation engine</b> reaches a live cluster: kubectl, pod
 *       logs, Loki, Prometheus queries.</li>
 * </ul>
 *
 * <p>Routing rather than picking one is not a hedge. The investigation engine
 * has <b>no AWS toolset at all</b> — reaching AWS from it would mean shelling
 * out to the CLI with one static credential shared across every tenant, inside
 * a container that can then run any command. The agent already does the AWS
 * half better, with per-tenant credentials and read-only guardrails, so the
 * honest design is to send each incident to the thing that can actually see it.
 *
 * <p>The customer is never shown this choice. Both write the same
 * investigation, into the same panel, under the same word.
 */
public final class InvestigationRouter {

    /** Where the estate is AWS and the agent can see things the engine cannot. */
    private static final Set<String> AWS_SOURCES = Set.of(
            "cloudwatch", "aws", "amazonsqs", "s3", "awscloudwatch", "cloudtrail");

    /** Kubernetes-shaped, where a live cluster is the thing worth reading. */
    private static final Set<String> CLUSTER_SOURCES = Set.of(
            "kubernetes", "prometheus", "grafana", "loki", "gke", "eks", "aks",
            "openshift", "argocd", "victorialogs", "alertmanager");

    public enum Engine {
        /** The AWS incident RCA agent, run through agent-service. */
        AWS_AGENT,
        /** The investigation engine (live cluster + observability toolsets). */
        HOLMES
    }

    private InvestigationRouter() {
    }

    /**
     * @param sources the incident's alert sources, lowercased by the caller or not
     * @param holmesAvailable whether an investigation engine is configured at all
     */
    public static Engine route(List<String> sources, boolean holmesAvailable) {
        boolean aws = matches(sources, AWS_SOURCES);
        boolean cluster = matches(sources, CLUSTER_SOURCES);

        // AWS wins a tie. An incident carrying both a CloudWatch alarm and a
        // Prometheus alert is far more often an AWS estate scraped by
        // Prometheus than a cluster that happens to emit CloudWatch — and the
        // agent degrades better, because it reports the window it searched
        // rather than failing to reach a cluster that was never there.
        if (aws) {
            return Engine.AWS_AGENT;
        }
        if (cluster && holmesAvailable) {
            return Engine.HOLMES;
        }
        // Nothing recognisable. The engine reasons from the alert text alone,
        // which is honest and occasionally useful; with no engine configured
        // the agent is the only thing that can run at all.
        return holmesAvailable ? Engine.HOLMES : Engine.AWS_AGENT;
    }

    private static boolean matches(List<String> sources, Set<String> known) {
        if (sources == null) {
            return false;
        }
        for (String s : sources) {
            if (s == null) {
                continue;
            }
            String v = s.toLowerCase(Locale.ROOT).trim();
            if (known.contains(v)) {
                return true;
            }
            // Providers name themselves inconsistently — "aws-cloudwatch",
            // "cloudwatch-metrics". A prefix match on a known root beats a list
            // that has to be extended every time a vendor renames something.
            for (String k : known) {
                if (v.startsWith(k) || v.endsWith(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The sources an incident reports, from either of the shapes the engine uses. */
    @SuppressWarnings("unchecked")
    public static List<String> sourcesOf(Map<String, Object> incident) {
        Object raw = incident == null ? null : incident.get("alert_sources");
        if (!(raw instanceof List<?>)) {
            raw = incident == null ? null : incident.get("sources");
        }
        if (raw instanceof List<?> l) {
            return l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }
}

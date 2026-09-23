package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * What the investigation engine concluded, and what it actually looked at.
 *
 * <p>{@code toolCalls} is not decoration. An analysis whose commands all failed
 * is a guess dressed as a finding - the reference run could not reach the
 * cluster and said so - and the only way a reader can tell the difference is by
 * seeing what ran and whether it worked.
 *
 * @param askedAt   when this was produced. An investigation is a point-in-time
 *                  statement and goes stale; showing it undated invites acting
 *                  on yesterday's conclusion
 * @param tookMs    cost and duration, shown because someone pays for each run
 */
public record InvestigationView(String analysis,
                                List<ToolCallView> toolCalls,
                                List<String> followUps,
                                String model,
                                String askedAt,
                                Long tookMs,
                                Double costUsd) {
}

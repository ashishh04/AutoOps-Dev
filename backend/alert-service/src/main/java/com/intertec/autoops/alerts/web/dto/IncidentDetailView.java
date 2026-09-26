package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One incident, with the alerts that became it and whatever was concluded.
 *
 * @param withheldEvidence how many of the incident's alerts this caller may not
 *                         see. Normally zero. It is non-zero only when
 *                         correlation grouped alerts across a tenant boundary,
 *                         and it is reported rather than hidden because
 *                         {@code incident.alertCount} comes from the engine and
 *                         counts them all — a detail page showing "5 alerts"
 *                         above a list of two reads as a bug in AutoOps, which
 *                         is the one thing worse than saying what happened.
 *
 *                         <p>It says HOW MANY and never whose. The count is what
 *                         makes the page consistent; the owner is not this
 *                         caller's business.
 */
public record IncidentDetailView(IncidentSummaryView incident,
                                 List<AlertView> evidence,
                                 int withheldEvidence,
                                 InvestigationView investigation) {
}

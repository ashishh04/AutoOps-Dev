package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/** One incident, with the alerts that became it and whatever was concluded. */
public record IncidentDetailView(IncidentSummaryView incident,
                                 List<AlertView> evidence,
                                 InvestigationView investigation) {
}

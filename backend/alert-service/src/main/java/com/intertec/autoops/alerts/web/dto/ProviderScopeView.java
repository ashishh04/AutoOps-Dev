package com.intertec.autoops.alerts.web.dto;

/** One permission the credentials need, and why. */
public record ProviderScopeView(String name,
                                String description,
                                boolean mandatory) {
}

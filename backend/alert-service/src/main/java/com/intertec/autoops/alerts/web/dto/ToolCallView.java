package com.intertec.autoops.alerts.web.dto;

/**
 * One thing the investigation engine did.
 *
 * @param succeeded whether it worked. A failed kubectl is the difference
 *                  between a root cause and a plausible story, so the console
 *                  marks it rather than hiding it
 */
public record ToolCallView(String tool,
                           String description,
                           String output,
                           boolean succeeded) {
}

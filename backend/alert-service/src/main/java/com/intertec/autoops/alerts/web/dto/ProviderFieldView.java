package com.intertec.autoops.alerts.web.dto;

/**
 * One input on the connect form.
 *
 * @param sensitive render as a password field. It also means the value goes
 *                  INTO the engine and is never read back out — there is no
 *                  endpoint here that returns a stored credential, deliberately
 * @param hint      usually a link to the vendor's own docs ("where do I find my
 *                  API key?"). It comes from the engine's catalog but points at
 *                  Datadog or Grafana, never at the engine itself
 */
public record ProviderFieldView(String name,
                                String label,
                                String hint,
                                boolean required,
                                boolean sensitive,
                                String defaultValue,
                                String validation) {
}

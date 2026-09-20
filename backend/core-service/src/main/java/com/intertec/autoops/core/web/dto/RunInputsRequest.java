package com.intertec.autoops.core.web.dto;

import java.util.Map;

/**
 * The values a person typed into a workflow's published input form.
 *
 * <p>Wrapped in an object rather than posting the map at the top level so the
 * trigger call has somewhere to grow — a run note, an idempotency key, a
 * requested priority — without every existing client breaking on the shape
 * change.
 *
 * <p>The whole body is optional: a workflow with no declared inputs, and every
 * client written before this existed, POST nothing at all.
 *
 * @param inputs variable name → value, as declared by the workflow's start
 *               node. Values stay {@code Object} because the form has
 *               number and select controls alongside text, and coercing them
 *               all to String here would send {@code "3"} where the workflow
 *               declared a number.
 */
public record RunInputsRequest(Map<String, Object> inputs) {
}

package com.intertec.autoops.core.service;

import java.util.List;

/**
 * One field of a workflow's published input form.
 *
 * <p>Lived on {@code DifyWorkflowService} until the engine behind workflows
 * stopped being Dify. It is its own type now because five unrelated classes
 * read it — the run controller, the agent dispatch controller, the input
 * validator, the native workflow service and the run service — and none of
 * them should have to name a vendor to describe a form field.
 *
 * <p><b>Where it comes from changed, and that is the point.</b> This used to be
 * fetched from Dify's {@code /v1/parameters} on every list and every run, which
 * meant the form a customer saw was only ever as available as a third party.
 * It is now read out of the workflow definition itself, so it cannot
 * desynchronise from the variables the workflow actually reads — the failure
 * the remote lookup existed to avoid, and only avoided while Dify was up.
 *
 * @param defaultValue nullable; the form's pre-filled value
 * @param options      empty unless {@code type} is a select
 * @param maxLength    nullable; only text and paragraph fields declare one
 */
public record WorkflowInputField(String variable, String label, String type, boolean required,
                                 String defaultValue, List<String> options, Integer maxLength) {
}

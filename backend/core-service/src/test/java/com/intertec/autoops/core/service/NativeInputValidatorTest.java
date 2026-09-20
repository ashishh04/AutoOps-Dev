package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.exception.CoreException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate between a caller's answers and a command line.
 *
 * <p>These are not form-polish tests. A value that gets past this class is
 * substituted into an SSH target or a script argument, so each rule here is
 * the thing standing between a caller and arbitrary execution on a customer's
 * server. No Spring context: the rules are pure.
 */
class NativeInputValidatorTest {

    private final NativeInputValidator validator = new NativeInputValidator(new ObjectMapper());

    private static final String DEFINITION = """
            {"nodes":[{"type":"ssh"}],
             "inputs":[
               {"variable":"TargetHost","label":"Target host","type":"string","required":true,
                "pattern":"^[a-z0-9.-]+$"},
               {"variable":"DiskWarnPercent","label":"Disk warning","type":"number",
                "required":true,"min":50,"max":99,"default":85},
               {"variable":"Execute","label":"Execute","type":"boolean","required":false,
                "default":false},
               {"variable":"ApprovalReference","label":"Approval reference","type":"string",
                "required":false,"requiredWhen":{"field":"Execute","equals":true},
                "pattern":"^APR-[A-Za-z0-9-]{6,}$"},
               {"variable":"OutputFormat","label":"Output format","type":"select",
                "required":true,"options":["Console","JSON"],"default":"JSON"}
             ]}
            """;

    private Map<String, Object> answers(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private Map<String, Object> validate(Map<String, Object> supplied) {
        return validator.validate(DEFINITION, supplied);
    }

    @Test
    void acceptsAValidSetAndAppliesDefaults() {
        Map<String, Object> clean = validate(answers("TargetHost", "app-prod-01.local"));
        assertThat(clean)
                .containsEntry("TargetHost", "app-prod-01.local")
                .containsEntry("DiskWarnPercent", 85L)
                .containsEntry("Execute", false)
                .containsEntry("OutputFormat", "JSON");
    }

    @Test
    void refusesAValueThatWouldSmuggleAShellCommand() {
        // The whole point. Semicolons are not in the host pattern, so this
        // never reaches the ssh argument list.
        assertThatThrownBy(() -> validate(answers("TargetHost", "host; rm -rf /")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Target host");
    }

    @Test
    void refusesAnInputTheWorkflowNeverDeclared() {
        // Fail closed: silently dropping something the caller believed
        // mattered is worse than refusing it.
        assertThatThrownBy(() -> validate(answers(
                "TargetHost", "app-01", "SudoPassword", "hunter2")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("SudoPassword");
    }

    @Test
    void refusesAMissingRequiredFieldThatHasNoDefault() {
        assertThatThrownBy(() -> validate(answers()))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Target host");
    }

    @Test
    void enforcesNumericBounds() {
        assertThatThrownBy(() -> validate(answers("TargetHost", "a", "DiskWarnPercent", 5)))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("at least 50");
        assertThatThrownBy(() -> validate(answers("TargetHost", "a", "DiskWarnPercent", 200)))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("at most 99");
    }

    @Test
    void enforcesSelectOptions() {
        assertThatThrownBy(() -> validate(answers("TargetHost", "a", "OutputFormat", "YAML")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("must be one of");
    }

    @Test
    void rejectsANonBooleanForASwitch() {
        // "yes" reaching a destructive flag as a truthy string is the failure
        // this prevents.
        assertThatThrownBy(() -> validate(answers("TargetHost", "a", "Execute", "yes")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("true or false");
    }

    @Test
    void requiredWhenBindsOnlyWhenItsTriggerIsSet() {
        assertThatCode(() -> validate(answers("TargetHost", "a", "Execute", false)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validate(answers("TargetHost", "a", "Execute", true)))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Approval reference");
    }

    @Test
    void approvalReferenceStillHasToLookLikeOne() {
        assertThatThrownBy(() -> validate(answers(
                "TargetHost", "a", "Execute", true, "ApprovalReference", "sure-go-ahead")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Approval reference");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        // One round trip per mistake would be a poor form; more importantly a
        // caller fixing errors one at a time learns the contract slowly.
        assertThatThrownBy(() -> validate(answers("DiskWarnPercent", 5, "OutputFormat", "YAML")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Target host")
                .hasMessageContaining("at least 50")
                .hasMessageContaining("must be one of");
    }

    @Test
    void aBrokenPatternRejectsEverythingRatherThanAcceptingIt() {
        // A guard that will not compile must not read as an absent guard.
        String broken = "{\"inputs\":[{\"variable\":\"Host\",\"label\":\"Host\","
                + "\"type\":\"string\",\"required\":true,\"pattern\":\"^[unclosed\"}]}";
        assertThatThrownBy(() -> validator.validate(broken, answers("Host", "anything")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("cannot be validated");
    }

    @Test
    void aWorkflowWithNoFormIsNullNotEmpty() {
        // null means "nothing was ever asked for"; {} would mean "asked and
        // left blank", and the run row keeps them apart.
        assertThat(validator.validate("{\"nodes\":[{\"type\":\"ssh\"}]}", answers())).isNull();
        assertThat(validator.validate("{not json", answers())).isNull();
        assertThat(validator.validate(null, answers())).isNull();
    }

    @Test
    void declaresFormOnlyWhenThereIsOne() {
        assertThat(validator.declaresForm(DEFINITION)).isTrue();
        assertThat(validator.declaresForm("{\"nodes\":[]}")).isFalse();
    }

    // ---- the form the tenant console renders -------------------------------

    @Test
    void offersEveryDeclaredFieldToTheConsole() {
        // Returning nothing here is what made a tenant press Run, supply
        // nothing, and be refused for inputs they were never shown.
        assertThat(validator.formFor(DEFINITION))
                .extracting(f -> f.variable())
                .containsExactly("TargetHost", "DiskWarnPercent", "Execute",
                        "ApprovalReference", "OutputFormat");
    }

    @Test
    void theFormCarriesWhatTheFieldNeedsToRender() {
        var host = validator.formFor(DEFINITION).get(0);
        assertThat(host.label()).isEqualTo("Target host");
        assertThat(host.required()).isTrue();

        var format = validator.formFor(DEFINITION).get(4);
        assertThat(format.options()).containsExactly("Console", "JSON");
        assertThat(format.defaultValue()).isEqualTo("JSON");
    }

    @Test
    void everyRequiredFieldTheValidatorEnforcesIsOnTheForm() {
        // If these two ever drift, the operator is asked for one thing and
        // judged on another.
        assertThat(validator.formFor(DEFINITION))
                .filteredOn(f -> f.required())
                .extracting(f -> f.variable())
                .containsExactly("TargetHost", "DiskWarnPercent", "OutputFormat");
    }

    @Test
    void aWorkflowWithNoInputsOffersAnEmptyForm() {
        assertThat(validator.formFor("{\"nodes\":[]}")).isEmpty();
    }

    @Test
    void anOptionalFieldLeftBlankStillAppearsAsEmpty() {
        // Omitting it entirely left its {{placeholder}} unresolved, and the
        // step was then refused for "missing" values the form called optional.
        Map<String, Object> clean = validate(answers("TargetHost", "app-01"));
        assertThat(clean).containsEntry("ApprovalReference", "");
    }

    @Test
    void aRequiredFieldLeftBlankIsStillAnError_notAnEmptyValue() {
        assertThatThrownBy(() -> validate(answers()))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Target host");
    }

    // ------ argument names as a model actually spells them ------

    @Test
    void aSnakeCasedNameFindsThePascalCaseInputItMeant() {
        // The failure this exists for: agent 9316 on 2026-09-20 sent
        // {"region": ..., "lookback_hours": 24} against inputs declared Region
        // and LookbackHours, was refused, and sent the IDENTICAL names on all
        // four retries before giving up. A model regenerates a schema's keys in
        // its own casing rather than copying them, and an error message does
        // not teach it otherwise.
        assertThat(validate(answers("target_host", "web-01", "disk_warn_percent", 70)))
                .containsEntry("TargetHost", "web-01")
                .containsEntry("DiskWarnPercent", 70L);
    }

    @Test
    void caseAloneIsEnoughToMatch() {
        assertThat(validate(answers("targethost", "web-01")))
                .containsEntry("TargetHost", "web-01");
    }

    @Test
    void aNameMatchingNoDeclaredInputIsStillRefused() {
        // Canonicalising resolves spelling, not membership. An undeclared input
        // stays undeclared, which is what keeps this a validator.
        assertThatThrownBy(() -> validate(answers(
                "TargetHost", "web-01", "sudo_password", "hunter2")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("sudo_password");
    }

    @Test
    void twoSpellingsOfOneInputAreRefusedRatherThanSilentlyDropped() {
        // Picking a winner would discard the caller's other value in silence.
        assertThatThrownBy(() -> validate(answers(
                "TargetHost", "web-01", "target_host", "web-02")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("both answer");
    }

    @Test
    void aResolvedNameStillFacesItsOwnPatternCheck() {
        // The boundary is the pattern, not the spelling of the key.
        assertThatThrownBy(() -> validate(answers("target_host", "host; rm -rf /")))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Target host");
    }
}

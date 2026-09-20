package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.LibraryItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the 213 seeded catalog rows actually contain, and what has to be
 * inferred from it.
 *
 * <p>The bodies here are trimmed from real rows in {@code library-catalog.sql}
 * — including the one property that broke every one of them: the step object
 * carries a {@code label} and a {@code value} and <b>no type</b>.
 */
class LibraryScriptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LibraryItem item(String definition) {
        LibraryItem libraryItem = new LibraryItem();
        libraryItem.setTitle("AWS Certificate Expiry Monitor");
        libraryItem.setCategory("AWS");
        libraryItem.setDefinition(definition);
        return libraryItem;
    }

    @Test
    void readsTheCatalogsTypelessPowerShellAsPowerShell() {
        // This shape is the whole bug: with no `type`, ExecutionEngine resolved
        // it to the literal "step", job-service had no runner registered under
        // that name, and every catalog script failed with "No executor for step
        // type 'step'" before a line of it ran.
        LibraryItem catalogRow = item("""
                {"steps":[{"label":"AWS Certificate Expiry Monitor",
                           "value":"#Requires -Version 5.1\\n[CmdletBinding()]\\nparam([string]$Region)\\nWrite-Host 'x'"}]}
                """);

        LibraryScript.Resolved resolved = LibraryScript.resolve(catalogRow, MAPPER).orElseThrow();

        assertThat(resolved.stepType()).isEqualTo("powershell");
        assertThat(resolved.body()).contains("#Requires -Version 5.1");
    }

    @Test
    void aDeclaredTypeBeatsInference() {
        // Inference exists for rows that never carried a type, not to
        // second-guess the ones that do.
        LibraryItem declared = item("""
                {"steps":[{"type":"pyscript","label":"x","value":"#Requires -Version 5.1"}]}
                """);

        assertThat(LibraryScript.resolve(declared, MAPPER).orElseThrow().stepType())
                .isEqualTo("pyscript");
    }

    @Test
    void theLiteralTypeStepIsTreatedAsAbsent() {
        // "step" is what the engine's own fallback produced, not something an
        // author chose. Honouring it would preserve the bug in any definition
        // that had already been round-tripped through the designer.
        LibraryItem roundTripped = item("""
                {"steps":[{"type":"step","label":"x","value":"[CmdletBinding()]\\nparam()"}]}
                """);

        assertThat(LibraryScript.resolve(roundTripped, MAPPER).orElseThrow().stepType())
                .isEqualTo("powershell");
    }

    @Test
    void aShebangSettlesTheQuestion() {
        assertThat(LibraryScript.inferType("#!/usr/bin/env python3\nprint(1)"))
                .isEqualTo("pyscript");
        assertThat(LibraryScript.inferType("#!/usr/bin/env pwsh\nGet-Date"))
                .isEqualTo("powershell");
        assertThat(LibraryScript.inferType("#!/bin/bash\nset -e\necho hi"))
                .isEqualTo("script");
    }

    @Test
    void recognisesPythonWithoutAShebang() {
        assertThat(LibraryScript.inferType("import boto3\n\ndef main():\n    pass"))
                .isEqualTo("pyscript");
    }

    @Test
    void fallsBackToShellRatherThanGuessing() {
        // Bash is what an unrecognised body was treated as before any of this
        // existed, so an unknown script behaves exactly as it always did.
        assertThat(LibraryScript.inferType("echo hello && ls -la")).isEqualTo("script");
    }

    @Test
    void emptyAndUnparseableDefinitionsResolveToNothing() {
        assertThat(LibraryScript.resolve(item("{\"steps\":[]}"), MAPPER)).isEmpty();
        assertThat(LibraryScript.resolve(item("not json at all"), MAPPER)).isEmpty();
        assertThat(LibraryScript.resolve(item("{\"steps\":[{\"label\":\"x\"}]}"), MAPPER))
                .isEmpty();
        assertThat(LibraryScript.resolve(null, MAPPER)).isEmpty();
    }
}

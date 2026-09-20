package com.intertec.autoops.agent.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a run is allowed to claim it covered.
 *
 * <p>Pinned before the reaper exists, because the reaper is the thing that turns
 * a wrong claim into deleted work. A scope that overstates coverage marks
 * findings resolved that nobody looked at, and the failure is invisible — a
 * quieter backlog looks exactly like a better week.
 */
class RunScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunScope parse(String json) {
        try {
            return RunScope.parse(MAPPER.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------- array shape ---

    /**
     * The decision this whole type exists for. {@code public_exposure_auditor}
     * correlates buckets and security groups with IAM users, so one run really
     * does cover {@code cloud_resource} and {@code principal} together.
     */
    @Test
    void oneRunMayCoverSeveralSubjectKinds() {
        RunScope scope = parse("""
                [
                  {"kind":"dimensional","subject_kind":"cloud_resource",
                   "dimensions":{"environment":["prod"]}},
                  {"kind":"all","subject_kind":"principal"}
                ]
                """);

        assertThat(scope.claims()).hasSize(2);
        assertThat(scope.byKind()).containsOnlyKeys("cloud_resource", "principal");
    }

    /** Scopes written before the array shape existed still have to be readable. */
    @Test
    void aLoneObjectIsAcceptedAsAOneElementList() {
        assertThat(parse("{\"kind\":\"all\"}").isEverything()).isTrue();
    }

    @Test
    void aBareAllCannotSitBesideOtherScopes() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"all"}, {"kind":"all","subject_kind":"principal"}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("cannot");
    }

    @Test
    void oneScopePerSubjectKind() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"all","subject_kind":"principal"},
                 {"kind":"all","subject_kind":"principal"}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("two entries");
    }

    /** Covering nothing is a claim; forgetting to fill the field in is not. */
    @Test
    void anEmptyArrayIsNotHowARunSaysItCoveredNothing() {
        assertThatThrownBy(() -> parse("[]"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("subject_id_count 0");

        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":0,"subject_ids_digest":"sha256:abc"}]
                """)).doesNotThrowAnyException();
    }

    @Test
    void aMissingScopeIsRefusedRatherThanTreatedAsEverything() {
        assertThatThrownBy(() -> RunScope.parse(null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("nothing it produces can ever be reaped");
    }

    // ------------------------------------------------------------ parsing ---

    @Test
    void dimensionalAndEnumeratedScopesMustNameTheirKind() {
        assertThatThrownBy(() -> parse("[{\"kind\":\"dimensional\"}]"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("subject_kind");
    }

    /**
     * A dimension that is not a column cannot be pushed into a WHERE, and one
     * silently dropped is a scope claiming more than the run covered.
     */
    @Test
    void anUnknownDimensionIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"owner_team":["payments"]}}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("not a dimension");
    }

    /**
     * The general rule, stated per-field so the next person does not have to
     * rediscover it: a scope says what was LOOKED AT, so anything produced by
     * looking is disqualified. Severity is the seductive one because it yields a
     * scope that reads tighter while describing the output set.
     */
    @Test
    void aScopeCannotSelectOnAnythingDerivedFromEvaluatingASubject() {
        for (String derived : new String[] {
                "severity", "risk_tier", "confidence_band", "priority_score"}) {
            assertThatThrownBy(() -> parse("""
                    [{"kind":"dimensional","subject_kind":"cloud_resource",
                      "dimensions":{"%s":["x"]}}]
                    """.formatted(derived)))
                    .as("dimension %s", derived)
                    .isInstanceOf(AgentException.class)
                    .hasMessageContaining("describes the output set rather than the input set");
        }
    }

    @Test
    void anEmptyDimensionListIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":[]}}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Omit the key");
    }

    @Test
    void anEnumeratedScopeLargerThanTheCapIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":100001,"subject_ids_digest":"sha256:abc"}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("describing a sweep");
    }

    @Test
    void anUnsupportedShapeIsRefusedWithTheReason() {
        assertThatThrownBy(() -> parse("[{\"kind\":\"glob\",\"pattern\":\"svc-*\"}]"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("SQL predicate over indexed columns");
    }

    @Test
    void anUnknownCoverageVerdictIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"all","subject_kind":"principal","coverage":"MOSTLY"}]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("not a coverage verdict");
    }

    // ---------------------------------------------------------- narrowing ---

    @Test
    void aRunThatSetOutToCoverEverythingMayFinishWithAnything() {
        RunScope start = parse("[{\"kind\":\"all\"}]");

        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":12,"subject_ids_digest":"sha256:abc",
                  "coverage":"COMPLETE"}]
                """).checkNarrows(start)).doesNotThrowAnyException();
    }

    @Test
    void aRunThatSetOutToCoverPartOfTheEstateCannotFinishClaimingAllOfIt() {
        RunScope start = parse("[{\"kind\":\"all\",\"subject_kind\":\"principal\"}]");

        assertThatThrownBy(() ->
                parse("[{\"kind\":\"all\",\"coverage\":\"COMPLETE\"}]").checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("cannot finish claiming it covered all of it");
    }

    @Test
    void aKindTheRunNeverSetOutToCoverIsRefused() {
        RunScope start = parse("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]");

        assertThatThrownBy(() -> parse("""
                [{"kind":"all","subject_kind":"cloud_resource","coverage":"COMPLETE"},
                 {"kind":"all","subject_kind":"principal","coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("never set out to cover");
    }

    /**
     * <b>The failure this rule exists for.</b> The exposure auditor enumerates
     * buckets, the IAM call throws, and the agent completes reporting success
     * with a scope covering only {@code cloud_resource}. If omission read as
     * "covered none of it", that scope would be indistinguishable from one that
     * deliberately skipped principals — and from one that covered them.
     */
    @Test
    void aDeclaredKindCannotBeSilentlyDroppedAtCompletion() {
        RunScope start = parse("""
                [{"kind":"all","subject_kind":"cloud_resource"},
                 {"kind":"all","subject_kind":"principal"}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"all","subject_kind":"cloud_resource","coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Silence is not the same as skipping");
    }

    /** Saying so explicitly is fine — and keeps the buckets reaping. */
    @Test
    void aKindThatFailedIsDeclaredSkippedAndTheOthersStillCount() {
        RunScope start = parse("""
                [{"kind":"all","subject_kind":"cloud_resource"},
                 {"kind":"all","subject_kind":"principal"}]
                """);

        RunScope end = parse("""
                [{"kind":"all","subject_kind":"cloud_resource","coverage":"COMPLETE"},
                 {"kind":"all","subject_kind":"principal","coverage":"SKIPPED"}]
                """);
        assertThatCode(() -> end.checkNarrows(start)).doesNotThrowAnyException();

        assertThat(end.reapable())
                .singleElement()
                .extracting(ScopeClaim::subjectKind).isEqualTo("cloud_resource");
    }

    @Test
    void aCompletionElementWithNoCoverageVerdictIsRefused() {
        RunScope start = parse("[{\"kind\":\"all\",\"subject_kind\":\"principal\"}]");

        assertThatThrownBy(() ->
                parse("[{\"kind\":\"all\",\"subject_kind\":\"principal\"}]").checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does not say how that kind ended");
    }

    @Test
    void aDimensionalScopeMayShrinkItsValues() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod","staging"]}}]
                """);

        assertThatCode(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"]},"coverage":"COMPLETE"}]
                """).checkNarrows(start)).doesNotThrowAnyException();
    }

    @Test
    void aDimensionalScopeMayAddAConstraintItDidNotStartWith() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"]}}]
                """);

        assertThatCode(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"],"service_ref":["svc-checkout"]},
                  "coverage":"COMPLETE"}]
                """).checkNarrows(start)).doesNotThrowAnyException();
    }

    @Test
    void aDimensionalScopeCannotGrowItsValues() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["staging"]}}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["staging","prod"]},"coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("never set out to cover");
    }

    @Test
    void aDimensionalScopeCannotDropAConstraintItStartedWith() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["staging"]}}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource","dimensions":{},
                  "coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("finished unconstrained on environment");
    }

    /** An explicit list of what was visited is stronger than any filter. */
    @Test
    void aFilterMayResolveToAnExplicitList() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"alert_rule",
                  "dimensions":{"environment":["prod"]}}]
                """);

        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:abc",
                  "coverage":"COMPLETE"}]
                """).checkNarrows(start)).doesNotThrowAnyException();
    }

    /**
     * The reverse is not symmetric, and the asymmetry is the point: a filter can
     * match subjects the run never held a list for.
     */
    @Test
    void anExplicitListMayNotDissolveIntoAFilter() {
        RunScope start = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:abc"}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"alert_rule",
                  "dimensions":{"environment":["prod"]},"coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("never held a list for");
    }

    @Test
    void anEnumeratedScopeMayShrinkWhenPagingStopsEarly() {
        RunScope start = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:aaa"}]
                """);

        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":300,"subject_ids_digest":"sha256:bbb",
                  "coverage":"PARTIAL"}]
                """).checkNarrows(start)).doesNotThrowAnyException();
    }

    @Test
    void anEnumeratedScopeCannotGrow() {
        RunScope start = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":300,"subject_ids_digest":"sha256:aaa"}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:bbb",
                  "coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("having set out to cover 300");
    }

    /**
     * The same digest with a different count means one of the two numbers is
     * describing a list it did not hash — and the reaper would trust whichever
     * it happened to read.
     */
    @Test
    void theSameListCannotHaveTwoLengths() {
        RunScope start = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:aaa"}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":300,"subject_ids_digest":"sha256:aaa",
                  "coverage":"PARTIAL"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("not describing its own list");
    }

    /** Narrowing is checked per kind, so one bad kind fails the whole run. */
    @Test
    void oneWidenedKindFailsTheRunEvenWhenTheOthersNarrowed() {
        RunScope start = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"]}},
                 {"kind":"dimensional","subject_kind":"principal",
                  "dimensions":{"environment":["prod"]}}]
                """);

        assertThatThrownBy(() -> parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"],"service_ref":["svc-checkout"]},
                  "coverage":"COMPLETE"},
                 {"kind":"dimensional","subject_kind":"principal",
                  "dimensions":{"environment":["prod","dev"]},"coverage":"COMPLETE"}]
                """).checkNarrows(start))
                .isInstanceOf(AgentException.class);
    }

    // ---------------------------------------------------------- coherence ---

    /**
     * Narrowing proves a completion scope is no wider than the intent. It cannot
     * prove the run did the work — and the over-broad claim is precisely the
     * failure the coverage gauge cannot see, because a run declaring {@code all}
     * and examining forty subjects reads as a perfect coverage score while being
     * entitled to resolve a backlog of nine hundred.
     */
    @Test
    void completeCoverageOfAnUnboundedScopeCannotComeFromExaminingNothing() {
        RunScope end = parse("[{\"kind\":\"all\",\"coverage\":\"COMPLETE\"}]");

        assertThatThrownBy(() -> end.checkCoherent(Map.of(RunScope.EVERY_KIND, 0)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("examined no subjects at all");

        assertThatCode(() -> end.checkCoherent(Map.of(RunScope.EVERY_KIND, 900)))
                .doesNotThrowAnyException();
    }

    /** The list is right there; anything else is arithmetic that does not add up. */
    @Test
    void completeCoverageOfAListMeansExactlyThatManySubjects() {
        RunScope end = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:abc",
                  "coverage":"COMPLETE"}]
                """);

        assertThatThrownBy(() -> end.checkCoherent(Map.of("alert_rule", 300)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("explicit list of 847 subjects, having examined 300");

        assertThatCode(() -> end.checkCoherent(Map.of("alert_rule", 847)))
                .doesNotThrowAnyException();
    }

    /** An empty enumeration is the one COMPLETE claim that examines nothing. */
    @Test
    void anEmptyListIsCompletelyCoveredByExaminingNothing() {
        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":0,"subject_ids_digest":"sha256:abc",
                  "coverage":"COMPLETE"}]
                """).checkCoherent(Map.of("alert_rule", 0))).doesNotThrowAnyException();
    }

    @Test
    void aSkippedKindCannotReportSubjectsExamined() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"all","subject_kind":"principal","coverage":"SKIPPED"}]
                """).checkCoherent(Map.of("principal", 12)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("was skipped, yet reports 12");
    }

    @Test
    void partialCoverageCannotExamineMoreThanTheListHolds() {
        assertThatThrownBy(() -> parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":300,"subject_ids_digest":"sha256:abc",
                  "coverage":"PARTIAL"}]
                """).checkCoherent(Map.of("alert_rule", 400)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("examined 400 subjects from a list of 300");
    }

    /** The realistic multi-kind completion: buckets done, IAM threw. */
    @Test
    void aMixedRunIsCoherentWhenEachKindsNumbersMatchItsVerdict() {
        assertThatCode(() -> parse("""
                [{"kind":"enumerated","subject_kind":"cloud_resource",
                  "subject_id_count":412,"subject_ids_digest":"sha256:abc",
                  "coverage":"COMPLETE"},
                 {"kind":"all","subject_kind":"principal","coverage":"SKIPPED"}]
                """).checkCoherent(Map.of("cloud_resource", 412, "principal", 0)))
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------- source verified ---

    /**
     * An enumeration is unverified unless something says otherwise.
     *
     * <p>The safe default, because the flag means "a source total was checked
     * against this list" — and absence of the field means nobody checked, not
     * that checking passed.
     */
    @Test
    void anEnumerationIsUnverifiedUnlessTheToolReportedASourceTotal() {
        RunScope scope = parse("""
                [{"kind":"enumerated","subject_kind":"cloud_resource",
                  "subject_id_count":200,"subject_ids_digest":"sha256:abc"}]
                """);

        assertThat(scope.claims().getFirst().sourceVerified()).isFalse();
        assertThat(scope.uncheckedEnumerations()).hasSize(1);
    }

    @Test
    void aVerifiedEnumerationSaysSoAndSurvivesARoundTrip() {
        RunScope scope = parse("""
                [{"kind":"enumerated","subject_kind":"cloud_resource",
                  "subject_id_count":200,"subject_ids_digest":"sha256:abc",
                  "source_verified":true}]
                """);

        assertThat(scope.uncheckedEnumerations()).isEmpty();
        assertThat(RunScope.parse(scope.toJson(MAPPER)).claims())
                .isEqualTo(scope.claims());
    }

    /**
     * Only enumerations can be unchecked. {@code all} and {@code dimensional}
     * are not lists, so there is no total to compare them against — that is
     * what {@code overclaimSuspects} covers instead.
     */
    @Test
    void anAllScopeIsNotAnUncheckedEnumeration() {
        assertThat(parse("[{\"kind\":\"all\"}]").uncheckedEnumerations()).isEmpty();
    }

    /** Stamping coverage must not silently drop the verification flag. */
    @Test
    void addingACoverageVerdictKeepsTheVerificationFlag() {
        ScopeClaim verified = parse("""
                [{"kind":"enumerated","subject_kind":"cloud_resource",
                  "subject_id_count":1,"subject_ids_digest":"sha256:abc",
                  "source_verified":true}]
                """).claims().getFirst();

        assertThat(verified.withCoverage(ScopeClaim.Coverage.COMPLETE).sourceVerified())
                .isTrue();
    }

    // ------------------------------------------------------------ predicate ---

    @Test
    void aBareAllCompilesToTrue() {
        SubjectScope.Predicate predicate = parse("[{\"kind\":\"all\"}]")
                .scopes().getFirst().toPredicate();

        assertThat(predicate.sql()).isEqualTo("1 = 1");
        assertThat(predicate.params()).isEmpty();
    }

    @Test
    void aDimensionalScopeCompilesToAConjunctionOfIns() {
        SubjectScope.Predicate predicate = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod","staging"]}}]
                """).scopes().getFirst().toPredicate();

        assertThat(predicate.sql())
                .isEqualTo("subject_kind = ? AND environment IN (?, ?)");
        assertThat(predicate.params())
                .containsExactly("cloud_resource", "prod", "staging");
    }

    @Test
    void anEnumeratedScopeCompilesToAJoinOnTheMaterialisedList() {
        SubjectScope.Predicate predicate = parse("""
                [{"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":3,"subject_ids_digest":"sha256:abc"}]
                """).scopes().getFirst().toPredicate();

        assertThat(predicate.sql()).contains("agent_run_subject");
        assertThat(predicate.params()).element(1).isSameAs(SubjectScope.RUN_ID);
    }

    /** Duplicate values would emit a duplicate bind parameter for no benefit. */
    @Test
    void repeatedDimensionValuesCollapse() {
        SubjectScope.Predicate predicate = parse("""
                [{"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod","prod"]}}]
                """).scopes().getFirst().toPredicate();

        assertThat(predicate.params()).containsExactly("cloud_resource", "prod");
    }

    // --------------------------------------------------------- round trip ---

    /**
     * The scope is parsed, narrowed and written back, so a value that does not
     * survive the round trip is a claim that quietly changes shape in storage.
     */
    @Test
    void everyShapeSurvivesARoundTrip() {
        RunScope original = parse("""
                [{"kind":"all","subject_kind":"principal","coverage":"SKIPPED"},
                 {"kind":"dimensional","subject_kind":"cloud_resource",
                  "dimensions":{"environment":["prod"],"service_ref":["svc-checkout"]},
                  "coverage":"COMPLETE"},
                 {"kind":"enumerated","subject_kind":"alert_rule",
                  "subject_id_count":847,"subject_ids_digest":"sha256:abc",
                  "coverage":"PARTIAL"}]
                """);

        JsonNode written = original.toJson(MAPPER);
        RunScope reparsed = RunScope.parse(written);

        assertThat(reparsed.claims()).isEqualTo(original.claims());
    }

    @Test
    void aBareAllDoesNotAcquireASubjectKindOnTheWayOut() {
        assertThat(RunScope.parse(parse("[{\"kind\":\"all\"}]").toJson(MAPPER)).isEverything())
                .isTrue();
    }

    /** A declaration round-trips without inventing a coverage verdict. */
    @Test
    void aDeclarationStaysADeclaration() {
        RunScope declared = parse("[{\"kind\":\"all\",\"subject_kind\":\"principal\"}]");

        assertThat(declared.toJson(MAPPER).toString()).doesNotContain("coverage");
        assertThat(declared.reapable()).isEmpty();
    }
}

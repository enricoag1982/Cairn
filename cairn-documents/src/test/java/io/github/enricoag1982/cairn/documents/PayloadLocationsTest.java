package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.PayloadFixtures.createOrganization;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.locationOf;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.payload;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.pointers;
import static io.github.enricoag1982.cairn.documents.PayloadFixtures.read;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.documents.PayloadFixtures.ReferenceDomainStore;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Locating the problems of a dry run and of a commit in the payload that was read (design D3 of
 * {@code add-json-payloads}), for each form of path that the engine gives a problem. The problems come from a real
 * store.
 */
class PayloadLocationsTest {

    private static final String JAN_1 = "2026-01-01T00:00:00Z";

    private final ReferenceDomainStore reference = new ReferenceDomainStore();

    /** Reads the payload, runs it dry, and returns its problems located in it; the dry run must reject it. */
    private List<Problem> locatedDryRun(String text) {
        TransactionPayload payload = reference.read(text);
        List<Problem> problems = reference.dryRunProblems(payload.transaction());
        List<Problem> located = payload.locate(problems);
        assertThat(located).extracting(Problem::code).isEqualTo(problems.stream().map(Problem::code).toList());
        assertThat(located).allSatisfy(problem -> assertThat(problem.location()).isPresent());
        return located;
    }

    private static Problem only(List<Problem> problems, ProblemCode code) {
        List<Problem> found = problems.stream().filter(problem -> problem.code() == code).toList();
        assertThat(found).as("problems with the code %s among %s", code, problems).hasSize(1);
        return found.getFirst();
    }

    private static String pointerOf(Problem problem) {
        return problem.location().orElseThrow().pointer();
    }

    @Nested
    class OfTheTransaction {

        @Test
        void aProblemAboutTheTransactionIsLocatedAtTheRootOfThePayload() {
            String text = "  {\n  \"cairn\": 1, \"principal\": \"\", \"writes\": [] }\n";

            Problem problem = locatedDryRun(text).getFirst();

            assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_PRINCIPAL);
            assertThat(problem.path()).isEqualTo("@transaction");
            assertThat(problem.location()).contains(new DocumentLocation("", 1, 3));
        }

        @Test
        void aReusedIdempotencyKeyIsLocatedAtTheRootToo() {
            String first = "{ \"cairn\": 1, \"principal\": \"importer\", \"idempotencyKey\": \"k\", \"writes\": [] }";
            String second = "{ \"cairn\": 1, \"principal\": \"importer\", \"idempotencyKey\": \"k\", \"reason\": \"x\", "
                    + "\"writes\": [] }";
            reference.commit(reference.read(first).transaction());

            TransactionPayload payload = reference.read(second);
            List<Problem> problems = payload.locate(reference.rejectedProblems(payload.transaction()));

            assertThat(problems).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED);
                assertThat(problem.location()).contains(new DocumentLocation("", 1, 1));
            });
        }
    }

    @Nested
    class OfAWrite {

        @Test
        void aWriteWithoutTemporaryIdOrIdIsLocatedByItsPosition() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    "{ \"create\": { \"entityType\": \"Organization\", \"from\": \"2026-02-01T00:00:00Z\", "
                            + "\"until\": \"2026-01-01T00:00:00Z\", \"values\": { \"name\": \"Beta\" } } }");

            List<Problem> problems = locatedDryRun(text);

            Problem problem = only(problems, ProblemCode.INVALID_PERIOD);
            assertThat(problem.path()).isEqualTo("@write.1");
            assertThat(problem.location()).contains(new DocumentLocation("/writes/1", 3, 1));
        }

        @Test
        void anAttributeOfAWriteWithoutTemporaryIdOrIdIsLocatedAtItsKey() {
            String text = payload(
                    "{ \"create\": { \"entityType\": \"Organization\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"name\": \"ACME\", \"lei\": \"bad\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo("@write.0.lei");
            assertThat(problem.location()).contains(locationOf(text, "\"lei\"", "/writes/0/create/values/lei"));
        }

        @Test
        void aMissingAttributeIsLocatedAtTheWriteThatShouldHaveIt() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    "{ \"create\": { \"entityType\": \"Organization\", \"from\": \"" + JAN_1 + "\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.MISSING_REQUIRED_VALUE);

            assertThat(problem.path()).isEqualTo("@write.1.name");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void aRelationshipWithoutTemporaryIdOrIdIsLocatedByItsPositionToo() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    createOrganization("beta", "Beta"),
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"$acme\", \"target\": \"$beta\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 150 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_OUT_OF_RANGE);

            assertThat(problem.path()).isEqualTo("@write.2.share");
            assertThat(problem.location()).contains(locationOf(text, "\"share\"", "/writes/2/relate/values/share"));
        }

        @Test
        void aMultiplicityOfAnEntityWithoutTemporaryIdOrIdIsLocatedAtItsWrite() {
            String text = payload(
                    "{ \"create\": { \"entityType\": \"Account\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"number\": \"A-3\", \"currency\": \"EUR\", \"balance\": 0 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.TOO_FEW_RELATIONSHIPS);

            assertThat(problem.path()).isEqualTo("@write.0[HeldBy]");
            assertThat(pointerOf(problem)).isEqualTo("/writes/0");
        }
    }

    @Nested
    class OfATemporaryId {

        @Test
        void aTemporaryIdIsLocatedAtTheWriteThatCreatesItWhateverWritesRelateItAfterwards() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"2026-02-01T00:00:00Z\", \"until\": \"2026-01-01T00:00:00Z\", "
                            + "\"values\": { \"name\": \"ACME\" } } }",
                    createOrganization("beta", "Beta"),
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"$acme\", \"target\": \"$beta\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 60 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.INVALID_PERIOD);

            assertThat(problem.path()).isEqualTo("$acme");
            assertThat(problem.location()).contains(new DocumentLocation("/writes/0", 2, 1));
        }

        @Test
        void aTemporaryIdIsLocatedAtTheLastWriteThatChangesIt() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    "{ \"endDate\": { \"target\": \"$acme\", \"from\": \"2025-12-01T00:00:00Z\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.ENTITY_NOT_EFFECTIVE);

            assertThat(problem.path()).isEqualTo("$acme");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void aTemporaryIdThatTwoWritesCreateIsLocatedAtTheSecond() {
            String text = payload(createOrganization("acme", "ACME"), createOrganization("acme", "Again"));

            Problem problem = only(locatedDryRun(text), ProblemCode.DUPLICATE_TEMPORARY_ID);

            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void aTemporaryIdNoWriteCreatesIsLocatedAtTheWriteThatUsesIt() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"$acme\", \"target\": \"$ghost\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 60 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.UNKNOWN_TEMPORARY_ID);

            assertThat(problem.path()).isEqualTo("$ghost");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void anAttributeOfATemporaryIdIsLocatedAtItsKey() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"name\": \"ACME\", \"lei\": \"bad\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo("$acme.lei");
            assertThat(problem.location()).contains(locationOf(text, "\"lei\"", "/writes/0/create/values/lei"));
        }

        @Test
        void anAttributeIsLocatedAtItsKeyInTheCreateWhenLaterWritesRelateOrPatchOthers() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"name\": \"ACME\", \"lei\": \"bad\" } } }",
                    createOrganization("beta", "Beta"),
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"$acme\", \"target\": \"$beta\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 60 } } }",
                    "{ \"patch\": { \"target\": \"$acme\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"name\": \"ACME Holdings\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo("$acme.lei");
            assertThat(pointerOf(problem)).isEqualTo("/writes/0/create/values/lei");
        }

        @Test
        void anAttributeIsLocatedAtItsKeyInTheLastWriteThatSetsIt() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"name\": \"ACME\", \"lei\": \"bad\" } } }",
                    "{ \"patch\": { \"target\": \"$acme\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"lei\": \"still bad\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo("$acme.lei");
            assertThat(problem.found()).contains("still bad");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1/patch/set/lei");
        }

        @Test
        void anAttributeNameInAnotherCaseIsLocatedAtTheKeyAsWritten() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"name\": \"ACME\", \"LEI\": \"bad\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo("$acme.lei");
            assertThat(problem.location()).contains(locationOf(text, "\"LEI\"", "/writes/0/create/values/LEI"));
        }

        @Test
        void anUnknownAttributeIsLocatedAtItsKeyAsWritten() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"entityType\": \"Organization\", "
                            + "\"from\": \"" + JAN_1 + "\", \"values\": { \"name\": \"ACME\", \"nmae\": \"x\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.UNKNOWN_ATTRIBUTE);

            assertThat(problem.path()).isEqualTo("$acme.nmae");
            assertThat(problem.location()).contains(locationOf(text, "\"nmae\"", "/writes/0/create/values/nmae"));
        }

        @Test
        void aRequiredAttributeThatAReplaceLeavesOutIsLocatedAtTheReplace() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    "{ \"replace\": { \"target\": \"$acme\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"lei\": \"5493001KJTIIGC8Y1R12\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.MISSING_REQUIRED_VALUE);

            assertThat(problem.path()).isEqualTo("$acme.name");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void aProblemOfARuleIsLocatedAtTheAttributeItNames() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"gamma\", \"entityType\": \"Organization\", "
                            + "\"from\": \"2026-03-01T00:00:00Z\", \"values\": { \"name\": \"Gamma\", "
                            + "\"lei\": \"" + ReferenceStories.LEI_WRONG_CHECK_DIGITS + "\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.RULE_VIOLATION);

            assertThat(problem.path()).isEqualTo("$gamma.lei");
            assertThat(problem.location()).contains(locationOf(text, "\"lei\"", "/writes/0/create/values/lei"));
        }

        @Test
        void aProblemOfARuleAboutARelationshipIsLocatedAtTheWriteThatRelatesIt() {
            Receipt parties = reference.replay(ReferenceStories.rd1Parties());
            reference.replay(ReferenceStories.rd2Ownership(
                    parties.temporaryIds().get(ACME), parties.temporaryIds().get(BETA)));
            String beta = parties.temporaryIds().get(BETA).toString();
            String text = payload(
                    createOrganization("gamma", "Gamma"),
                    "{ \"relate\": { \"temporaryId\": \"gammaOwnsBeta\", \"relationshipType\": \"Owns\", "
                            + "\"source\": \"$gamma\", \"target\": \"" + beta + "\", "
                            + "\"from\": \"2026-04-01T00:00:00Z\", \"values\": { \"share\": 50 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.RULE_VIOLATION);

            assertThat(problem.path()).isEqualTo("$gammaOwnsBeta");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void aMultiplicityIsLocatedLikeTheEntityBeforeTheBracket() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"a3\", \"entityType\": \"Account\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"number\": \"A-3\", \"currency\": \"EUR\", \"balance\": 0 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.TOO_FEW_RELATIONSHIPS);

            assertThat(problem.path()).isEqualTo("$a3[HeldBy]");
            assertThat(pointerOf(problem)).isEqualTo("/writes/0");
        }

        @Test
        void aDuplicateRelationshipIsLocatedAtTheWriteThatRelatesIt() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    createOrganization("beta", "Beta"),
                    "{ \"relate\": { \"temporaryId\": \"r1\", \"relationshipType\": \"Owns\", \"source\": \"$acme\", "
                            + "\"target\": \"$beta\", \"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 10 } } }",
                    "{ \"relate\": { \"temporaryId\": \"r2\", \"relationshipType\": \"Owns\", \"source\": \"$acme\", "
                            + "\"target\": \"$beta\", \"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 20 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.DUPLICATE_RELATIONSHIP);

            assertThat(problem.path()).isIn("$r1", "$r2");
            assertThat(pointerOf(problem)).isEqualTo(problem.path().equals("$r1") ? "/writes/2" : "/writes/3");
        }

        @Test
        void aTemporaryIdAndTheIdTheSameWriteSuppliesNameOneSubject() {
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"acme\", \"id\": \"00000000-0000-7000-8000-0000000000aa\", "
                            + "\"entityType\": \"Organization\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"name\": \"ACME\", \"lei\": \"bad\" } } }",
                    "{ \"patch\": { \"target\": \"00000000-0000-7000-8000-0000000000aa\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"name\": \"ACME Holdings\" } } }");
            TransactionPayload payload = reference.read(text);

            assertThat(payload.locations().find("$acme.lei")).hasValueSatisfying(at ->
                    assertThat(at.pointer()).isEqualTo("/writes/0/create/values/lei"));
            assertThat(payload.locations().find("00000000-0000-7000-8000-0000000000aa.lei")).hasValueSatisfying(at ->
                    assertThat(at.pointer()).isEqualTo("/writes/0/create/values/lei"));
            assertThat(payload.locations().find("$acme")).hasValueSatisfying(at ->
                    assertThat(at.pointer()).isEqualTo("/writes/1"));
        }
    }

    @Nested
    class OfAnId {

        private Receipt parties;

        private String id(TemporaryId temporaryId) {
            return parties.temporaryIds().get(temporaryId).toString();
        }

        private void createParties() {
            parties = reference.replay(ReferenceStories.rd1Parties());
        }

        @Test
        void anIdIsLocatedAtTheLastWriteThatChangesIt() {
            createParties();
            String alice = id(ALICE);
            String text = payload(
                    createOrganization("beta2", "Beta Two"),
                    "{ \"patch\": { \"target\": \"" + alice + "\", \"from\": \"2025-01-01T00:00:00Z\", "
                            + "\"set\": { \"birthDate\": \"1990-05-17\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.ENTITY_NOT_EFFECTIVE);

            assertThat(problem.path()).isEqualTo(alice);
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void anIdThatNoEntityHasIsLocatedAtTheWriteThatNamesIt() {
            createParties();
            String text = payload(
                    createOrganization("beta2", "Beta Two"),
                    "{ \"endDate\": { \"target\": \"nobody\", \"from\": \"2026-03-01T00:00:00Z\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.UNKNOWN_ENTITY);

            assertThat(problem.path()).isEqualTo("nobody");
            assertThat(pointerOf(problem)).isEqualTo("/writes/1");
        }

        @Test
        void anAttributeOfAnIdIsLocatedAtItsKeyInTheSetOfAPatch() {
            createParties();
            String acme = id(ACME);
            String text = payload(
                    "{ \"patch\": { \"target\": \"" + acme + "\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"name\": \"ACME Holdings\", \"lei\": \"bad\" } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.VALUE_PATTERN_MISMATCH);

            assertThat(problem.path()).isEqualTo(acme + ".lei");
            assertThat(problem.location()).contains(locationOf(text, "\"lei\"", "/writes/0/patch/set/lei"));
        }

        @Test
        void anAttributeThatAPatchClearsIsLocatedAtItsNameInTheListToClear() {
            createParties();
            String acme = id(ACME);
            String text = payload(
                    "{ \"patch\": { \"target\": \"" + acme + "\", \"from\": \"" + JAN_1 + "\", "
                            + "\"clear\": [\"lei\", \"NAME\"] } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.MISSING_REQUIRED_VALUE);

            assertThat(problem.path()).isEqualTo(acme + ".name");
            assertThat(problem.location()).contains(locationOf(text, "\"NAME\"", "/writes/0/patch/clear/1"));
        }

        @Test
        void aConflictOfAnAttributeIsLocatedAtItsKey() {
            createParties();
            String acme = id(ACME);
            reference.replay(ReferenceStories.rd9AcmeLei(parties.temporaryIds().get(ACME)));
            String text = payload(
                    "{ \"patch\": { \"target\": \"" + acme + "\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"name\": \"ACME Holdings\", \"lei\": \"5493001KJTIIGC8Y1R12\" }, "
                            + "\"conflictCheck\": { \"perAttribute\": 2 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.CONFLICT);

            assertThat(problem.path()).isEqualTo(acme + ".lei");
            assertThat(problem.location()).contains(locationOf(text, "\"lei\"", "/writes/0/patch/set/lei"));
        }

        @Test
        void aConflictOfAWholeEntityIsLocatedAtTheWrite() {
            createParties();
            String acme = id(ACME);
            reference.replay(ReferenceStories.rd9AcmeLei(parties.temporaryIds().get(ACME)));
            String text = payload(
                    "{ \"replace\": { \"target\": \"" + acme + "\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"name\": \"ACME Holdings\" }, \"conflictCheck\": { \"perEntity\": 2 } } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.CONFLICT);

            assertThat(problem.path()).isEqualTo(acme);
            assertThat(pointerOf(problem)).isEqualTo("/writes/0");
        }

        @Test
        void aMultiplicityOfAnIdIsLocatedLikeTheEntityIfThePayloadNamesIt() {
            createParties();
            Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                    parties.temporaryIds().get(BETA), parties.temporaryIds().get(ACME)));
            String a1 = accounts.temporaryIds().get(ReferenceStories.A1).toString();
            String text = payload(
                    "{ \"patch\": { \"target\": \"" + a1 + "\", \"from\": \"" + JAN_1 + "\", "
                            + "\"set\": { \"balance\": 1 } } }",
                    "{ \"endDate\": { \"target\": \"" + accounts.temporaryIds().get(ReferenceStories.HELD_BY_A1)
                            + "\", \"from\": \"2026-06-01T00:00:00Z\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.TOO_FEW_RELATIONSHIPS);

            assertThat(problem.path()).isEqualTo(a1 + "[HeldBy]");
            assertThat(pointerOf(problem)).isEqualTo("/writes/0");
        }

        @Test
        void aMultiplicityOfAnIdTheMessageDoesNotLeadToAWriteIsLocatedAtTheRoot() {
            createParties();
            Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                    parties.temporaryIds().get(BETA), parties.temporaryIds().get(ACME)));
            String a1 = accounts.temporaryIds().get(ReferenceStories.A1).toString();
            // The payload names the relationship that holds the account, not the account, and the message names the account.
            String text = payload(
                    "{ \"endDate\": { \"target\": \"" + accounts.temporaryIds().get(ReferenceStories.HELD_BY_A1)
                            + "\", \"from\": \"2026-06-01T00:00:00Z\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.TOO_FEW_RELATIONSHIPS);

            assertThat(problem.path()).isEqualTo(a1 + "[HeldBy]");
            assertThat(problem.location()).contains(new DocumentLocation("", 1, 1));
        }
    }

    @Nested
    class OfSomethingThePayloadDoesNotName {

        @Test
        void aRelationshipThatOutlivesAnEntityTheWriteEndsIsLocatedAtTheWriteThatEndsTheEntity() {
            Receipt parties = reference.replay(ReferenceStories.rd1Parties());
            Receipt accounts = reference.replay(ReferenceStories.rd5Accounts(
                    parties.temporaryIds().get(BETA), parties.temporaryIds().get(ACME)));
            String beta = parties.temporaryIds().get(BETA).toString();
            String heldBy = accounts.temporaryIds().get(ReferenceStories.HELD_BY_A1).toString();
            String text = payload(
                    createOrganization("gamma", "Gamma"),
                    "{ \"endDate\": { \"target\": \"" + beta + "\", \"from\": \"2026-06-01T00:00:00Z\" } }");

            Problem problem = only(locatedDryRun(text), ProblemCode.RELATIONSHIP_OUTLIVES_END);

            assertThat(problem.path()).isEqualTo(heldBy);
            assertThat(problem.message()).contains(beta);
            assertThat(problem.location()).contains(new DocumentLocation("/writes/1", 3, 1));
        }

        @Test
        void theSameProblemsAreLocatedInTheSamePlacesInADryRunAndInACommit() {
            Receipt parties = reference.replay(ReferenceStories.rd1Parties());
            reference.replay(ReferenceStories.rd5Accounts(
                    parties.temporaryIds().get(BETA), parties.temporaryIds().get(ACME)));
            String text = payload(
                    createOrganization("gamma", "Gamma"),
                    "{ \"endDate\": { \"target\": \"" + parties.temporaryIds().get(BETA)
                            + "\", \"from\": \"2026-06-01T00:00:00Z\" } }");
            TransactionPayload payload = reference.read(text);

            List<Problem> dryRun = payload.locate(reference.dryRunProblems(payload.transaction()));
            List<Problem> commit = payload.locate(reference.rejectedProblems(payload.transaction()));

            assertThat(dryRun).isNotEmpty();
            assertThat(pointers(commit)).isEqualTo(pointers(dryRun));
            assertThat(commit).extracting(Problem::code).isEqualTo(dryRun.stream().map(Problem::code).toList());
        }

        @Test
        void aProblemThatNamesNothingOfThePayloadIsLocatedAtTheRoot() {
            TransactionPayload payload = reference.read(payload(createOrganization("acme", "ACME")));
            Problem problem = new Problem(ProblemCode.CONFLICT, "somewhere-else", "Nothing here is named.",
                    Optional.of("no change"), Optional.of("a change"), Optional.empty());

            assertThat(payload.locate(List.of(problem))).singleElement().satisfies(located ->
                    assertThat(located.location()).contains(new DocumentLocation("", 1, 1)));
        }

        @Test
        void aNameIsFoundInTheMessageTheFoundValueOrTheExpectedValueButOnlyAsAWordOfItsOwn() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    createOrganization("acmeCorp", "ACME Corp"),
                    createOrganization("beta", "Beta"));
            TransactionPayload payload = reference.read(text);

            Problem inMessage = new Problem(ProblemCode.CONFLICT, "elsewhere", "It concerns $acme, and more.");
            Problem inFound = new Problem(ProblemCode.CONFLICT, "elsewhere", "No names.", Optional.empty(),
                    Optional.of("also $acme"), Optional.empty());
            Problem inExpected = new Problem(ProblemCode.CONFLICT, "elsewhere", "No names.",
                    Optional.of("like $beta"), Optional.empty(), Optional.empty());
            Problem partOfAName = new Problem(ProblemCode.CONFLICT, "elsewhere", "It concerns $acmeCor and $acm.");

            assertThat(pointers(payload.locate(List.of(inMessage, inFound, inExpected, partOfAName))))
                    .containsExactly("/writes/0", "/writes/0", "/writes/2", "");
        }

        @Test
        void theLastWriteOfSeveralNamedSubjectsIsTheOneThatIsUsed() {
            String text = payload(
                    createOrganization("acme", "ACME"),
                    createOrganization("beta", "Beta"),
                    createOrganization("gamma", "Gamma"));
            TransactionPayload payload = reference.read(text);

            Problem problem = new Problem(ProblemCode.CONFLICT, "elsewhere", "$beta and $acme differ.");

            assertThat(pointers(payload.locate(List.of(problem)))).containsExactly("/writes/1");
        }

        @Test
        void aPositionInTheMessageNamesTheWriteAtThatPosition() {
            String text = payload(createOrganization("acme", "ACME"), createOrganization("beta", "Beta"));
            TransactionPayload payload = reference.read(text);

            Problem problem = new Problem(ProblemCode.DUPLICATE_RELATIONSHIP, "elsewhere", "It duplicates @write.1 from "
                    + "January.");
            Problem beyond = new Problem(ProblemCode.DUPLICATE_RELATIONSHIP, "elsewhere", "It duplicates @write.9.");

            assertThat(pointers(payload.locate(List.of(problem, beyond)))).containsExactly("/writes/1", "");
        }

        @Test
        void aRelationshipThatOnlyMentionsAnEntityAsAnEndIsALastResortForTheEntity() {
            Receipt parties = reference.replay(ReferenceStories.rd1Parties());
            String acme = parties.temporaryIds().get(ACME).toString();
            String text = payload(
                    createOrganization("beta", "Beta"),
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"" + acme + "\", "
                            + "\"target\": \"$beta\", \"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 10 } } }",
                    "{ \"relate\": { \"relationshipType\": \"Owns\", \"source\": \"" + acme + "\", "
                            + "\"target\": \"$beta\", \"from\": \"" + JAN_1 + "\", \"values\": { \"share\": 10 } } }");
            TransactionPayload payload = reference.read(text);

            assertThat(payload.locations().find(acme)).hasValueSatisfying(at ->
                    assertThat(at.pointer()).isEqualTo("/writes/2"));
            assertThat(payload.locations().find(acme + ".name")).hasValueSatisfying(at ->
                    assertThat(at.pointer()).isEqualTo("/writes/2"));
        }
    }

    @Nested
    class TheLocationsThemselves {

        private final String text = payload(createOrganization("acme", "ACME"), createOrganization("beta", "Beta"));
        private final PayloadLocations locations = reference.read(text).locations();

        @Test
        void theRootAndTheWritesAreFoundByPosition() {
            assertThat(locations.root()).isEqualTo(new DocumentLocation("", 1, 1));
            assertThat(locations.write(0)).contains(new DocumentLocation("/writes/0", 2, 1));
            assertThat(locations.write(1)).contains(new DocumentLocation("/writes/1", 3, 1));
            assertThat(locations.write(2)).isEmpty();
            assertThat(locations.write(-1)).isEmpty();
        }

        @Test
        void aPathThatNamesNothingIsFoundNowhereAndLocatedAtTheRoot() {
            assertThat(locations.find("$ghost")).isEmpty();
            assertThat(locations.find("@write.2")).isEmpty();
            assertThat(locations.find("@write.x")).isEmpty();
            assertThat(locations.find("@write.1x")).isEmpty();
            assertThat(locations.find("@write.1234567890123")).isEmpty();
            assertThat(locations.of("$ghost")).isEqualTo(locations.root());
        }

        @Test
        void thePathsOfWritesAreFoundWithAndWithoutAnAttribute() {
            assertThat(locations.find("@transaction")).contains(locations.root());
            assertThat(locations.find("@write.1")).contains(new DocumentLocation("/writes/1", 3, 1));
            assertThat(locations.find("@write.1[HeldBy]")).contains(new DocumentLocation("/writes/1", 3, 1));
            assertThat(locations.find("@write.1.name").map(DocumentLocation::pointer))
                    .contains("/writes/1/create/values/name");
            assertThat(locations.find("@write.1.NAME").map(DocumentLocation::pointer))
                    .contains("/writes/1/create/values/name");
            assertThat(locations.find("@write.1.lei").map(DocumentLocation::pointer)).contains("/writes/1");
        }

        @Test
        void aProblemThatIsLocatedAlreadyKeepsItsLocation() {
            Problem located = new Problem(ProblemCode.CONFLICT, "$acme", "x").at(new DocumentLocation("/elsewhere", 9, 9));

            assertThat(locations.locate(located)).isSameAs(located);
            assertThat(locations.locate(List.of(located))).containsExactly(located);
        }

        @Test
        void locationsOfTheSameTextAreEqual() {
            assertThat(reference.read(text).locations()).isEqualTo(locations).hasSameHashCodeAs(locations);
            assertThat(reference.read(payload(createOrganization("acme", "ACME"))).locations()).isNotEqualTo(locations);
            assertThat(locations.toString()).startsWith("PayloadLocations").contains("/writes/1");
        }
    }

    @Nested
    class OfAttributesByAlias {

        private final SchemaDefinition things = SchemaDefinition.builder()
                .entityType("Thing", e -> e.attribute("text", STRING, a -> a.required().aliases("label")))
                .build();

        @Test
        void anAttributeWrittenUnderAnAliasIsLocatedAtTheKeyAsWrittenWhereTheEngineNamesItByItsName() {
            Store store = Stores.inMemory(StoreSettings.defaults());
            assertThat(store.schema().register(new Origin("things", "1.0.0"), things))
                    .isInstanceOf(RegistrationResult.Applied.class);
            String text = payload(
                    "{ \"create\": { \"temporaryId\": \"t\", \"entityType\": \"Thing\", \"from\": \"" + JAN_1 + "\", "
                            + "\"values\": { \"Label\": 5 } } }");
            TransactionPayload payload = read(text, store.schema().current());

            DryRunResult result = store.dryRun(payload.transaction());

            assertThat(result).isInstanceOf(DryRunResult.Rejected.class);
            List<Problem> located = payload.locate(((DryRunResult.Rejected) result).problems());
            assertThat(located).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.VALUE_TYPE_MISMATCH);
                assertThat(problem.path()).isEqualTo("$t.text");
                assertThat(problem.location()).contains(locationOf(text, "\"Label\"", "/writes/0/create/values/Label"));
            });
            assertThat(store.commit(payload.transaction())).isInstanceOf(CommitResult.Rejected.class);
        }
    }
}

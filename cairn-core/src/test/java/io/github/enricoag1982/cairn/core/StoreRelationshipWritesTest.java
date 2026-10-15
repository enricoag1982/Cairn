package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.assertProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationship;
import static io.github.enricoag1982.cairn.core.ReferenceStore.relationshipId;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.GAMMA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Design D1 and D2 of {@code add-relationships}: how a store relates entities, and how relate, patch, replace,
 * end-date and conflict checks work on relationships as they do on entities, with the IDs and temporary IDs that
 * entities and relationships share.
 */
class StoreRelationshipWritesTest {

    private static final TemporaryId OWNS = new TemporaryId("owns");
    private static final TemporaryId EMPLOYS = new TemporaryId("employs");

    private final ReferenceStore reference = new ReferenceStore();
    private Receipt parties;
    private EntityId acme;
    private EntityId beta;
    private EntityId alice;

    @BeforeEach
    void theParties() {
        parties = reference.createParties();
        acme = entity(parties, ACME);
        beta = entity(parties, BETA);
        alice = entity(parties, ALICE);
    }

    private static Problem only(List<Problem> problems) {
        assertThat(problems).hasSize(1);
        return problems.getFirst();
    }

    /** ACME owns Beta, with a share of 60, from 1 February. */
    private RelationshipId acmeOwnsBeta() {
        return relationship(reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build()), OWNS);
    }

    // ------------------------------------------------------------------ relating

    @Test
    void relatingCreatesARelationshipWhoseIdIsInTheReceipt() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        assertThat(receipt.created()).containsExactly(relationshipId(4));
        assertThat(receipt.temporaryIds()).containsEntry(OWNS, relationshipId(4));
    }

    @Test
    void entitiesAndRelationshipsDrawTheirIdsFromTheSameGeneratorInTheOrderOfTheWrites() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", GAMMA, beta, from(2, 1), values("share", 10))
                .create("Organization", from(1, 1), values("name", "Delta"))
                .build());

        assertThat(receipt.created()).containsExactly(id(4), relationshipId(5), id(6));
        assertThat(receipt.temporaryIds()).containsEntry(GAMMA, id(4)).containsEntry(OWNS, relationshipId(5));
    }

    @Test
    void theEndsAreGivenByIdsOrByTemporaryIdsOfEntitiesCreatedInTheSameTransaction() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", acme, GAMMA, from(2, 1), values("share", 10))
                .relate(EMPLOYS, "Employs", GAMMA, alice, from(2, 1), values("title", "Analyst"))
                .build());

        assertThat(receipt.created()).hasSize(3);
    }

    @Test
    void aRelationshipTypeIsFoundByAliasAndWithoutRegardToCase() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(OWNS, "oWnS", acme, beta, from(2, 1), values("share", 60)).build());

        assertThat(receipt.created()).hasSize(1);
    }

    @Test
    void aMisspelledRelationshipTypeIsReportedWithAHint() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Own", acme, beta, from(2, 1), values("share", 60)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "$owns");
        assertThat(problem.found()).contains("Own");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Owns"));
    }

    @Test
    void aRelationshipThatCannotBeCreatedDoesNotMakeItsEndsReportTheRelationshipsTheyMiss() {
        TemporaryId a3 = new TemporaryId("a3");

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(a3, "Account", from(1, 1),
                        values("number", "A-3", "currency", "EUR", "balance", new BigDecimal("1")))
                .relate("Hold", a3, beta, from(1, 1), values()).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "@write.1");
    }

    @Test
    void aPeriodThatDoesNotEndAfterItStartsIsReported() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("Owns", acme, beta, EffectivePeriod.between(on(3, 1), on(2, 1)), values("share", 60))
                .build());

        assertProblem(only(problems), ProblemCode.INVALID_PERIOD, "@write.0");
    }

    @Test
    void aRelationshipHasAClosedPeriodIfItIsGiven() {
        RelationshipId owns = relationship(reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, EffectivePeriod.between(on(2, 1), on(5, 1)),
                        values("share", 60)).build()), OWNS);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(owns, from(5, 1), values("share", 70)).build());

        assertProblem(only(problems), ProblemCode.ENTITY_NOT_EFFECTIVE, owns.value());
    }

    @Test
    void anEntityThatIsOnlyTheEndOfARelationshipIsNotWrittenAgain() {
        acmeOwnsBeta();

        assertThat(reference.view(on(3, 1)).timeline(acme)).singleElement().satisfies(state ->
                assertThat(state.recordedBasis()).isEqualTo(parties.basis()));
        assertThat(reference.view(on(3, 1)).timeline(beta)).singleElement().satisfies(state ->
                assertThat(state.recordedBasis()).isEqualTo(parties.basis()));
    }

    @Test
    void aRetryOfATransactionThatRelatesGetsTheFirstReceipt() {
        Transaction transaction = Transaction.by("importer").idempotencyKey("owns-1")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build();

        Receipt first = reference.commit(transaction);
        Receipt second = reference.commit(transaction);

        assertThat(second).isEqualTo(first);
        assertThat(reference.store.latestBasis()).isEqualTo(first.basis());
    }

    // ------------------------------------------------------------------ relationship ends that are not entities

    @Test
    void anEndGivenByTheIdOfARelationshipIsNotAnEntity() {
        RelationshipId owns = acmeOwnsBeta();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("Owns", owns, beta, from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.WRONG_END_TYPE, "@write.0");
    }

    @Test
    void anEndGivenByTheTemporaryIdOfARelationshipIsNotAnEntity() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60))
                .relate("Owns", acme, OWNS, from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.WRONG_END_TYPE, "@write.1");
    }

    @Test
    void anEndWithAnIdThatNoEntityHasIsUnknown() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("Owns", acme, id(99), from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_ENTITY, id(99).value());
    }

    @Test
    void anEndWithATemporaryIdThatNoEarlierWriteCreatesIsUnknown() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate("Owns", acme, GAMMA, from(2, 1), values("share", 60))
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma")).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_TEMPORARY_ID, "$gamma");
    }

    // ------------------------------------------------------------------ IDs

    @Test
    void aSuppliedRelationshipIdIsUsed() {
        RelationshipId supplied = new RelationshipId("00000000-0000-7000-8000-0000000000aa");

        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(supplied, "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        assertThat(receipt.created()).containsExactly(supplied);
    }

    @Test
    void aSuppliedRelationshipIdMustHaveTheFormatOfTheGenerator() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(new RelationshipId("owns-1"), "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.INVALID_ENTITY_ID, "owns-1");
    }

    @Test
    void aSuppliedRelationshipIdThatARelationshipHasIsRejected() {
        RelationshipId owns = acmeOwnsBeta();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(owns, "Employs", beta, alice, from(3, 1), values()).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.ENTITY_EXISTS, owns.value());
        assertThat(problem.message()).contains("relationship");
    }

    @Test
    void aSuppliedRelationshipIdThatAnEntityHasIsRejected() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(new RelationshipId(acme.value()), "Owns", acme, beta, from(2, 1), values("share", 60))
                .build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.ENTITY_EXISTS, acme.value());
        assertThat(problem.message()).contains("entity");
    }

    @Test
    void aSuppliedEntityIdThatARelationshipHasIsRejected() {
        RelationshipId owns = acmeOwnsBeta();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(new EntityId(owns.value()), "Organization", from(1, 1), values("name", "Gamma")).build());

        assertProblem(only(problems), ProblemCode.ENTITY_EXISTS, owns.value());
    }

    @Test
    void aSuppliedIdThatAnEarlierWriteOfTheTransactionUsesIsRejectedWhateverTheKind() {
        String text = "00000000-0000-7000-8000-0000000000bb";

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(new EntityId(text), "Organization", from(1, 1), values("name", "Gamma"))
                .relate(new RelationshipId(text), "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.ENTITY_EXISTS, text);
        assertThat(problem.message()).contains("twice");
    }

    @Test
    void aGeneratedIdIsNeverOneThatTheStoreOrTheTransactionAlreadyHas() {
        RelationshipId supplied = relationshipId(4);
        reference.commit(Transaction.by("importer")
                .relate(supplied, "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        Receipt receipt = reference.commit(Transaction.by("importer")
                .create("Organization", from(1, 1), values("name", "Gamma")).build());

        assertThat(receipt.created()).as("the generator's fourth ID is a relationship's").containsExactly(id(5));
    }

    // ------------------------------------------------------------------ temporary IDs

    @Test
    void entitiesAndRelationshipsShareTheirTemporaryIds() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .create(OWNS, "Organization", from(1, 1), values("name", "Gamma"))
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.DUPLICATE_TEMPORARY_ID, "$owns");
    }

    @Test
    void aTemporaryIdOfARelationshipIsUsableByLaterWritesOnly() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 60))
                .patch(OWNS, from(3, 1), values("share", 55)).build());
        assertThat(receipt.temporaryIds()).containsKey(OWNS);

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(OWNS, from(3, 1), values("share", 55))
                .relate(OWNS, "Owns", acme, alice, from(2, 1), values("share", 60)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_TEMPORARY_ID, "$owns");
    }

    @Test
    void aRelationshipThatCouldNotBeCreatedStillHoldsItsTemporaryIdForLaterWrites() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Own", acme, beta, from(2, 1), values("share", 60))
                .patch(OWNS, from(3, 1), values("share", 55)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_RELATIONSHIP_TYPE, "$owns");
    }

    // ------------------------------------------------------------------ attribute values

    @Test
    void aRelationshipWithoutItsRequiredAttributeIsRejected() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values()).build());

        assertProblem(only(problems), ProblemCode.MISSING_REQUIRED_VALUE, "$owns.share");
    }

    @Test
    void aRelationshipValueOutsideTheRangeIsReported() {
        for (Object share : new Object[] {0, new BigDecimal("100.01"), -5}) {
            List<Problem> problems = reference.reject(Transaction.by("importer")
                    .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", share)).build());

            assertProblem(only(problems), ProblemCode.VALUE_OUT_OF_RANGE, "$owns.share");
        }
        reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", 100)).build());
    }

    @Test
    void aRelationshipValueOfTheWrongTypeIsReported() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("share", "most")).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.VALUE_TYPE_MISMATCH, "$owns.share");
        assertThat(problem.expected()).hasValueSatisfying(expected -> assertThat(expected).contains("decimal"));
    }

    @Test
    void anUnknownRelationshipAttributeIsReportedWithAHintAndDoesNotAlsoReportAMissingOne() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, from(2, 1), values("shre", 60)).build());

        Problem problem = only(problems);
        assertProblem(problem, ProblemCode.UNKNOWN_ATTRIBUTE, "$owns.shre");
        assertThat(problem.message()).contains("relationship type Owns");
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("share"));
    }

    @Test
    void aProblemAboutARelationshipWithoutAnyIdIsLocatedAtThePositionOfItsWrite() {
        Transaction invalid = Transaction.by("importer")
                .create(GAMMA, "Organization", from(1, 1), values("name", "Gamma"))
                .relate("Owns", acme, GAMMA, from(2, 1), values())
                .build();

        List<Problem> committing = reference.reject(invalid);

        assertProblem(only(committing), ProblemCode.MISSING_REQUIRED_VALUE, "@write.1.share");
    }

    @Test
    void anOptionalRelationshipAttributeMayBeLeftOut() {
        Receipt receipt = reference.commit(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", acme, alice, from(3, 1), values()).build());

        assertThat(receipt.created()).hasSize(1);
    }

    // ------------------------------------------------------------------ patch

    @Test
    void aPatchOfARelationshipIsValidatedLikeOneOfAnEntity() {
        RelationshipId owns = acmeOwnsBeta();

        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(owns, from(3, 1), values("share", 101)).build())),
                ProblemCode.VALUE_OUT_OF_RANGE, owns.value() + ".share");
        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(owns, from(3, 1), values("shre", 55)).build())),
                ProblemCode.UNKNOWN_ATTRIBUTE, owns.value() + ".shre");
        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(owns, from(3, 1), values(), Set.of("share"), ConflictCheck.none()).build())),
                ProblemCode.MISSING_REQUIRED_VALUE, owns.value() + ".share");
        reference.commit(Transaction.by("importer").patch(owns, from(3, 1), values("share", 55)).build());
    }

    @Test
    void aPatchOfARelationshipCannotExtendItsLifetime() {
        RelationshipId owns = acmeOwnsBeta();

        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(owns, from(1, 15), values("share", 55)).build());

        assertProblem(only(problems), ProblemCode.ENTITY_NOT_EFFECTIVE, owns.value());
    }

    @Test
    void aPatchOfARelationshipThatNoRelationshipHasIsReported() {
        List<Problem> problems = reference.reject(Transaction.by("importer")
                .patch(relationshipId(99), from(3, 1), values("share", 55)).build());

        assertProblem(only(problems), ProblemCode.UNKNOWN_RELATIONSHIP, relationshipId(99).value());
    }

    @Test
    void anIdIsOfOneKindOnly() {
        RelationshipId owns = acmeOwnsBeta();

        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(new EntityId(owns.value()), from(3, 1), values("name", "x")).build())),
                ProblemCode.UNKNOWN_ENTITY, owns.value());
        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(new RelationshipId(acme.value()), from(3, 1), values("share", 55)).build())),
                ProblemCode.UNKNOWN_RELATIONSHIP, acme.value());
    }

    // ------------------------------------------------------------------ replace

    @Test
    void aReplaceOfARelationshipSetsItsCompleteState() {
        RelationshipId owns = acmeOwnsBeta();
        RelationshipId employs = relationship(reference.commit(Transaction.by("importer")
                .relate(EMPLOYS, "Employs", acme, alice, from(3, 1), values("title", "Analyst")).build()), EMPLOYS);

        assertProblem(only(reference.reject(Transaction.by("importer")
                .replace(owns, from(3, 1), values()).build())),
                ProblemCode.MISSING_REQUIRED_VALUE, owns.value() + ".share");
        reference.commit(Transaction.by("importer").replace(employs, from(4, 1), values()).build());
        reference.commit(Transaction.by("importer").replace(owns, from(3, 1), values("share", 70)).build());
    }

    // ------------------------------------------------------------------ end-date

    @Test
    void anEndDatedRelationshipCannotBeChangedAfterItsEnd() {
        RelationshipId owns = acmeOwnsBeta();
        reference.commit(Transaction.by("importer").endDate(owns, on(6, 1)).build());

        assertProblem(only(reference.reject(Transaction.by("importer")
                .patch(owns, from(7, 1), values("share", 55)).build())),
                ProblemCode.ENTITY_NOT_EFFECTIVE, owns.value());
        reference.commit(Transaction.by("importer")
                .patch(owns, EffectivePeriod.between(on(5, 1), on(6, 1)), values("share", 55)).build());
    }

    @Test
    void aRelationshipCannotBeEndedAtOrBeforeItsStart() {
        RelationshipId owns = acmeOwnsBeta();

        Problem problem = only(reference.reject(Transaction.by("importer").endDate(owns, on(2, 1)).build()));

        assertProblem(problem, ProblemCode.ENTITY_NOT_EFFECTIVE, owns.value());
        assertThat(problem.message()).contains("relationship");
    }

    @Test
    void endingARelationshipAtOrAfterItsEndChangesNothing() {
        RelationshipId owns = relationship(reference.commit(Transaction.by("importer")
                .relate(OWNS, "Owns", acme, beta, EffectivePeriod.between(on(2, 1), on(5, 1)),
                        values("share", 60)).build()), OWNS);

        CommitResult result = reference.store.commit(Transaction.by("importer").endDate(owns, on(8, 1)).build());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    void endingARelationshipDoesNotEndItsEnds() {
        RelationshipId owns = acmeOwnsBeta();

        reference.commit(Transaction.by("importer").endDate(owns, on(6, 1)).build());

        assertThat(reference.view(on(7, 1)).entity(acme)).isPresent();
        assertThat(reference.view(on(7, 1)).entity(beta)).isPresent();
    }

    // ------------------------------------------------------------------ conflict checks

    @Test
    void aConflictCheckOfARelationshipComparesItsStateAtTheBasisItWasRead() {
        RelationshipId owns = acmeOwnsBeta();
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("first").patch(owns, from(3, 1), values("share", 55)).build());

        List<Problem> perAttribute = reference.reject(Transaction.by("second")
                .patch(owns, from(3, 1), values("share", 50), ConflictCheck.perAttribute(readAt)).build());
        List<Problem> perEntity = reference.reject(Transaction.by("second")
                .endDate(owns, on(6, 1), ConflictCheck.perEntity(readAt)).build());

        assertProblem(only(perAttribute), ProblemCode.CONFLICT, owns.value() + ".share");
        assertProblem(only(perEntity), ProblemCode.CONFLICT, owns.value());
        reference.commit(Transaction.by("second").patch(owns, from(3, 1), values("share", 50),
                ConflictCheck.perAttribute(reference.store.latestBasis())).build());
    }

    @Test
    void aRelationshipWithoutAnyChangeSinceItWasReadHasNoConflict() {
        RelationshipId owns = acmeOwnsBeta();
        Basis readAt = reference.store.latestBasis();
        reference.clock.advance(Duration.ofMinutes(1));
        reference.commit(Transaction.by("first").create("Organization", from(1, 1), values("name", "Gamma")).build());

        reference.commit(Transaction.by("second").patch(owns, from(3, 1), values("share", 50),
                ConflictCheck.perEntity(readAt)).build());
    }

    // ------------------------------------------------------------------ unique attributes of relationships

    private static SchemaDefinition links() {
        return SchemaDefinition.builder()
                .entityType("Node", node -> node.attribute("ref", ValueType.STRING, a -> a.unique()))
                .relationshipType("Link", link -> link.from("Node").to("Node")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("linkedFrom")
                        .attribute("ref", ValueType.STRING, a -> a.unique()))
                .build();
    }

    @Test
    void aUniqueAttributeOfRelationshipsIsUniqueAmongRelationshipsAtEveryPointInTime() {
        ReferenceStore nodes = ReferenceStore.with(links());
        TemporaryId a = new TemporaryId("a");
        TemporaryId b = new TemporaryId("b");
        TemporaryId c = new TemporaryId("c");
        Receipt created = nodes.commit(Transaction.by("importer")
                .create(a, "Node", from(1, 1), values("ref", "shared"))
                .create(b, "Node", from(1, 1), values())
                .create(c, "Node", from(1, 1), values())
                .relate(new TemporaryId("first"), "Link", a, b, from(2, 1), values("ref", "shared")).build());

        List<Problem> clash = nodes.reject(Transaction.by("importer")
                .relate(new TemporaryId("second"), "Link", entity(created, b), entity(created, c), from(3, 1),
                        values("ref", "shared")).build());

        assertProblem(only(clash), ProblemCode.UNIQUE_VALUE_TAKEN, "$second.ref");
        assertThat(only(clash).message()).contains(relationship(created, new TemporaryId("first")).value());
    }

    @Test
    void aUniqueValueOfARelationshipMayBeReusedAfterItEndsAndDoesNotClashWithAnEntity() {
        ReferenceStore nodes = ReferenceStore.with(links());
        TemporaryId a = new TemporaryId("a");
        TemporaryId b = new TemporaryId("b");
        Receipt created = nodes.commit(Transaction.by("importer")
                .create(a, "Node", from(1, 1), values("ref", "shared"))
                .create(b, "Node", from(1, 1), values())
                .relate(new TemporaryId("first"), "Link", a, b, EffectivePeriod.between(on(2, 1), on(3, 1)),
                        values("ref", "shared")).build());

        Receipt receipt = nodes.commit(Transaction.by("importer")
                .relate(new TemporaryId("second"), "Link", entity(created, b), entity(created, a), from(3, 1),
                        values("ref", "shared")).build());

        assertThat(receipt.created()).hasSize(1);
    }

    @Test
    void twoRelationshipsOfOneTransactionWithTheSameUniqueValueAreReportedOnTheSecond() {
        ReferenceStore nodes = ReferenceStore.with(links());
        TemporaryId a = new TemporaryId("a");
        TemporaryId b = new TemporaryId("b");
        TemporaryId c = new TemporaryId("c");

        List<Problem> problems = nodes.reject(Transaction.by("importer")
                .create(a, "Node", from(1, 1), values())
                .create(b, "Node", from(1, 1), values())
                .create(c, "Node", from(1, 1), values())
                .relate(new TemporaryId("first"), "Link", a, b, from(2, 1), values("ref", "x"))
                .relate(new TemporaryId("second"), "Link", b, c, from(2, 1), values("ref", "x")).build());

        assertProblem(only(problems), ProblemCode.UNIQUE_VALUE_TAKEN, "$second.ref");
    }
}

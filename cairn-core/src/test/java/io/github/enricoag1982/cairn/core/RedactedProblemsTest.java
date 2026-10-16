package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.DryRunResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the problems of a transaction with a clearance say about attributes that the clearance does not cover (ADR-0036,
 * design D4 of {@code add-attribute-access-control}): the code and the path remain, and, for a check, its name and its
 * author's message, but no value of a withheld attribute, no value found, no message of an evaluation, and no ID of another
 * entity. The model is test-only: a Gadget has a name and a size, which anyone may read and write, and attributes derived
 * from them that are classified {@code secret}, so that a writer without a clearance can still cause problems about them.
 * A transaction without a clearance gets the messages it always got. The tests run against RocksDB stores too.
 */
class RedactedProblemsTest {

    private static final Clearance NONE = Clearance.none();
    private static final TemporaryId G1 = new TemporaryId("g1");
    private static final TemporaryId G2 = new TemporaryId("g2");

    private static SchemaDefinition gadgets() {
        return SchemaDefinition.builder()
                .entityType("Gadget", gadget -> gadget
                        .attribute("name", ValueType.STRING, a -> a.required())
                        .attribute("size", ValueType.INT)
                        // The tag is the name, and must be unique among gadgets.
                        .attribute("tag", ValueType.STRING, a -> a.derived("self.name").unique()
                                .classification("secret"))
                        // The rank is twice the size, and at most 10.
                        .attribute("rank", ValueType.INT, a -> a.derived("self.size * 2").classification("secret")
                                .range(Range.between(Bound.inclusive(Value.of(0)), Bound.inclusive(Value.of(10)))))
                        // The ratio divides by the size, which can be 0.
                        .attribute("ratio", ValueType.INT, a -> a.derived("100 / self.size")
                                .classification("secret"))
                        .check("size-below-100", "!has(self.size) || self.size < 100",
                                "A gadget is smaller than 100.")
                        .check("size-not-seven", "!has(self.size) || 100 / (self.size - 7) != 12345")
                        // A check over the tag, which is classified.
                        .check("tag-short", "!has(self.tag) || self.tag.size() < 12", "A tag is short.")
                        .check("tag-numeric", "self.name != 'Probe-9137' || int(self.tag) > 0"))
                .build();
    }

    private ReferenceStore store() {
        return ReferenceStore.withExpressionLanguage(gadgets(), ExpressionLanguages.discovered().orElseThrow());
    }

    private static Transaction.Builder create(Clearance clearance, TemporaryId id, String name, Integer size) {
        Transaction.Builder builder = Transaction.by("importer");
        if (clearance != null) {
            builder.clearance(clearance);
        }
        return size == null
                ? builder.create(id, "Gadget", from(1, 1), values("name", name))
                : builder.create(id, "Gadget", from(1, 1), values("name", name, "size", size));
    }

    private static List<Problem> problemsOf(ReferenceStore reference, Transaction transaction) {
        DryRunResult dryRun = reference.store.dryRun(transaction);
        assertThat(dryRun).isInstanceOf(DryRunResult.Rejected.class);
        List<Problem> problems = ((DryRunResult.Rejected) dryRun).problems();
        assertThat(reference.store.commit(transaction)).as("a commit gets the same problems")
                .isInstanceOfSatisfying(CommitResult.Rejected.class,
                        rejected -> assertThat(rejected.problems()).isEqualTo(problems));
        return problems;
    }

    private static Problem problem(List<Problem> problems, ProblemCode code) {
        return problems.stream().filter(candidate -> candidate.code() == code).findFirst()
                .orElseThrow(() -> new AssertionError("No " + code + " in " + problems));
    }

    // ------------------------------------------------------------------ unique values

    @Test
    void aClashOfAClassifiedUniqueValueNamesNeitherTheValueNorTheOtherEntity() {
        ReferenceStore reference = store();
        EntityId first = (EntityId) reference.commit(create(null, G1, "alpha-tag", 5).build())
                .temporaryIds().get(G1);
        reference.recordedOn(on(2, 1));

        List<Problem> redacted = problemsOf(reference, create(NONE, G2, "alpha-tag", 5).build());
        List<Problem> plain = problemsOf(reference, create(null, G2, "alpha-tag", 5).build());

        Problem clash = problem(redacted, ProblemCode.UNIQUE_VALUE_TAKEN);
        assertThat(clash.path()).endsWith(".tag");
        assertThat(clash.toString()).as("no value and no ID of the other entity").doesNotContain("alpha-tag")
                .doesNotContain(first.value());
        assertThat(clash.found()).isEmpty();
        Problem named = problem(plain, ProblemCode.UNIQUE_VALUE_TAKEN);
        assertThat(named.message()).as("without a clearance, the message it always had").contains("alpha-tag")
                .contains(first.value());
        assertThat(named.found()).hasValue("alpha-tag");
        assertThat(named.path()).isEqualTo(clash.path());
    }

    // ------------------------------------------------------------------ constraints of values

    @Test
    void aValueOfAClassifiedDerivedAttributeOutOfRangeIsReportedWithoutTheValue() {
        ReferenceStore reference = store();

        List<Problem> redacted = problemsOf(reference, create(NONE, G1, "wide", 7).build());
        List<Problem> plain = problemsOf(reference, create(null, G1, "wide", 7).build());

        Problem range = problem(redacted, ProblemCode.VALUE_OUT_OF_RANGE);
        assertThat(range.path()).endsWith(".rank");
        assertThat(range.found()).isEmpty();
        assertThat(range.message()).doesNotContain("14");
        assertThat(range.expected()).as("what the attribute demands").isPresent();
        assertThat(problem(plain, ProblemCode.VALUE_OUT_OF_RANGE).found()).hasValue("14");
        assertThat(problem(plain, ProblemCode.VALUE_OUT_OF_RANGE).message()).contains("14");
    }

    // ------------------------------------------------------------------ derivations

    @Test
    void aDerivationOfAClassifiedAttributeThatFailsSaysOnlyThatItGaveNoValue() {
        ReferenceStore reference = store();

        List<Problem> redacted = problemsOf(reference, create(NONE, G1, "flat", 0).build());
        List<Problem> plain = problemsOf(reference, create(null, G1, "flat", 0).build());

        Problem failed = problem(redacted, ProblemCode.DERIVATION_FAILED);
        assertThat(failed.path()).endsWith(".ratio");
        assertThat(failed.found()).isEmpty();
        assertThat(failed.message()).isEqualTo("The derivation of the attribute ratio gave no value that fits it.");
        Problem detailed = problem(plain, ProblemCode.DERIVATION_FAILED);
        assertThat(detailed.found()).isPresent();
        assertThat(detailed.message()).startsWith("The derivation of the attribute ratio gave no value that fits it: ");
        assertThat(detailed.path()).isEqualTo(failed.path());
    }

    // ------------------------------------------------------------------ checks

    @Test
    void aCheckThatReadsOnlyAttributesTheClearanceCoversKeepsTheDetailOfItsFailure() {
        ReferenceStore reference = store();

        List<Problem> problems = problemsOf(reference, create(NONE, G1, "seven", 7).build());

        Problem failed = problem(problems, ProblemCode.CHECK_FAILED);
        assertThat(failed.message()).contains("size-not-seven").startsWith("The check size-not-seven gave no result: ");
        assertThat(failed.found()).as("it reads size, which anyone may read").isPresent();
    }

    @Test
    void aCheckThatReadsAClassifiedAttributeSaysOnlyThatItGaveNoResult() {
        ReferenceStore reference = store();

        List<Problem> redacted = problemsOf(reference, create(NONE, G1, "Probe-9137", 5).build());
        List<Problem> plain = problemsOf(reference, create(null, G1, "Probe-9137", 5).build());

        Problem failed = problem(redacted, ProblemCode.CHECK_FAILED);
        assertThat(failed.message()).isEqualTo("The check tag-numeric gave no result.");
        assertThat(failed.found()).isEmpty();
        assertThat(failed.toString()).doesNotContain("9137");
        Problem detailed = problem(plain, ProblemCode.CHECK_FAILED);
        assertThat(detailed.message()).startsWith("The check tag-numeric gave no result: ");
        assertThat(detailed.found()).isPresent();
    }

    @Test
    void aCheckThatReadsAClassifiedAttributeAndIsViolatedKeepsTheMessageOfItsAuthor() {
        ReferenceStore reference = store();

        List<Problem> problems = problemsOf(reference, create(NONE, G1, "a-name-longer-than-a-tag", 5).build());

        Problem violated = problem(problems, ProblemCode.CHECK_VIOLATED);
        assertThat(violated.message()).contains("tag-short").contains("A tag is short.");
        assertThat(violated.toString()).doesNotContain("a-name-longer");
    }

    @Test
    void aViolatedCheckKeepsTheMessageOfItsAuthor() {
        ReferenceStore reference = store();

        List<Problem> problems = problemsOf(reference, create(NONE, G1, "big", 250).build());

        Problem violated = problem(problems, ProblemCode.CHECK_VIOLATED);
        assertThat(violated.message()).contains("size-below-100").contains("A gadget is smaller than 100.");
    }

    @Test
    void theOtherProblemsOfARestrictedTransactionAreStillReportedInFull() {
        ReferenceStore reference = store();

        List<Problem> problems = problemsOf(reference, Transaction.by("importer").clearance(NONE)
                .create(G1, "Gadget", from(1, 1), values("size", 5)).build());

        Problem missing = problem(problems, ProblemCode.MISSING_REQUIRED_VALUE);
        assertThat(missing.path()).endsWith(".name");
        assertThat(missing.message()).contains("required attribute name");
    }
}

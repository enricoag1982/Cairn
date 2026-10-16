package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.reference.types.LeiChecksumRuleV1;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Owns;
import io.github.enricoag1982.cairn.reference.types.TotalOwnershipAtMost100RuleV1;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The base classes that are generated for the rules the reference model names (design D3). */
class RuleBaseClassesTest {

    /** Records the organizations it is asked to check. */
    private static final class RecordingLeiChecksum extends LeiChecksumRuleV1 {
        final List<Organization> seen = new ArrayList<>();

        @Override
        public RuleDescriptor descriptor() {
            return describe("Records.", new RuleCode("never", "Never reported."));
        }

        @Override
        protected void check(Organization value, RuleContext context) {
            seen.add(value);
        }
    }

    /** Records the ownerships it is asked to check. */
    private static final class RecordingOwnership extends TotalOwnershipAtMost100RuleV1 {
        final List<Owns> seen = new ArrayList<>();

        @Override
        public RuleDescriptor descriptor() {
            return describe("Records.");
        }

        @Override
        protected void check(Owns value, RuleContext context) {
            seen.add(value);
        }
    }

    /** Records the organizations it is asked to check, with the names of the versions that {@code previous} gives. */
    private static final class PreviousOrganization extends LeiChecksumRuleV1 {
        final List<String> seen = new ArrayList<>();

        @Override
        public RuleDescriptor descriptor() {
            return describe("Records.");
        }

        @Override
        protected void check(Organization value, RuleContext context) {
            seen.add(value.name() + " from " + value.entityState().period().from() + " after, "
                    + previous(value, context).stream().map(before -> before.name() + " " + before.lei().orElse("-"))
                            .toList() + " before");
        }
    }

    /** Records the ownerships it is asked to check, with the shares of the versions that {@code previous} gives. */
    private static final class PreviousOwnership extends TotalOwnershipAtMost100RuleV1 {
        final List<String> seen = new ArrayList<>();

        @Override
        public RuleDescriptor descriptor() {
            return describe("Records.");
        }

        @Override
        protected void check(Owns value, RuleContext context) {
            seen.add(value.share().toPlainString() + " after, " + previous(value, context).stream()
                    .map(before -> before.share().toPlainString()).toList() + " before");
        }
    }

    @Test
    void aBaseClassHoldsTheNameVersionAndTypeOfItsRule() {
        assertThat(LeiChecksumRuleV1.NAME).isEqualTo("lei-checksum");
        assertThat(LeiChecksumRuleV1.VERSION).isEqualTo(1);
        assertThat(LeiChecksumRuleV1.APPLIES_TO).isEqualTo("Organization");
        assertThat(TotalOwnershipAtMost100RuleV1.NAME).isEqualTo("total-ownership-at-most-100");
        assertThat(TotalOwnershipAtMost100RuleV1.VERSION).isEqualTo(1);
        assertThat(TotalOwnershipAtMost100RuleV1.APPLIES_TO).isEqualTo("Owns");
    }

    @Test
    void aBaseClassImplementsTheRuleInterfaceOfItsKindOfType() {
        assertThat(EntityRule.class).isAssignableFrom(LeiChecksumRuleV1.class);
        assertThat(RelationshipRule.class).isAssignableFrom(TotalOwnershipAtMost100RuleV1.class);
    }

    @Test
    void theDescriptorHasTheDescriptionAndCodesOfTheSubclassAndTheRestFromTheBaseClass() {
        RuleDescriptor descriptor = new RecordingLeiChecksum().descriptor();

        assertThat(descriptor).isEqualTo(new RuleDescriptor("lei-checksum", 1, "Records.", "Organization",
                List.of(new RuleCode("never", "Never reported."))));
        assertThat(new RecordingOwnership().descriptor()).isEqualTo(new RuleDescriptor(
                "total-ownership-at-most-100", 1, "Records.", "Owns", List.of()));
    }

    @Test
    void anEntityRuleIsPassedEveryVersionReadAsItsType() {
        RecordingLeiChecksum rule = new RecordingLeiChecksum();
        ReferenceDomain domain = new ReferenceDomain(List.of(rule, new TotalOwnershipRuleStub()));

        Receipt parties = domain.replay(ReferenceStories.rd1Parties());

        assertThat(rule.seen).extracting(Organization::name).containsExactlyInAnyOrder("ACME", "Beta");
        assertThat(rule.seen).extracting(Organization::id).containsExactlyInAnyOrder(
                ReferenceDomain.entityId(parties, ACME), ReferenceDomain.entityId(parties, BETA));
        assertThat(rule.seen).allSatisfy(organization -> assertThat(organization.lei()).isEmpty());
    }

    @Test
    void aRelationshipRuleIsPassedEveryVersionReadAsItsType() {
        RecordingOwnership rule = new RecordingOwnership();
        ReferenceDomain domain = new ReferenceDomain(List.of(new LeiChecksumRule(), rule));
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());

        domain.replay(ReferenceStories.rd2Ownership(ReferenceDomain.entityId(parties, ACME),
                ReferenceDomain.entityId(parties, BETA)));

        assertThat(rule.seen).singleElement().satisfies(owns -> {
            assertThat(owns.share()).isEqualByComparingTo("60");
            assertThat(owns.source()).isEqualTo(ReferenceDomain.entityId(parties, ACME));
            assertThat(owns.target()).isEqualTo(ReferenceDomain.entityId(parties, BETA));
        });
    }

    @Test
    void previousReadsTheVersionsOfTheEntityThatTheStoreHeldDuringThePeriodOfTheVersionAsItsType() {
        PreviousOrganization rule = new PreviousOrganization();
        ReferenceDomain domain = new ReferenceDomain(List.of(rule, new TotalOwnershipRuleStub()));
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        assertThat(rule.seen).as("an entity that the transaction creates had no previous version")
                .containsExactlyInAnyOrder("ACME from " + on(1, 1) + " after, [] before",
                        "Beta from " + on(1, 1) + " after, [] before");
        rule.seen.clear();
        EntityId acme = ReferenceDomain.entityId(parties, ACME);

        domain.replay(ReferenceStories.rd9AcmeLei(acme));
        String lei = ReferenceStories.LEI;
        assertThat(rule.seen).containsExactly("ACME from " + on(1, 1) + " after, [ACME -] before");

        // The rule reads the timeline, so it is run again for the versions that the transaction does not write, after
        // the one it writes. A re-check reads, as "before", the state before the transaction that runs it again.
        rule.seen.clear();
        domain.commit(on(2, 1), Transaction.by(ReferenceStories.PRINCIPAL)
                .patch(acme, from(3, 1), values("name", "ACME Mar")).build());
        assertThat(rule.seen).containsExactly(
                "ACME Mar from " + on(3, 1) + " after, [ACME " + lei + "] before",
                "ACME from " + on(1, 1) + " after, [ACME " + lei + "] before");

        rule.seen.clear();
        domain.commit(on(2, 2), Transaction.by(ReferenceStories.PRINCIPAL)
                .patch(acme, from(6, 1), values("name", "ACME Jun")).build());
        assertThat(rule.seen).containsExactly(
                "ACME Jun from " + on(6, 1) + " after, [ACME Mar " + lei + "] before",
                "ACME from " + on(1, 1) + " after, [ACME " + lei + "] before",
                "ACME Mar from " + on(3, 1) + " after, [ACME Mar " + lei + "] before");

        rule.seen.clear();
        domain.commit(on(2, 3), Transaction.by(ReferenceStories.PRINCIPAL)
                .patch(acme, from(4, 1), values("name", "ACME Apr")).build());
        assertThat(rule.seen).as("the version from April lasts until the end, so it overlaps March and June")
                .containsExactly(
                        "ACME Apr from " + on(4, 1) + " after, [ACME Mar " + lei + ", ACME Jun " + lei + "] before",
                        "ACME from " + on(1, 1) + " after, [ACME " + lei + "] before",
                        "ACME Mar from " + on(3, 1) + " after, [ACME Mar " + lei + "] before");
    }

    @Test
    void previousReadsTheVersionsOfTheRelationshipThatTheStoreHeldDuringThePeriodOfTheVersionAsItsType() {
        PreviousOwnership rule = new PreviousOwnership();
        ReferenceDomain domain = new ReferenceDomain(List.of(new LeiChecksumRule(), rule));
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        Receipt ownership = domain.replay(ReferenceStories.rd2Ownership(ReferenceDomain.entityId(parties, ACME),
                ReferenceDomain.entityId(parties, BETA)));
        assertThat(rule.seen).as("a relationship that the transaction creates had no previous version")
                .containsExactly("60 after, [] before");
        rule.seen.clear();
        RelationshipId owns = ReferenceDomain.relationshipId(ownership, ReferenceStories.ACME_OWNS_BETA);

        domain.commit(on(2, 1), Transaction.by(ReferenceStories.PRINCIPAL).patch(owns, from(3, 1),
                values("share", 70)).build());
        assertThat(rule.seen).as("the version written, then the version it read the timeline of, run again")
                .containsExactly("70 after, [60] before", "60 after, [60] before");

        rule.seen.clear();
        domain.commit(on(2, 2), Transaction.by(ReferenceStories.PRINCIPAL).patch(owns, from(2, 15),
                values("share", 65)).build());
        assertThat(rule.seen).as("the version from 15 February overlaps both versions that were held")
                .containsExactly("65 after, [60, 70] before", "60 after, [60] before");
    }

    @Test
    void aBaseClassWritesPreviousForItsTypeWithTheTimelineOfTheBeforeViewOfTheContext() throws Exception {
        Generation.Generated generated = generate(ReferenceDomain.DOCUMENT);

        assertThat(text(generated, "LeiChecksumRuleV1"))
                .contains("protected List<Organization> previous(Organization version, RuleContext context) {")
                .contains("EffectivePeriod period = version.entityState().period();")
                .contains("return context.before().timeline(version.id()).stream()")
                .contains(".map(Organization::from)");
        assertThat(text(generated, "TotalOwnershipAtMost100RuleV1"))
                .contains("protected List<Owns> previous(Owns version, RuleContext context) {")
                .contains("EffectivePeriod period = version.relationshipState().period();")
                .contains("return context.before().relationshipTimeline(version.id()).stream()")
                .contains(".map(Owns::from)");
    }

    @Test
    void previousCompilesInTheBaseClassOfARuleForATypeNamedLikeALibraryType(@TempDir Path directory) throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: library, version: "1.0.0" }
                entityTypes:
                  List:
                    attributes:
                      present: { type: boolean }
                    rules:
                      - { name: list-kept, version: 1 }
                """);

        assertThat(text(generated, "ListKeptRuleV1"))
                .contains("protected java.util.List<List> previous(List version, RuleContext context) {");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(loader.loadClass(GeneratedSource.PACKAGE + ".ListKeptRuleV1")
                    .getDeclaredMethod("previous", loader.loadClass(GeneratedSource.PACKAGE + ".List"),
                            RuleContext.class).getReturnType()).isEqualTo(java.util.List.class);
        }
    }

    /** A rule for the second rule of the reference model that checks nothing, so that the model can be registered. */
    private static final class TotalOwnershipRuleStub extends TotalOwnershipAtMost100RuleV1 {
        @Override
        public RuleDescriptor descriptor() {
            return describe("Checks nothing.");
        }

        @Override
        protected void check(Owns value, RuleContext context) {
            // Nothing to check.
        }
    }
}

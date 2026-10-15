package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipRule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.reference.types.LeiChecksumRuleV1;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Owns;
import io.github.enricoag1982.cairn.reference.types.TotalOwnershipAtMost100RuleV1;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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

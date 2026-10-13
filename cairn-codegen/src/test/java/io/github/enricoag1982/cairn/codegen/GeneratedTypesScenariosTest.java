package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.reference.types.Address;
import io.github.enricoag1982.cairn.reference.types.HasAddress;
import io.github.enricoag1982.cairn.reference.types.LeiChecksumRuleV1;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Owns;
import io.github.enricoag1982.cairn.reference.types.Party;
import io.github.enricoag1982.cairn.reference.types.Person;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the {@code java-types} spec that use the generated types of the reference domain, which the build
 * generates from {@code docs/reference/reference-model.cairn.yaml} into this test source set (the convention plugin
 * {@code cairn.reference-types}), with the in-memory store and the stories of the reference domain.
 */
class GeneratedTypesScenariosTest {

    private static final String PRINCIPAL = ReferenceStories.PRINCIPAL;

    /** A test-only rule written against the base class that was generated for {@code lei-checksum}. */
    private static final class TypedLeiChecksum extends LeiChecksumRuleV1 {

        static final RuleDescriptor DESCRIPTOR = describe("The LEI's check digits are valid (ISO 17442, ISO 7064 "
                + "MOD 97-10).", new RuleCode(LeiChecksumRule.INVALID_CHECK_DIGITS,
                        "The two check digits of the LEI do not match its other characters. Correct the LEI."));

        @Override
        public RuleDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        protected void check(Organization value, RuleContext context) {
            value.lei().filter(lei -> !LeiChecksumRule.hasValidCheckDigits(lei)).ifPresent(lei ->
                    context.problem(LeiChecksumRule.INVALID_CHECK_DIGITS, "lei",
                            "The check digits of the LEI " + lei + " are wrong (ISO 7064 MOD 97-10)."));
        }
    }

    // ------------------------------------------------------------------ reading states

    @Test
    @DisplayName("Scenario: Reading Alice as a Person")
    void readingAliceAsAPerson() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        EntityId aliceId = ReferenceDomain.entityId(parties, ALICE);

        EntityState state = domain.entity(on(6, 1), aliceId);
        Person alice = Person.from(state);

        assertThat(alice.name()).isEqualTo("Alice");
        assertThat(alice.birthDate()).isEmpty();
        assertThat(alice.id()).isEqualTo(state.id()).isEqualTo(aliceId);
        assertThat(alice.entityState()).isEqualTo(state);
    }

    @Test
    @DisplayName("Scenario: Reading an organization as a party")
    void readingAnOrganizationAsAParty() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());

        Party acme = Party.from(domain.entity(on(6, 1), ReferenceDomain.entityId(parties, ACME)));

        assertThat(acme).isInstanceOf(Organization.class);
        assertThat(acme.name()).isEqualTo("ACME");
        assertThat(((Organization) acme).lei()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A subtype registered after generation")
    void aSubtypeRegisteredAfterGeneration() {
        ReferenceDomain domain = new ReferenceDomain();
        domain.replay(ReferenceStories.rd1Parties());
        SchemaDefinition banking = SchemaDefinition.builder().entityType("Bank", bank -> bank.supertype("Organization"))
                .build();
        assertThat(domain.store.schema().register(new Origin("banking", "1.0.0"), banking))
                .isInstanceOf(RegistrationResult.Applied.class);
        Receipt created = domain.commit(on(6, 1), Transaction.by(PRINCIPAL).create(new TemporaryId("bank"), "Bank",
                ReferenceStories.from(1, 1), ReferenceStories.values("name", "First Bank")).build());

        EntityState state = domain.entity(on(6, 2), ReferenceDomain.entityId(created, new TemporaryId("bank")));
        Organization bank = Organization.from(state);

        assertThat(bank.entityState().entityType()).isEqualTo("Bank");
        assertThat(bank.name()).isEqualTo("First Bank");
        assertThat(bank.lei()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A state of another type")
    void aStateOfAnotherType() {
        ReferenceDomain domain = new ReferenceDomain();
        EntityId acme = ReferenceDomain.entityId(domain.replay(ReferenceStories.rd1Parties()), ACME);
        Receipt created = domain.commit(on(1, 6), Transaction.by(PRINCIPAL)
                .write(Address.create().as("address").street("1 High Street").city("London").country("GB")
                        .effectiveFrom(on(1, 1)))
                .write(HasAddress.relate(acme, new TemporaryId("address")).effectiveFrom(on(1, 1))).build());
        EntityState address = domain.entity(on(6, 1), ReferenceDomain.entityId(created, new TemporaryId("address")));

        assertThatThrownBy(() -> Person.from(address)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Address").hasMessageContaining("Person");
    }

    // ------------------------------------------------------------------ building writes

    @Test
    @DisplayName("Scenario: Creating Alice with a builder")
    void creatingAliceWithABuilder() {
        ReferenceDomain domain = new ReferenceDomain();

        Receipt receipt = domain.commit(on(1, 5), Transaction.by(PRINCIPAL)
                .write(Person.create().as("alice").name("Alice").effectiveFrom(on(1, 1))).build());

        Person alice = Person.from(domain.entity(on(6, 1), ReferenceDomain.entityId(receipt, new TemporaryId("alice"))));
        assertThat(alice.name()).isEqualTo("Alice");
        assertThat(alice.birthDate()).isEmpty();
    }

    @Test
    @DisplayName("Scenario: A builder does not check values")
    void aBuilderDoesNotCheckValues() {
        ReferenceDomain domain = new ReferenceDomain();

        CommitResult result = domain.attempt(on(1, 5), Transaction.by(PRINCIPAL)
                .write(Person.create().as("alice").effectiveFrom(on(1, 1))).build());

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        List<Problem> problems = ((CommitResult.Rejected) result).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.MISSING_REQUIRED_VALUE);
            assertThat(problem.path()).isEqualTo("$alice.name");
        });
    }

    @Test
    @DisplayName("Scenario: Relating with a builder")
    void relatingWithABuilder() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        Organization acme = Organization.from(domain.entity(on(6, 1), ReferenceDomain.entityId(parties, ACME)));
        Party beta = Party.from(domain.entity(on(6, 1), ReferenceDomain.entityId(parties, BETA)));

        Receipt receipt = domain.commit(on(3, 2), Transaction.by(PRINCIPAL).write(Owns.relate(acme, beta).as("owns")
                .share(new BigDecimal("30")).effectiveFrom(on(3, 1))).build());

        RelationshipId id = ReferenceDomain.relationshipId(receipt, new TemporaryId("owns"));
        Owns owns = Owns.from(domain.relationship(on(6, 1), id));
        assertThat(owns.share()).isEqualByComparingTo("30");
        assertThat(owns.source()).isEqualTo(acme.id());
        assertThat(owns.target()).isEqualTo(beta.id());
        assertThat(owns.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("Scenario: Clearing an optional value with a builder")
    void clearingAnOptionalValueWithABuilder() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        EntityId acme = ReferenceDomain.entityId(parties, ACME);
        domain.replay(ReferenceStories.rd9AcmeLei(acme));
        assertThat(Organization.from(domain.entity(on(2, 1), acme)).lei()).contains(ReferenceStories.LEI);

        domain.commit(on(3, 2), Transaction.by(PRINCIPAL)
                .write(Organization.patch(acme).clearLei().effectiveFrom(on(3, 1))).build());

        assertThat(Organization.from(domain.entity(on(3, 1), acme)).lei()).isEmpty();
        assertThat(Organization.from(domain.entity(on(2, 1), acme)).lei()).as("before 1 March").contains(ReferenceStories.LEI);
    }

    // ------------------------------------------------------------------ rules

    @Test
    @DisplayName("Scenario: A rule written against a generated type")
    void aRuleWrittenAgainstAGeneratedType() {
        ReferenceDomain domain = new ReferenceDomain(List.of(new TypedLeiChecksum(), new TotalOwnershipRule()));

        CommitResult result = ReferenceStories.attempt(domain.store, domain.clock,
                ReferenceStories.rd12GammaWrongCheckDigits());

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
            assertThat(problem.ruleCode()).contains("lei-checksum/invalid-check-digits");
        });
        assertThat(domain.store.schema().availableRules()).contains(TypedLeiChecksum.DESCRIPTOR);
        assertThat(TypedLeiChecksum.DESCRIPTOR.appliesTo()).isEqualTo("Organization");
    }
}

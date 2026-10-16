package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.LeiChecksumRule;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.fixtures.TotalOwnershipRule;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.query.Row;
import io.github.enricoag1982.cairn.reference.types.Account;
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
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * The test-only rule {@code lei-kept}, written against the base class that is generated for it: it reads the
     * organization's previous versions with {@code previous}, as {@code Organization}s, and compares with each. It is
     * source text, because the reference model does not name this rule, so the build generates no base class for it;
     * the scenario generates one from the reference model in which {@code Organization} names the rule, and compiles
     * this source with it.
     */
    private static final String TYPED_LEI_KEPT = """
            package %s;

            import io.github.enricoag1982.cairn.data.RuleContext;
            import io.github.enricoag1982.cairn.schema.RuleCode;
            import io.github.enricoag1982.cairn.schema.RuleDescriptor;

            /** The test-only rule lei-kept, written against its generated base class. */
            public final class TypedLeiKept extends LeiKeptRuleV1 {

                /** Creates the rule. */
                public TypedLeiKept() {
                }

                @Override
                public RuleDescriptor descriptor() {
                    return describe("An organization that had an LEI before the transaction, at any time in the "
                            + "checked version's period, still has one.",
                            new RuleCode("lei-removed", "The organization had an LEI before the transaction. Keep it."));
                }

                @Override
                protected void check(Organization value, RuleContext context) {
                    if (value.lei().isEmpty() && previous(value, context).stream()
                            .anyMatch(before -> before.lei().isPresent())) {
                        context.problem("lei-removed", "The LEI of " + value.name() + " would be removed.");
                    }
                }
            }
            """.formatted(GeneratedSource.PACKAGE);

    // ------------------------------------------------------------------ typed queries

    @Test
    @DisplayName("Scenario: A typed query")
    void aTypedQuery() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        Receipt accounts = domain.replay(ReferenceStories.rd5Accounts(ReferenceDomain.entityId(parties, BETA),
                ReferenceDomain.entityId(parties, ACME)));
        EntityId a1 = ReferenceDomain.entityId(accounts, ReferenceStories.A1);
        domain.replay(ReferenceStories.rd5BalancePatch(a1));
        domain.replay(ReferenceStories.rd5BalanceCorrection(a1));
        Query query = Query.from(Account.TYPE, "a")
                .where(Account.BALANCE.of("a").gt(new BigDecimal("1200")))
                .build();

        QueryResult result = domain.view(on(3, 31)).query(query);

        assertThat(result).isInstanceOf(QueryResult.Rows.class);
        List<Row> rows = ((QueryResult.Rows) result).rows();
        assertThat(rows).singleElement().satisfies(row -> {
            Account account = Account.from(row.entity("a").orElseThrow());
            assertThat(account.number()).isEqualTo("A-2");
            assertThat(account.balance()).isEqualByComparingTo("2000");
        });
    }

    @Test
    @DisplayName("Scenario: A subtype registered after generation in a query")
    void aSubtypeRegisteredAfterGenerationInAQuery() {
        ReferenceDomain domain = new ReferenceDomain();
        domain.replay(ReferenceStories.rd1Parties());
        SchemaDefinition banking = SchemaDefinition.builder().entityType("Bank", bank -> bank.supertype("Organization"))
                .build();
        assertThat(domain.store.schema().register(new Origin("banking", "1.0.0"), banking))
                .isInstanceOf(RegistrationResult.Applied.class);
        domain.commit(on(6, 1), Transaction.by(PRINCIPAL).create(new TemporaryId("bank"), "Bank",
                ReferenceStories.from(1, 1), ReferenceStories.values("name", "First Bank")).build());
        View view = domain.view(on(6, 2));

        QueryResult result = view.query(Query.from(Party.TYPE, "p").build());

        assertThat(result).isInstanceOf(QueryResult.Rows.class);
        List<Party> parties = ((QueryResult.Rows) result).rows().stream()
                .map(row -> Party.from(row.entity("p").orElseThrow(), view.schema())).toList();
        assertThat(parties).extracting(Party::name).containsExactlyInAnyOrder("ACME", "Beta", "Alice", "First Bank");
        Party bank = parties.stream().filter(party -> party.name().equals("First Bank")).findFirst().orElseThrow();
        assertThat(bank).as("a Bank is an Organization").isInstanceOf(Organization.class);
        assertThat(bank.entityState().entityType()).isEqualTo("Bank");
        assertThat(((Organization) bank).lei()).isEmpty();
        assertThat(Party.from(view.entity(bank.id()).orElseThrow())).as("without the schema, it is only a Party")
                .isNotInstanceOf(Organization.class);
        assertThat(parties).filteredOn(party -> party.name().equals("Alice")).singleElement()
                .isInstanceOf(Person.class);
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

    // ------------------------------------------------------------------ keys

    @Test
    @DisplayName("Scenario: Patching and finding by a generated key")
    void patchingAndFindingByAGeneratedKey() {
        ReferenceDomain domain = new ReferenceDomain();
        Receipt parties = domain.replay(ReferenceStories.rd1Parties());
        domain.replay(ReferenceStories.rd5Accounts(ReferenceDomain.entityId(parties, BETA),
                ReferenceDomain.entityId(parties, ACME)));

        CommitResult result = domain.attempt(on(3, 16), Transaction.by(PRINCIPAL)
                .write(Account.patch(Account.byNumber("A-1")).balance(new BigDecimal("1500"))
                        .effectiveFrom(on(3, 15))).build());

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        Optional<EntityState> found = domain.view(on(3, 31)).entity(Account.byNumber("A-1"));
        assertThat(found).isPresent();
        Account a1 = Account.from(found.get());
        assertThat(a1.number()).isEqualTo("A-1");
        assertThat(a1.balance()).isEqualByComparingTo("1500");
        assertThat(Account.byNumber("A-1")).isEqualTo(new UniqueKey("Account", "number", Value.of("A-1")));
        assertThat(Organization.byLei(ReferenceStories.LEI)).as("a type of its own")
                .isEqualTo(new UniqueKey("Organization", "lei", Value.of(ReferenceStories.LEI)));
        assertThat(domain.view(on(3, 31)).entity(Organization.byLei(ReferenceStories.LEI))).isEmpty();
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

    @Test
    @DisplayName("Scenario: The previous version in a generated rule")
    void thePreviousVersionInAGeneratedRule(@TempDir Path directory) throws Exception {
        String reference = Files.readString(ReferenceDomain.DOCUMENT);
        String named = reference.replace("      - { name: lei-checksum, version: 1 }\n",
                "      - { name: lei-checksum, version: 1 }\n      - { name: lei-kept, version: 1 }\n");
        assertThat(named).as("the reference model in which Organization also names lei-kept")
                .isNotEqualTo(reference);
        Path document = GeneratedSource.document(directory, "reference-lei-kept.cairn.yaml", named);
        List<GeneratedFile> files = new ArrayList<>(GeneratedSource.generate(document).files());
        files.add(new GeneratedFile(GeneratedSource.PACKAGE.replace('.', '/') + "/TypedLeiKept.java", TYPED_LEI_KEPT));

        try (URLClassLoader loader = GeneratedSource.compile(new Generation.Generated(files), directory).loader()) {
            Rule leiKept = (Rule) loader.loadClass(GeneratedSource.PACKAGE + ".TypedLeiKept")
                    .getDeclaredConstructor().newInstance();
            List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
            rules.add(leiKept);
            ReferenceDomain domain = new ReferenceDomain(document, rules);
            EntityId acme = ReferenceDomain.entityId(domain.replay(ReferenceStories.rd1Parties()), ACME);
            domain.replay(ReferenceStories.rd9AcmeLei(acme));

            CommitResult result = domain.attempt(on(2, 1), Transaction.by(PRINCIPAL)
                    .patch(acme, ReferenceStories.from(3, 1), ReferenceStories.values(), Set.of("lei"),
                            ConflictCheck.none()).build());

            assertThat(result).isInstanceOf(CommitResult.Rejected.class);
            assertThat(((CommitResult.Rejected) result).problems()).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.RULE_VIOLATION);
                assertThat(problem.ruleCode()).contains("lei-kept/lei-removed");
                assertThat(problem.path()).isEqualTo(acme.value());
            });
            assertThat(Organization.from(domain.entity(on(4, 1), acme)).lei()).as("the LEI was not removed")
                    .contains(ReferenceStories.LEI);
        }
    }
}

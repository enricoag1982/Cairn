package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ALICE;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.reference.types.Account;
import io.github.enricoag1982.cairn.reference.types.HeldBy;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Owns;
import io.github.enricoag1982.cairn.reference.types.Party;
import io.github.enricoag1982.cairn.reference.types.Person;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading states as generated types with the schema they were read in, {@code from(state, schema)} (design D6 of
 * {@code add-queries-and-aggregation}, ADR-0012): the nearest type that the generated code knows among the state's type
 * and its supertypes, for entities and for relationships, and the types that are not one of those.
 */
class ReadingWithASchemaTest {

    private static final String PRINCIPAL = ReferenceStories.PRINCIPAL;
    private static final TemporaryId BANK = new TemporaryId("bank");
    private static final TemporaryId SAVINGS = new TemporaryId("savings");
    private static final TemporaryId TRUST = new TemporaryId("trust");
    private static final TemporaryId WIDGET = new TemporaryId("widget");
    private static final TemporaryId CONTROLS = new TemporaryId("controls");

    private final ReferenceDomain domain = new ReferenceDomain();
    private Receipt parties;
    private Receipt created;
    /** The schema of a view taken before the types were registered. */
    private Schema earlier;

    /** The reference domain, with types registered after generation: Bank, a subtype of a subtype, and more. */
    @BeforeEach
    void registerTypesAfterGeneration() {
        parties = domain.replay(ReferenceStories.rd1Parties());
        earlier = domain.view(on(1, 6)).schema();
        SchemaDefinition later = SchemaDefinition.builder()
                .entityType("Bank", bank -> bank.supertype("Organization"))
                .entityType("SavingsBank", savings -> savings.supertype("Bank"))
                .entityType("Trust", trust -> trust.supertype("Party"))
                .entityType("Widget", widget -> widget.attribute("name", ValueType.STRING, a -> a.required()))
                .relationshipType("Controls", controls -> controls.supertype("Owns").from("Organization").to("Party")
                        .targetsPerSource(Multiplicity.ZERO_OR_MORE).sourcesPerTarget(Multiplicity.ZERO_OR_MORE)
                        .inverse("controlledBy").attribute("note", ValueType.STRING))
                .build();
        assertThat(domain.store.schema().register(new Origin("later", "1.0.0"), later))
                .isInstanceOf(RegistrationResult.Applied.class);
        created = domain.commit(on(6, 1), Transaction.by(PRINCIPAL)
                .create(BANK, "Bank", ReferenceStories.from(1, 1), ReferenceStories.values("name", "First Bank"))
                .create(SAVINGS, "SavingsBank", ReferenceStories.from(1, 1),
                        ReferenceStories.values("name", "Savings Bank"))
                .create(TRUST, "Trust", ReferenceStories.from(1, 1), ReferenceStories.values("name", "The Trust"))
                .create(WIDGET, "Widget", ReferenceStories.from(1, 1), ReferenceStories.values("name", "A Widget"))
                .relate(CONTROLS, "Controls", BANK, TRUST, ReferenceStories.from(1, 1),
                        ReferenceStories.values("share", 70, "note", "board"))
                .build());
    }

    private View view() {
        return domain.view(on(6, 2));
    }

    private EntityState entity(TemporaryId id) {
        return view().entity(ReferenceDomain.entityId(created, id)).orElseThrow();
    }

    @Test
    void aStateOfAGeneratedTypeReadsAsItsOwnType() {
        EntityState alice = view().entity(ReferenceDomain.entityId(parties, ALICE)).orElseThrow();
        EntityState acme = view().entity(ReferenceDomain.entityId(parties, ACME)).orElseThrow();

        assertThat(Party.from(alice, view().schema())).isInstanceOf(Person.class);
        assertThat(Party.from(acme, view().schema())).isInstanceOf(Organization.class);
        assertThat(Person.from(alice, view().schema()).name()).isEqualTo("Alice");
        assertThat(Organization.from(acme, view().schema()).name()).isEqualTo("ACME");
    }

    @Test
    void aSubtypeRegisteredAfterGenerationReadsAsItsNearestGeneratedSupertype() {
        EntityState bank = entity(BANK);

        Party asParty = Party.from(bank, view().schema());
        Organization asOrganization = Organization.from(bank, view().schema());

        assertThat(asParty).isInstanceOf(Organization.class);
        assertThat(asOrganization.name()).isEqualTo("First Bank");
        assertThat(asOrganization.entityState().entityType()).isEqualTo("Bank");
    }

    @Test
    void theNearestIsFoundThroughSeveralLevels() {
        EntityState savings = entity(SAVINGS);

        assertThat(Party.from(savings, view().schema())).isInstanceOf(Organization.class);
        assertThat(Organization.from(savings, view().schema()).name()).isEqualTo("Savings Bank");
    }

    @Test
    void aSubtypeOfAnAbstractTypeThatNoGeneratedSubtypeCoversReadsAsThatType() {
        EntityState trust = entity(TRUST);

        Party party = Party.from(trust, view().schema());

        assertThat(party).isNotInstanceOf(Organization.class).isNotInstanceOf(Person.class);
        assertThat(party.name()).isEqualTo("The Trust");
    }

    @Test
    void aTypeWithNoGeneratedTypeInItsChainReadsAsTheTypeThatIsAsked() {
        EntityState widget = entity(WIDGET);

        assertThat(Party.from(widget, view().schema()).name()).isEqualTo("A Widget");
        assertThat(Organization.from(widget, view().schema()).name()).isEqualTo("A Widget");
        assertThat(Organization.from(widget, view().schema())).isNotInstanceOf(Person.class);
    }

    @Test
    void aKnownTypeThatIsNotTheOneAskedForOrASubtypeOfItIsAnError() {
        EntityState alice = view().entity(ReferenceDomain.entityId(parties, ALICE)).orElseThrow();
        EntityState bank = entity(BANK);

        assertThatThrownBy(() -> Organization.from(alice, view().schema())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Person").hasMessageContaining("Organization");
        assertThatThrownBy(() -> Person.from(bank, view().schema())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bank").hasMessageContaining("nearest generated type is Organization")
                .hasMessageContaining("neither Person");
        assertThatThrownBy(() -> Account.from(bank, view().schema())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization").hasMessageContaining("Account");
    }

    @Test
    void aSchemaWithoutTheTypeFindsNoChainSoTheStateReadsAsTheTypeAskedFor() {
        EntityState bank = entity(BANK);

        assertThat(earlier.entityType("Bank")).as("the schema of the earlier view has no Bank").isEmpty();
        assertThat(Party.from(bank, earlier)).isNotInstanceOf(Organization.class).isNotInstanceOf(Person.class);
        assertThat(Party.from(bank, earlier).name()).isEqualTo("First Bank");
        assertThat(Party.from(bank, view().schema())).isInstanceOf(Organization.class);
    }

    @Test
    void aRelationshipSubtypeRegisteredAfterGenerationReadsAsItsNearestGeneratedSupertype() {
        RelationshipState controls = view().relationship(ReferenceDomain.relationshipId(created, CONTROLS))
                .orElseThrow();
        Receipt ownership = domain.replay(ReferenceStories.rd2Ownership(ReferenceDomain.entityId(parties, ACME),
                ReferenceDomain.entityId(parties, BETA)));
        RelationshipState owns = view().relationship(
                ReferenceDomain.relationshipId(ownership, ReferenceStories.ACME_OWNS_BETA)).orElseThrow();

        Owns asOwns = Owns.from(controls, view().schema());

        assertThat(asOwns.relationshipState().relationshipType()).isEqualTo("Controls");
        assertThat(asOwns.share()).isEqualByComparingTo("70");
        assertThat(Owns.from(owns, view().schema()).share()).isEqualByComparingTo("60");
    }

    @Test
    void aRelationshipOfAnotherGeneratedTypeIsAnError() {
        RelationshipState controls = view().relationship(ReferenceDomain.relationshipId(created, CONTROLS))
                .orElseThrow();

        assertThatThrownBy(() -> HeldBy.from(controls, view().schema()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Owns")
                .hasMessageContaining("HeldBy");
    }
}

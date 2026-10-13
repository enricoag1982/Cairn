package io.github.enricoag1982.cairn.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void anEntityIdIsItsText() {
        EntityId id = new EntityId("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d");

        assertThat(id.value()).isEqualTo("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d");
        assertThat(id).hasToString("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d");
        assertThat(id).isEqualTo(new EntityId("0199c1d2-3e4f-7a5b-8c6d-7e8f9a0b1c2d"));
    }

    @Test
    void anEntityIdDoesNotCheckTheFormatBecauseTheStoreDoes() {
        assertThat(new EntityId("not-a-uuid").value()).isEqualTo("not-a-uuid");
        assertThat(new EntityId("").value()).isEmpty();
    }

    @Test
    void anEntityIdRejectsNull() {
        assertThatThrownBy(() -> new EntityId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void entityIdsAreOrderedByTheirText() {
        EntityId first = new EntityId("00000000-0000-7000-8000-000000000001");
        EntityId second = new EntityId("00000000-0000-7000-8000-000000000002");
        EntityId third = new EntityId("00000000-0000-7000-8000-00000000000a");

        assertThat(new TreeSet<>(List.of(third, first, second))).containsExactly(first, second, third);
        assertThat(first.compareTo(second)).isNegative();
    }

    @Test
    void aTemporaryIdIsItsNameAndPrintsLikeAPath() {
        TemporaryId acme = new TemporaryId("acme");

        assertThat(acme.name()).isEqualTo("acme");
        assertThat(acme).hasToString("$acme");
        assertThat(acme).isEqualTo(new TemporaryId("acme")).isNotEqualTo(new TemporaryId("beta"));
    }

    @Test
    void aTemporaryIdNeedsAName() {
        assertThatThrownBy(() -> new TemporaryId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporaryId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemporaryId("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityIdsAndTemporaryIdsAreRefs() {
        Ref byId = new EntityId("a");
        Ref byTemporaryId = new TemporaryId("a");

        assertThat(byId).isInstanceOf(Id.class);
        assertThat(byTemporaryId).isNotInstanceOf(Id.class);
        assertThat(byId).isNotEqualTo(byTemporaryId);
    }

    @Test
    void conflictChecksHoldTheBasisTheyAreAskedFor() {
        Basis basis = new Basis(7);

        assertThat(ConflictCheck.none()).isEqualTo(new ConflictCheck.None());
        assertThat(ConflictCheck.perEntity(basis)).isEqualTo(new ConflictCheck.PerEntity(basis));
        assertThat(ConflictCheck.perAttribute(basis)).isEqualTo(new ConflictCheck.PerAttribute(basis));
        assertThat(ConflictCheck.perEntity(basis)).isNotEqualTo(ConflictCheck.perAttribute(basis));
    }

    @Test
    void conflictChecksRejectANullBasis() {
        assertThatThrownBy(() -> ConflictCheck.perEntity(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConflictCheck.perAttribute(null)).isInstanceOf(NullPointerException.class);
    }
}

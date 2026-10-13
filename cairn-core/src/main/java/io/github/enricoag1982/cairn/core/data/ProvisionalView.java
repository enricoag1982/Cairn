package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityType;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The view that rules read through (design D3 of {@code add-entity-rules}): the state a transaction would produce. It
 * takes the latest committed timelines of the store and overlays the working timelines of the transaction, with the
 * schema the transaction would produce. Its basis is the prospective basis of the transaction, and the working segments
 * carry it as their recorded basis.
 *
 * <p>The view is read-only and valid only while the rules of the transaction run: in a dry run, or if the commit is
 * rejected, the prospective basis later names another commit. The transaction holds the store's writer lock, so the
 * committed timelines do not change underneath it. All views of a transaction share one {@link Source}, which is
 * created once; {@link Source#at(Instant)} makes the view at an effective time.
 */
final class ProvisionalView implements View {

    /**
     * An entity as the transaction would leave it.
     *
     * @param type     the entity's type
     * @param timeline its timeline after the transaction
     */
    record Overlaid(TypeId type, Timeline timeline) {
    }

    /**
     * What the views of one transaction share.
     *
     * @param basis   the basis the transaction would get
     * @param index   the schema the transaction would produce
     * @param stored  the entities of the store, of which the latest timelines are the committed state
     * @param overlay the entities the transaction creates or changes, with the timelines it would give them
     */
    record Source(Basis basis, SchemaIndex index, Map<EntityId, StoredEntity> stored, Map<EntityId, Overlaid> overlay) {

        /** The view of the transaction's state at an effective time. */
        ProvisionalView at(Instant effectiveAt) {
            return new ProvisionalView(Objects.requireNonNull(effectiveAt, "effectiveAt")
                    .truncatedTo(ChronoUnit.MICROS), this);
        }

        private Optional<Overlaid> find(EntityId id) {
            Overlaid overlaid = overlay.get(id);
            if (overlaid != null) {
                return Optional.of(overlaid);
            }
            StoredEntity entity = stored.get(id);
            return entity == null ? Optional.empty() : Optional.of(new Overlaid(entity.type(), entity.latest()));
        }
    }

    private final Instant effectiveAt;
    private final Source source;

    private ProvisionalView(Instant effectiveAt, Source source) {
        this.effectiveAt = effectiveAt;
        this.source = source;
    }

    @Override
    public Instant effectiveAt() {
        return effectiveAt;
    }

    @Override
    public Basis basis() {
        return source.basis();
    }

    @Override
    public Schema schema() {
        return source.index().schema();
    }

    @Override
    public Optional<EntityState> entity(EntityId id) {
        Objects.requireNonNull(id, "id");
        return source.find(id).flatMap(entity -> state(id, entity, entity.timeline().at(effectiveAt)));
    }

    @Override
    public List<EntityState> entities(String entityType) {
        Objects.requireNonNull(entityType, "entityType");
        EntityType type = source.index().schema().entityType(entityType).orElseThrow(() -> new IllegalArgumentException(
                "The schema of the transaction has no entity type '" + entityType + "'."));
        Set<TypeId> types = source.index().typeAndSubtypes(type);
        Map<EntityId, Overlaid> matching = new TreeMap<>();
        for (StoredEntity entity : source.stored().values()) {
            if (types.contains(entity.type()) && !source.overlay().containsKey(entity.id())) {
                matching.put(entity.id(), new Overlaid(entity.type(), entity.latest()));
            }
        }
        source.overlay().forEach((id, entity) -> {
            if (types.contains(entity.type())) {
                matching.put(id, entity);
            }
        });
        List<EntityState> states = new ArrayList<>();
        matching.forEach((id, entity) -> state(id, entity, entity.timeline().at(effectiveAt)).ifPresent(states::add));
        return List.copyOf(states);
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        Objects.requireNonNull(id, "id");
        List<EntityState> states = new ArrayList<>();
        source.find(id).ifPresent(entity -> {
            for (Segment segment : entity.timeline().segments()) {
                state(id, entity, Optional.of(segment)).ifPresent(states::add);
            }
        });
        return List.copyOf(states);
    }

    private Optional<EntityState> state(EntityId id, Overlaid entity, Optional<Segment> segment) {
        return segment.flatMap(found -> EntityStates.of(source.index(), id, entity.type(), found));
    }
}

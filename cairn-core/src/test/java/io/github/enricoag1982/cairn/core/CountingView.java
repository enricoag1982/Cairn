package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.core.query.QueryRunner;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Step;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A test double of {@link View} that delegates to another view and counts the reads of data: everything but the
 * coordinates and the schema. It runs queries on the shared evaluator, as every implementation of {@code View} does.
 */
final class CountingView implements View {

    private final View view;
    private int reads;

    CountingView(View view) {
        this.view = view;
    }

    /** How many times data was read since the view was made. */
    int reads() {
        return reads;
    }

    @Override
    public Instant effectiveAt() {
        return view.effectiveAt();
    }

    @Override
    public Basis basis() {
        return view.basis();
    }

    @Override
    public Schema schema() {
        return view.schema();
    }

    @Override
    public Optional<EntityState> entity(EntityId id) {
        reads++;
        return view.entity(id);
    }

    @Override
    public Optional<EntityState> entity(UniqueKey key) {
        reads++;
        return view.entity(key);
    }

    @Override
    public List<EntityState> entities(String entityType) {
        reads++;
        return view.entities(entityType);
    }

    @Override
    public List<EntityState> timeline(EntityId id) {
        reads++;
        return view.timeline(id);
    }

    @Override
    public Optional<RelationshipState> relationship(RelationshipId id) {
        reads++;
        return view.relationship(id);
    }

    @Override
    public List<RelationshipState> relationshipTimeline(RelationshipId id) {
        reads++;
        return view.relationshipTimeline(id);
    }

    @Override
    public List<Link> navigate(EntityId from, String name) {
        reads++;
        return view.navigate(from, name);
    }

    @Override
    public List<RelationshipState> navigateTimeline(EntityId from, String name) {
        reads++;
        return view.navigateTimeline(from, name);
    }

    @Override
    public List<Step> traverse(EntityId from, String name, Predicate<RelationshipState> follow, int maxDepth) {
        reads++;
        return view.traverse(from, name, follow, maxDepth);
    }

    @Override
    public QueryResult query(Query query) {
        return QueryRunner.run(this, query);
    }
}

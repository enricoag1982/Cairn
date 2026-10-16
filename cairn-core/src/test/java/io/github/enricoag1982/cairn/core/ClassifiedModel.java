package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;

/**
 * The reference model with the test-only classifications that the focused tests of attribute access share (ADR-0036):
 * Account's {@code currency} is {@code operations} and its {@code balance} {@code finance}, so that the schema lists a
 * classification that sorts after another one before it, Organization's {@code lei} is {@code identity}, and Owns'
 * {@code share} is {@code ownership}.
 */
final class ClassifiedModel {

    /** A clearance that covers every classification of {@link #definition()}. */
    static final Clearance ALL = Clearance.of("operations", "finance", "identity", "ownership");

    private ClassifiedModel() {
    }

    /** The reference model with the classifications. */
    static SchemaDefinition definition() {
        SchemaDefinition model = withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
        model = withClassification(model, "Account", "currency", "operations");
        model = withClassification(model, "Organization", "lei", "identity");
        return withClassification(model, "Owns", "share", "ownership");
    }
}

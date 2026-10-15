package io.github.enricoag1982.cairn.documents;

import java.util.List;

/**
 * The keys of transaction payloads of format version 1, per position (design D2 of {@code add-json-payloads}). Write
 * keys mirror the records of the transaction API. The reader accepts exactly these keys.
 */
final class PayloadKeys {

    /** The path of problems about the payload as a whole, and of those that no write is concerned in. */
    static final String TRANSACTION_PATH = "@transaction";

    /** The start of the path of problems about one top-level key of the payload: {@code @transaction.} and the key. */
    static final String TRANSACTION_KEY_PATH = TRANSACTION_PATH + ".";

    /** The start of the path of problems about a write: {@code @write.} and the position of the write, from 0. */
    static final String WRITE_PATH = "@write.";

    /** The keys of the payload; {@value Keys#SCHEMA} names the published JSON Schema for editors and is ignored. */
    static final List<String> TRANSACTION = List.of(
            "cairn", "principal", "onBehalfOf", "reason", "idempotencyKey", "writes", Keys.SCHEMA);
    /** The keys the payload must have. */
    static final List<String> TRANSACTION_REQUIRED = List.of("cairn", "principal", "writes");

    /** The keys that name the kind of a write: a write has exactly one of them. */
    static final List<String> KINDS = List.of("create", "relate", "patch", "replace", "endDate");

    /** The keys of a {@code create}. */
    static final List<String> CREATE = List.of("temporaryId", "id", "entityType", "from", "until", "values");
    /** The keys a {@code create} must have. */
    static final List<String> CREATE_REQUIRED = List.of("entityType", "from");

    /** The keys of a {@code relate}. */
    static final List<String> RELATE = List.of(
            "temporaryId", "id", "relationshipType", "source", "target", "from", "until", "values");
    /** The keys a {@code relate} must have. */
    static final List<String> RELATE_REQUIRED = List.of("relationshipType", "source", "target", "from");

    /** The keys of a {@code patch}. */
    static final List<String> PATCH = List.of("target", "from", "until", "set", "clear", "conflictCheck");
    /** The keys a {@code patch} must have. */
    static final List<String> PATCH_REQUIRED = List.of("target", "from");

    /** The keys of a {@code replace}. */
    static final List<String> REPLACE = List.of("target", "from", "until", "values", "conflictCheck");
    /** The keys a {@code replace} must have. */
    static final List<String> REPLACE_REQUIRED = List.of("target", "from", "values");

    /** The keys of an {@code endDate}. */
    static final List<String> END_DATE = List.of("target", "from", "conflictCheck");
    /** The keys an {@code endDate} must have. */
    static final List<String> END_DATE_REQUIRED = List.of("target", "from");

    /** The keys of a reference to an entity by a unique key (ADR-0026): the entity type and the {@code key}. */
    static final List<String> UNIQUE_KEY = List.of("entityType", "key");
    /** The keys a reference by a unique key must have. */
    static final List<String> UNIQUE_KEY_REQUIRED = List.of("entityType", "key");

    /** The keys of a conflict check: it has exactly one of them, whose value is a basis. */
    static final List<String> CONFLICT_CHECK = List.of("perEntity", "perAttribute");

    private PayloadKeys() {
    }
}

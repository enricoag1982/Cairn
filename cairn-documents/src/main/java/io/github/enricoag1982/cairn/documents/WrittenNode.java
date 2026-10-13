package io.github.enricoag1982.cairn.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tree that a schema document is written from: {@link DocumentWriter} builds it from an origin and a schema
 * definition, and {@link YamlText} and {@link JsonText} render it, so both formats say the same (design D7).
 *
 * <p>A scalar is either a string, which a format quotes as its rules require, or text that is written as it is: a
 * number or {@code true} or {@code false}.
 */
sealed interface WrittenNode {

    /**
     * A mapping; its members keep the order in which they were added. A key may be added twice, because a definition
     * that names two types alike is written as it is, and reading the document then reports the duplicate.
     */
    final class Mapping implements WrittenNode {

        /**
         * One member of a mapping.
         *
         * @param key   the key
         * @param value the value
         */
        record Member(String key, WrittenNode value) {

            /** Rejects null components. */
            Member {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(value, "value");
            }
        }

        private final List<Member> members = new ArrayList<>();

        /**
         * Adds a member.
         *
         * @param key   the key
         * @param value the value
         * @return this mapping
         */
        Mapping put(String key, WrittenNode value) {
            members.add(new Member(key, value));
            return this;
        }

        /**
         * Adds a member with a string as its value.
         *
         * @param key   the key
         * @param value the string
         * @return this mapping
         */
        Mapping put(String key, String value) {
            return put(key, new Text(value));
        }

        /**
         * Adds a member with {@code true} as its value.
         *
         * @param key the key
         * @return this mapping
         */
        Mapping putTrue(String key) {
            return put(key, new Plain("true"));
        }

        /**
         * The members, in the order they were added.
         *
         * @return the members
         */
        List<Member> members() {
            return List.copyOf(members);
        }

        /**
         * Tells whether the mapping has no members.
         *
         * @return {@code true} if it has none
         */
        boolean isEmpty() {
            return members.isEmpty();
        }
    }

    /**
     * A sequence of scalars and mappings.
     *
     * @param items the items, in order
     */
    record Sequence(List<WrittenNode> items) implements WrittenNode {

        /** Copies the items; a sequence of sequences is not part of the format. */
        public Sequence {
            items = List.copyOf(items);
            if (items.stream().anyMatch(item -> item instanceof Sequence)) {
                throw new IllegalArgumentException("a sequence of sequences is not part of schema documents");
            }
        }
    }

    /**
     * A string. A YAML document writes it plain if reading it back gives the same string, and in quotes otherwise.
     *
     * @param value the string
     */
    record Text(String value) implements WrittenNode {

        /** Rejects a null value. */
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A string that is always written in quotes, because a reader that does not know the key could take it for
     * another type: the version of the origin, and the multiplicities.
     *
     * @param value the string
     */
    record Quoted(String value) implements WrittenNode {

        /** Rejects a null value. */
        public Quoted {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Text that is written as it is: a number, {@code true} or {@code false}. It is the same in YAML and in JSON.
     *
     * @param text the text
     */
    record Plain(String text) implements WrittenNode {

        /** Rejects a null text. */
        public Plain {
            Objects.requireNonNull(text, "text");
        }
    }
}

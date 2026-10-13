package io.github.enricoag1982.cairn.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable copies that keep the iteration order of the original. {@code Map.copyOf} and {@code Set.copyOf} do not,
 * and the order of values and attribute names decides the order of problems and of entity states.
 */
final class Copies {

    private Copies() {
    }

    /** Copies a map into an unmodifiable map with the same iteration order; rejects null keys and values. */
    static <K, V> Map<K, V> map(Map<K, V> map, String name) {
        Objects.requireNonNull(map, name);
        Map<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Copies a set into an unmodifiable set with the same iteration order; rejects null elements. */
    static <E> Set<E> set(Set<E> set, String name) {
        Objects.requireNonNull(set, name);
        Set<E> copy = new LinkedHashSet<>();
        for (E element : set) {
            copy.add(Objects.requireNonNull(element, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }
}

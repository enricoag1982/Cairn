package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the writes of a transaction payload are in its text, and what the paths of problems about them mean (capability
 * {@code json-payloads}, design D3).
 *
 * <p>The paths are those that the engine gives the problems of a dry run or a commit:
 *
 * <ul>
 * <li>{@code @transaction} is the root of the payload. {@code @transaction.<key>}, such as
 *     {@code @transaction.principal}, is the top-level key of the payload with that name, which is the pointer
 *     {@code /<key>}, or the root of the payload if the payload has no such key.
 * <li>{@code @write.<n>} is the write at position {@code n}, counted from 0, which is the pointer {@code /writes/<n>};
 *     {@code @write.<n>.<attribute>} is the key of the attribute in that write's {@code values}, {@code set} or
 *     {@code clear}, or the write if it has none. The engine uses these paths for an entity or relationship that a
 *     write creates without a temporary ID or an ID.
 * <li>A temporary ID such as {@code $acme}, or an ID, is the last write of the payload that is about the entity or
 *     relationship it names: a write that creates it, or patches, replaces or end-dates it. Only if there is none, it
 *     is the last write that merely mentions it, as the end of a relationship. A write that names an entity by a unique
 *     key is about the entity that the {@link IdLookup} resolved the key to when the payload was read, at the start of
 *     the write's period (ADR-0026), and mentions it if it is an end of a relationship; the problems of the
 *     transaction about that entity name its ID. If the lookup resolved nothing, such problems are located like those
 *     about a subject that no write names, which is less precise and never wrong. A problem about the key itself has
 *     the path {@code @write.<n>} of its write.
 * <li>A reference and an attribute joined by a dot, such as {@code $acme.name}, is the attribute's key in the last write
 *     about the subject that has one. A write that sets the whole state of the subject (a create, a relate that
 *     creates it, a replace) without the attribute ends the search at the write itself: the attribute is missing
 *     from it. Without any key, the path is located like the subject.
 * <li>A multiplicity such as {@code $a3[HeldBy]} or {@code <id>[holds]} is located like the entity before the bracket.
 * </ul>
 *
 * <p>A temporary ID and the ID that the same create gives the entity name the same subject. A problem whose path names
 * no write (a stored relationship that outlives an entity the payload ends, a target or a relationship a cascade ends,
 * an end touched only through a relationship) is located at the last write that is about a subject its message, found
 * value or expected value names, by the subject's temporary ID or ID as a word of its own, or as
 * {@code @write.<n>}. A problem that names none is located at the root of the payload.
 */
public final class PayloadLocations {

    private static final Pattern WRITE_REFERENCE = Pattern.compile("@write\\.(\\d+)");

    /** The kinds of writes. */
    enum Kind {
        CREATE, RELATE, PATCH, REPLACE, END_DATE
    }

    /**
     * What is known about the location of one write.
     *
     * @param kind       the kind of the write
     * @param location   where the write starts
     * @param own        the temporary ID and ID, as problem paths write them, of the subject the write creates, or the
     *                   reference of the target that it patches, replaces or end-dates
     * @param mentions   the references, as problem paths write them, of the ends of a relationship that a relate relates
     * @param attributes the keys of the write's values, in lower case, with the location of each: the attributes as
     *                   written, and their names in the schema
     */
    record WriteEntry(
            Kind kind,
            DocumentLocation location,
            List<String> own,
            List<String> mentions,
            Map<String, DocumentLocation> attributes) {

        WriteEntry {
            own = List.copyOf(own);
            mentions = List.copyOf(mentions);
            attributes = Map.copyOf(attributes);
        }

        /** Tells whether the write gives its subject all its values: a create, a relate or a replace. */
        boolean setsWholeState() {
            return kind == Kind.CREATE || kind == Kind.RELATE || kind == Kind.REPLACE;
        }
    }

    private final DocumentLocation root;
    /** The top-level keys of the payload, each with the location of the key. */
    private final Map<String, DocumentLocation> keys;
    private final List<WriteEntry> writes;
    /** Every temporary ID and ID that a write creates, targets or relates. */
    private final Set<String> references = new HashSet<>();
    /** The positions of the writes that are about a name, in order. */
    private final Map<String, List<Integer>> aboutByName = new HashMap<>();
    /** The positions of the writes that mention a name as an end of a relationship, in order. */
    private final Map<String, List<Integer>> mentionsByName = new HashMap<>();
    /** For the names a create or relate gives its subject, all of them. */
    private final Map<String, Set<String>> sameSubject = new HashMap<>();

    PayloadLocations(DocumentLocation root, Map<String, DocumentLocation> keys, List<WriteEntry> writes) {
        this.root = Objects.requireNonNull(root, "root");
        this.keys = Map.copyOf(keys);
        this.writes = List.copyOf(writes);
        for (int position = 0; position < this.writes.size(); position++) {
            WriteEntry write = this.writes.get(position);
            for (String name : write.own()) {
                aboutByName.computeIfAbsent(name, key -> new ArrayList<>()).add(position);
                references.add(name);
                if (write.kind() == Kind.CREATE || write.kind() == Kind.RELATE) {
                    sameSubject.computeIfAbsent(name, key -> new HashSet<>()).addAll(write.own());
                }
            }
            for (String name : write.mentions()) {
                mentionsByName.computeIfAbsent(name, key -> new ArrayList<>()).add(position);
                references.add(name);
            }
        }
    }

    /**
     * Returns the root of the payload, where a problem about the transaction as a whole, or about no write, is located.
     *
     * @return the location of the root, with the empty pointer
     */
    public DocumentLocation root() {
        return root;
    }

    /**
     * Finds a write.
     *
     * @param position the position of the write in the payload's list of writes, from 0
     * @return the location of the write, whose pointer is {@code /writes/<position>}, or empty if the payload has no
     *         write at that position
     */
    public Optional<DocumentLocation> write(int position) {
        return position >= 0 && position < writes.size() ? Optional.of(writes.get(position).location()) : Optional.empty();
    }

    /**
     * Finds the place that a problem path names, by the rules in the description of this class. The message of a
     * problem is not considered; see {@link #locate(Problem)}.
     *
     * @param path the path of a problem, such as {@code $acme.name}
     * @return the location, or empty if the path names no write or attribute of the payload; for
     *         {@code @transaction.<key>}, the location of that top-level key, or the root if the payload has no such key
     */
    public Optional<DocumentLocation> find(String path) {
        if (path.equals(PayloadKeys.TRANSACTION_PATH)) {
            return Optional.of(root);
        }
        if (path.startsWith(PayloadKeys.TRANSACTION_KEY_PATH)) {
            return Optional.of(keys.getOrDefault(path.substring(PayloadKeys.TRANSACTION_KEY_PATH.length()), root));
        }
        String base = withoutMultiplicity(path);
        if (base.startsWith(PayloadKeys.WRITE_PATH)) {
            return writePath(base.substring(PayloadKeys.WRITE_PATH.length()));
        }
        return referencePath(base);
    }

    /**
     * Locates a path.
     *
     * @param path the path of a problem
     * @return the location the path names, or the root of the payload if it names none
     */
    public DocumentLocation of(String path) {
        return find(path).orElse(root);
    }

    /**
     * Locates a problem: by its path if the path names a write or an attribute of the payload, else at the last write
     * that is about a subject that the problem's message, found value or expected value names, else at the root. A
     * problem that is located already is returned as it is.
     *
     * @param problem the problem
     * @return the located problem
     */
    public Problem locate(Problem problem) {
        if (problem.location().isPresent()) {
            return problem;
        }
        return problem.at(find(problem.path()).or(() -> mentioned(problem)).orElse(root));
    }

    /**
     * Locates problems. Problems that are located already are returned as they are.
     *
     * @param problems the problems
     * @return the located problems, in the same order
     */
    public List<Problem> locate(List<Problem> problems) {
        return problems.stream().map(this::locate).toList();
    }

    // ------------------------------------------------------------------ paths

    /** A multiplicity path names the entity before the bracket. */
    private static String withoutMultiplicity(String path) {
        if (path.endsWith("]")) {
            int open = path.lastIndexOf('[');
            if (open > 0) {
                return path.substring(0, open);
            }
        }
        return path;
    }

    /** The path after {@code @write.}: a position, and perhaps an attribute. */
    private Optional<DocumentLocation> writePath(String rest) {
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 9) {
            return Optional.empty();
        }
        int position = Integer.parseInt(rest.substring(0, end));
        if (position >= writes.size()) {
            return Optional.empty();
        }
        WriteEntry write = writes.get(position);
        String remainder = rest.substring(end);
        if (remainder.isEmpty()) {
            return Optional.of(write.location());
        }
        if (!remainder.startsWith(".")) {
            return Optional.empty();
        }
        return Optional.of(write.attributes().getOrDefault(DocumentLocations.key(remainder.substring(1)), write.location()));
    }

    /** A temporary ID or an ID, with or without an attribute after a dot. */
    private Optional<DocumentLocation> referencePath(String base) {
        String reference = referenceIn(base);
        if (reference == null) {
            return Optional.empty();
        }
        if (base.equals(reference)) {
            OptionalInt write = subjectWrite(reference);
            return write.isPresent() ? Optional.of(writes.get(write.getAsInt()).location()) : Optional.empty();
        }
        return attributeLocation(reference, base.substring(reference.length() + 1));
    }

    /** The longest reference of the payload that a text starts with, ended by a dot or by the end of the text. */
    private String referenceIn(String text) {
        if (references.contains(text)) {
            return text;
        }
        for (int dot = text.lastIndexOf('.'); dot > 0; dot = text.lastIndexOf('.', dot - 1)) {
            String prefix = text.substring(0, dot);
            if (references.contains(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ subjects

    /** Every name of a subject: the reference, and the other name that the write which creates it gives it. */
    private Set<String> namesOf(String reference) {
        return sameSubject.getOrDefault(reference, Set.of(reference));
    }

    /** The positions of the writes about a subject, in order. */
    private List<Integer> aboutSubject(Set<String> names) {
        return names.stream().flatMap(name -> aboutByName.getOrDefault(name, List.of()).stream()).distinct().sorted().toList();
    }

    /** The last write about a subject, else the last that mentions it. */
    private OptionalInt subjectWrite(String reference) {
        Set<String> names = namesOf(reference);
        OptionalInt about = names.stream().flatMap(name -> aboutByName.getOrDefault(name, List.of()).stream())
                .mapToInt(Integer::intValue).max();
        if (about.isPresent()) {
            return about;
        }
        return names.stream().flatMap(name -> mentionsByName.getOrDefault(name, List.of()).stream())
                .mapToInt(Integer::intValue).max();
    }

    /** The key of an attribute in the last write about a subject that has it; see the description of this class. */
    private Optional<DocumentLocation> attributeLocation(String reference, String attribute) {
        List<Integer> about = aboutSubject(namesOf(reference));
        String key = DocumentLocations.key(attribute);
        for (int i = about.size() - 1; i >= 0; i--) {
            WriteEntry write = writes.get(about.get(i));
            DocumentLocation at = write.attributes().get(key);
            if (at != null) {
                return Optional.of(at);
            }
            if (write.setsWholeState()) {
                return Optional.of(write.location());
            }
        }
        OptionalInt write = subjectWrite(reference);
        return write.isPresent() ? Optional.of(writes.get(write.getAsInt()).location()) : Optional.empty();
    }

    // ------------------------------------------------------------------ problems about no write

    /** The last write that is about a subject the problem names in its message, found value or expected value. */
    private Optional<DocumentLocation> mentioned(Problem problem) {
        int last = -1;
        for (String text : List.of(problem.message(), problem.found().orElse(""), problem.expected().orElse(""))) {
            for (String word : words(text)) {
                String reference = referenceIn(word);
                if (reference != null) {
                    last = Math.max(last, subjectWrite(reference).orElse(-1));
                }
            }
            Matcher positions = WRITE_REFERENCE.matcher(text);
            while (positions.find()) {
                String digits = positions.group(1);
                if (digits.length() <= 9 && Integer.parseInt(digits) < writes.size()) {
                    last = Math.max(last, Integer.parseInt(digits));
                }
            }
        }
        return last < 0 ? Optional.empty() : Optional.of(writes.get(last).location());
    }

    /**
     * The words of a text, which are separated by white space, without the punctuation around them: a reference is a
     * word of its own, not a part of a longer name, and an attribute may follow it after a dot.
     */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String raw : text.split("\\s+")) {
            int start = 0;
            int end = raw.length();
            while (start < end && "([{\"'<".indexOf(raw.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && ")]}\"'>,;:.".indexOf(raw.charAt(end - 1)) >= 0) {
                end--;
            }
            if (start < end) {
                String word = raw.substring(start, end);
                int bracket = word.indexOf('[');
                words.add(bracket > 0 ? word.substring(0, bracket) : word);
            }
        }
        return words;
    }

    // ------------------------------------------------------------------ equality

    @Override
    public boolean equals(Object other) {
        return other instanceof PayloadLocations that && root.equals(that.root) && keys.equals(that.keys)
                && writes.equals(that.writes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, keys, writes);
    }

    @Override
    public String toString() {
        return "PayloadLocations" + writes.stream().map(WriteEntry::location).toList();
    }
}

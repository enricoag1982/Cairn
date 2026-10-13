package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.core.data.Timeline.Stretch;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the rules of a store read when they last checked (design D3 of {@code add-rule-rechecks}, ADR-0025): for each
 * entity or relationship and each rule with code that its type names, a list of {@link Check}s, each the effective
 * period that one run of the rule covered and the reads it made, and an inverse map from each key to the checks that read
 * it. A transaction asks which checks its {@linkplain Changes changes} reach, and runs those rules again.
 *
 * <p>The index is writer-only state, kept beside the latest timelines: only the thread that holds the store's writer lock
 * reads or changes it, and views and readers never see it. A transaction does not change it while it is prepared. It
 * collects a {@link Stage}, which the store applies when the transaction commits, so that a dry run, or a transaction
 * that is rejected, leaves the index as it was.
 *
 * <p>A purge drops the checks of the entities and relationships it removes, and the keys that mention them from the
 * other checks, so that no purged ID stays in the index (ADR-0024).
 */
final class ReadIndex {

    /**
     * One run of a rule for one version: the period the version had, which writes can cut down to parts, and what the
     * rule read. A check that reads nothing is not kept, because no change can reach it.
     */
    static final class Check {

        private final Id subject;
        private final RuleReference rule;
        private List<Stretch> periods;
        private final Map<ReadKey, Reads.Times> reads;

        private Check(Id subject, RuleReference rule, Stretch period, Map<ReadKey, Reads.Times> reads) {
            this.subject = subject;
            this.rule = rule;
            this.periods = List.of(period);
            this.reads = reads;
        }

        /** The entity or relationship the rule checked. */
        Id subject() {
            return subject;
        }

        /** The rule that ran. */
        RuleReference rule() {
            return rule;
        }

        /** The parts of the checked version's period that are left, in order and not touching. */
        List<Stretch> periods() {
            return periods;
        }

        /** What the rule read, by key; a view of the check's own map. */
        Map<ReadKey, Reads.Times> reads() {
            return Collections.unmodifiableMap(reads);
        }

        /** Cuts a stretch out of the period; tells whether nothing of the period is left. */
        private boolean cut(Stretch stretch) {
            List<Stretch> left = new ArrayList<>();
            for (Stretch period : periods) {
                if (!period.from().isBefore(stretch.end()) || !stretch.from().isBefore(period.end())) {
                    left.add(period);
                    continue;
                }
                if (period.from().isBefore(stretch.from())) {
                    left.add(new Stretch(period.from(), stretch.from()));
                }
                if (stretch.end().isBefore(period.end())) {
                    left.add(new Stretch(stretch.end(), period.end()));
                }
            }
            periods = List.copyOf(left);
            return periods.isEmpty();
        }
    }

    /**
     * The checks that a transaction would change, which the store applies if the transaction commits: the parts of
     * checks that its writes cut off, the checks of rules that the type no longer names, and the runs of rules, which
     * replace the older checks of the same version. Used by one preparation, in the writer thread.
     */
    static final class Stage {

        private record Run(Id subject, RuleReference rule, Stretch period, Reads reads) {
        }

        private final Map<Id, List<Stretch>> cuts = new HashMap<>();
        private final Set<Check> dropped = new LinkedHashSet<>();
        private final List<Run> runs = new ArrayList<>();

        /** A run of a rule for the version that has a period; the rule's reads replace older reads of those instants. */
        void ran(Id subject, RuleReference rule, Instant from, Instant end, Reads reads) {
            runs.add(new Run(subject, rule, new Stretch(from, end), reads));
        }

        /** The stretches of effective time in which the writes change an entity or relationship, by ID. */
        void cut(Map<Id, List<Stretch>> changed) {
            changed.forEach((id, stretches) -> cuts.computeIfAbsent(id, key -> new ArrayList<>()).addAll(stretches));
        }

        /** A check whose rule its subject's type no longer names. */
        void drop(Check check) {
            dropped.add(check);
        }
    }

    private final Map<Id, Map<RuleReference, List<Check>>> bySubject = new HashMap<>();
    private final Map<ReadKey, Set<Check>> byKey = new HashMap<>();

    /**
     * Applies what a committed transaction staged: the cuts of its writes, the drops, and then the runs. A run replaces
     * the parts of older checks of the same entity or relationship and rule that its period covers.
     *
     * @param stage the staged checks
     */
    void apply(Stage stage) {
        stage.cuts.forEach(this::cut);
        stage.dropped.forEach(this::remove);
        for (Stage.Run run : stage.runs) {
            replace(run);
        }
    }

    /** Cuts stretches out of the periods of every check of an entity or relationship; drops the ones left empty. */
    private void cut(Id subject, List<Stretch> stretches) {
        Map<RuleReference, List<Check>> rules = bySubject.get(subject);
        if (rules == null) {
            return;
        }
        for (List<Check> checks : List.copyOf(rules.values())) {
            for (Check check : List.copyOf(checks)) {
                boolean empty = false;
                for (Stretch stretch : stretches) {
                    empty = check.cut(stretch);
                    if (empty) {
                        break;
                    }
                }
                if (empty) {
                    remove(check);
                }
            }
        }
    }

    private void replace(Stage.Run run) {
        Map<RuleReference, List<Check>> rules = bySubject.get(run.subject());
        if (rules != null && rules.containsKey(run.rule())) {
            for (Check check : List.copyOf(rules.get(run.rule()))) {
                if (check.cut(run.period())) {
                    remove(check);
                }
            }
        }
        if (run.reads().isEmpty()) {
            return;
        }
        Check check = new Check(run.subject(), run.rule(), run.period(), run.reads().times());
        bySubject.computeIfAbsent(run.subject(), id -> new HashMap<>())
                .computeIfAbsent(run.rule(), rule -> new ArrayList<>()).add(check);
        for (ReadKey key : check.reads.keySet()) {
            byKey.computeIfAbsent(key, k -> new HashSet<>()).add(check);
        }
    }

    /** Removes a check from the index, with the entries of its keys that no other check shares. */
    private void remove(Check check) {
        Map<RuleReference, List<Check>> rules = bySubject.get(check.subject);
        if (rules != null) {
            List<Check> checks = rules.get(check.rule);
            if (checks != null) {
                checks.remove(check);
                if (checks.isEmpty()) {
                    rules.remove(check.rule);
                }
            }
            if (rules.isEmpty()) {
                bySubject.remove(check.subject);
            }
        }
        for (ReadKey key : check.reads.keySet()) {
            Set<Check> readers = byKey.get(key);
            if (readers != null) {
                readers.remove(check);
                if (readers.isEmpty()) {
                    byKey.remove(key);
                }
            }
        }
    }

    /**
     * Finds the checks that changes reach: those that read a key that changed, at an effective time in a stretch in which
     * it changed, or across effective time.
     *
     * @param changes the stretches of effective time in which each key changes
     * @return the checks, in no particular order
     */
    Set<Check> hits(Map<ReadKey, List<Stretch>> changes) {
        Set<Check> hits = new HashSet<>();
        changes.forEach((key, stretches) -> {
            for (Check check : byKey.getOrDefault(key, Set.of())) {
                if (!hits.contains(check) && check.reads.get(key).hitBy(stretches)) {
                    hits.add(check);
                }
            }
        });
        return hits;
    }

    /**
     * Removes what a purge removes (ADR-0024): the checks of the entities and relationships it removes, and every key
     * that mentions one of them from the other checks, which are dropped if nothing is left to read.
     *
     * @param removed the IDs of the entities and relationships the purge removes
     */
    void purge(Collection<? extends Id> removed) {
        Set<String> ids = new HashSet<>();
        removed.forEach(id -> ids.add(id.value()));
        for (Id id : removed) {
            Map<RuleReference, List<Check>> rules = bySubject.get(id);
            if (rules != null) {
                rules.values().stream().flatMap(List::stream).toList().forEach(this::remove);
            }
        }
        for (ReadKey key : List.copyOf(byKey.keySet())) {
            if (!key.mentions(ids)) {
                continue;
            }
            for (Check check : List.copyOf(byKey.remove(key))) {
                check.reads.remove(key);
                if (check.reads.isEmpty()) {
                    remove(check);
                }
            }
        }
    }

    // ------------------------------------------------------------------ for tests and for the equivalence of stores

    /** The number of checks. */
    int size() {
        return bySubject.values().stream().flatMap(rules -> rules.values().stream()).mapToInt(List::size).sum();
    }

    /** The checks of an entity or relationship for a rule, by the start of their first period. */
    List<Check> checks(Id subject, RuleReference rule) {
        Map<RuleReference, List<Check>> rules = bySubject.get(subject);
        List<Check> checks = rules == null ? List.of() : rules.getOrDefault(rule, List.of());
        return checks.stream().sorted(Comparator.comparing(check -> check.periods().getFirst().from()))
                .toList();
    }

    /** The checks that read a key. */
    Set<Check> readers(ReadKey key) {
        return Set.copyOf(byKey.getOrDefault(key, Set.of()));
    }

    /** The value of every ID that the index mentions, as the subject of a check or in a key; no purged ID is among them. */
    Set<String> mentionedIds() {
        Set<String> ids = new TreeSet<>();
        bySubject.keySet().forEach(id -> ids.add(id.value()));
        byKey.keySet().stream().map(ReadKey::mentioned).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    /**
     * A description of the index that does not depend on the order of anything: for each entity or relationship and rule,
     * the checks' reads as keys with the instants or {@code *} for across effective time, without the periods, which a
     * trimmed check and a check that was made again can split differently. For tests that compare two stores.
     */
    Set<String> describe() {
        Set<String> lines = new TreeSet<>();
        for (Map<RuleReference, List<Check>> rules : bySubject.values()) {
            for (List<Check> checks : rules.values()) {
                for (Check check : checks) {
                    check.reads.forEach((key, times) -> lines.add(check.subject.value() + " " + check.rule.name() + " "
                            + key + " " + (times.acrossTime() ? "*" : times.instants())));
                }
            }
        }
        return lines;
    }

    /** Tells whether the index holds the same inverse map as the checks need: every key leads to the checks that read it. */
    boolean consistent() {
        Map<ReadKey, Set<Check>> expected = new HashMap<>();
        for (Map<RuleReference, List<Check>> rules : bySubject.values()) {
            for (List<Check> checks : rules.values()) {
                for (Check check : checks) {
                    if (check.periods.isEmpty() || check.reads.isEmpty()) {
                        return false;
                    }
                    check.reads.keySet().forEach(key -> expected.computeIfAbsent(key, k -> new HashSet<>()).add(check));
                }
            }
        }
        return expected.equals(byKey);
    }
}

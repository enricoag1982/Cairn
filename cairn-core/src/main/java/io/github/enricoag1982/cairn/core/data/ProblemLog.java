package io.github.enricoag1982.cairn.core.data;

import io.github.enricoag1982.cairn.schema.Problem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The problems a {@link Preparation} finds, in the order in which its parts report them (design D5 and D6 of
 * {@code add-entity-versions}): those of the transaction itself, then those of each write in the order of the writes,
 * then those of each entity and relationship (the subjects) in the order in which the writes first touched them, and
 * last those of the rules. The parts append in that order, so the log never sorts.
 *
 * <p>Used by one {@link Preparation}, in the thread that holds the store's writer lock.
 */
final class ProblemLog {

    private final List<Problem> problems = new ArrayList<>();

    void add(Problem problem) {
        problems.add(problem);
    }

    void addAll(Collection<? extends Problem> found) {
        problems.addAll(found);
    }

    /** A copy of the problems so far, in order. */
    List<Problem> all() {
        return List.copyOf(problems);
    }

    /**
     * Tells whether a problem found so far is about a subject: the subject itself, one of its attributes, or, for an
     * entity, one of its relationship types, such as {@code $a3[HeldBy]}. A subject is called by its path, and by its
     * ID if that differs.
     */
    boolean concerns(Working subject) {
        for (Problem problem : problems) {
            if (isAbout(problem.path(), subject.path) || isAbout(problem.path(), subject.id.value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAbout(String problemPath, String subjectPath) {
        return problemPath.equals(subjectPath) || problemPath.startsWith(subjectPath + ".")
                || problemPath.startsWith(subjectPath + "[");
    }
}

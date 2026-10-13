package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.NamedRules;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.Submission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads schema documents and checks them together, as a schema registry would register them (design D5 of
 * {@code add-java-type-generation}).
 *
 * <p>The registry declares, without code, every rule that the documents name, for the closest common supertype of the
 * types that name it. Documents that refer to each other cannot be registered in any one order, so they are
 * registered in passes: each pass tries every document that is not registered yet, and passes go on until one
 * registers nothing. The problems of the documents that are left are those their last registration reported.
 */
final class Checker {

    /** The principal that the generator's registrations are recorded with; nothing reads it. */
    private static final Submission SUBMISSION = Submission.by("cairn-codegen");

    private Checker() {
    }

    /**
     * What checking found.
     *
     * @param schema   the schema of the documents; empty if there are problems
     * @param rules    the rules that the documents name
     * @param problems every problem found, located in its document
     */
    record Checked(Schema schema, List<NamedRules.Named> rules, List<DocumentProblem> problems) {
    }

    /**
     * Reads and registers the documents.
     *
     * @param files the documents, in any order
     * @return the schema and rules, or every problem
     * @throws UncheckedIOException if a document cannot be read
     */
    static Checked check(List<Path> files) {
        List<DocumentProblem> problems = new ArrayList<>();
        List<Path> readable = new ArrayList<>();
        List<SchemaDocument> documents = new ArrayList<>();
        for (Path file : files) {
            switch (SchemaDocuments.read(text(file))) {
                case DocumentReadResult.Read read -> {
                    readable.add(file);
                    documents.add(read.document());
                }
                case DocumentReadResult.Invalid invalid ->
                    invalid.problems().forEach(problem -> problems.add(new DocumentProblem(file, problem)));
            }
        }

        List<NamedRules.Named> named = NamedRules.in(documents, Set.of());
        SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.system(), named.stream()
                .map(rule -> new RuleDescriptor(rule.rule().name(), rule.rule().version(),
                        "Named by the schema document " + readable.get(rule.document()).getFileName() + ".",
                        rule.appliesTo(), List.of()))
                .toList());

        Map<Integer, List<Problem>> pending = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            pending.put(i, List.of());
        }
        boolean registered = true;
        while (registered && !pending.isEmpty()) {
            registered = false;
            for (int i : new ArrayList<>(pending.keySet())) {
                SchemaDocument document = documents.get(i);
                switch (registry.register(document.origin(), document.definition(), SUBMISSION)) {
                    case RegistrationResult.Applied applied -> {
                        pending.remove(i);
                        registered = true;
                    }
                    case RegistrationResult.Unchanged unchanged -> {
                        pending.remove(i);
                        registered = true;
                    }
                    case RegistrationResult.Rejected rejected -> pending.put(i, rejected.problems());
                }
            }
        }

        Set<Integer> left = new HashSet<>(pending.keySet());
        for (int i = 0; i < documents.size(); i++) {
            if (left.contains(i)) {
                for (Problem problem : documents.get(i).locate(pending.get(i))) {
                    problems.add(new DocumentProblem(readable.get(i), problem));
                }
            }
        }
        problems.sort(Comparator.comparingInt(problem -> files.indexOf(problem.document())));
        return new Checked(registry.current(), named, problems);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The schema document " + file + " cannot be read: " + e.getMessage(), e);
        }
    }
}

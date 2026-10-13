package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.Origin;
import io.github.enricoag1982.sysrec.schema.Problem;
import io.github.enricoag1982.sysrec.schema.ProblemCode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Rules for origins: well-formed names and versions, and version order (capability {@code schema-evolution}). */
final class OriginRules {

    static final String PATH = "@origin";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    private OriginRules() {
    }

    static List<Problem> validate(Origin origin) {
        List<Problem> problems = new ArrayList<>();
        if (!NAME.matcher(origin.name()).matches()) {
            problems.add(new Problem(ProblemCode.INVALID_ORIGIN, PATH, "The origin name '" + origin.name()
                    + "' must be non-empty and contain only letters, digits, dots, hyphens and underscores."));
        }
        if (!VERSION.matcher(origin.version()).matches()) {
            problems.add(new Problem(ProblemCode.INVALID_ORIGIN, PATH, "The version '" + origin.version()
                    + "' of origin " + origin.name() + " must be numbers separated by dots, such as 1.4.0."));
        }
        return problems;
    }

    /** Compares two well-formed versions number by number; missing numbers count as 0. */
    static int compareVersions(String first, String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            BigInteger x = i < a.length ? new BigInteger(a[i]) : BigInteger.ZERO;
            BigInteger y = i < b.length ? new BigInteger(b[i]) : BigInteger.ZERO;
            int order = x.compareTo(y);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }
}

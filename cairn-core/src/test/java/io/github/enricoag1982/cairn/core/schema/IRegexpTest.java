package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Matching and translating I-Regexp patterns with RE2/J (ADR-0020). */
class IRegexpTest {

    @Nested
    class Matching {

        @Test
        void patternsOfTheReferenceDomain() {
            IRegexp currency = IRegexp.of("[A-Z]{3}");
            IRegexp lei = IRegexp.of("[A-Z0-9]{18}[0-9]{2}");

            assertThat(currency.matches("EUR")).isTrue();
            assertThat(currency.matches("eur")).isFalse();
            assertThat(currency.matches("EURO")).isFalse();
            assertThat(currency.matches("EU")).isFalse();
            assertThat(currency.matches("")).isFalse();
            assertThat(lei.matches("5493001KJTIIGC8Y1R12")).isTrue();
            assertThat(lei.matches("ABC")).isFalse();
            assertThat(lei.matches("5493001KJTIIGC8Y1R1X")).isFalse();
        }

        @Test
        void aPatternMatchesAValueAsAWhole() {
            IRegexp pattern = IRegexp.of("a");

            assertThat(pattern.matches("a")).isTrue();
            assertThat(pattern.matches("ab")).isFalse();
            assertThat(pattern.matches("ba")).isFalse();
            assertThat(IRegexp.of("a|ab").matches("ab")).isTrue();
            assertThat(IRegexp.of("a|b").matches("ab")).isFalse();
        }

        @Test
        void theEmptyPatternMatchesOnlyTheEmptyValue() {
            assertThat(IRegexp.of("").matches("")).isTrue();
            assertThat(IRegexp.of("").matches("a")).isFalse();
        }

        @Test
        void quantifiersGroupsAndAlternatives() {
            assertThat(IRegexp.of("(EUR|USD)[0-9]{2,3}").matches("USD42")).isTrue();
            assertThat(IRegexp.of("(EUR|USD)[0-9]{2,3}").matches("USD4")).isFalse();
            assertThat(IRegexp.of("(EUR|USD)[0-9]{2,3}").matches("USD4242")).isFalse();
            assertThat(IRegexp.of("a?b*c+").matches("bbc")).isTrue();
            assertThat(IRegexp.of("a?b*c+").matches("abb")).isFalse();
            assertThat(IRegexp.of("a{2,}").matches("aaaaa")).isTrue();
            assertThat(IRegexp.of("a{2,}").matches("a")).isFalse();
        }

        @Test
        void theDotDoesNotMatchLineBreaks() {
            IRegexp pattern = IRegexp.of("a.c");

            assertThat(pattern.matches("abc")).isTrue();
            assertThat(pattern.matches("a c")).isTrue();
            assertThat(pattern.matches("a\nc")).isFalse();
            assertThat(pattern.matches("a\rc")).isFalse();
            assertThat(pattern.matches("a\u2028c")).isTrue();
            assertThat(IRegexp.of("[.]").matches("x")).isFalse();
            assertThat(IRegexp.of("[.]").matches(".")).isTrue();
            assertThat(IRegexp.of("\\.").matches("x")).isFalse();
        }

        @Test
        void aDollarSignIsMatchedLiterally() {
            IRegexp pattern = IRegexp.of("a$");

            assertThat(pattern.matches("a$")).isTrue();
            assertThat(pattern.matches("a")).isFalse();
            assertThat(pattern.matches("a\n")).isFalse();
            assertThat(IRegexp.of("$").matches("$")).isTrue();
            assertThat(IRegexp.of("[$]").matches("$")).isTrue();
        }

        @Test
        void aCaretIsMatchedLiterally() {
            IRegexp pattern = IRegexp.of("^a");

            assertThat(pattern.matches("^a")).isTrue();
            assertThat(pattern.matches("a")).isFalse();
            assertThat(IRegexp.of("^[A-Z]{3}$").matches("^EUR$")).isTrue();
            assertThat(IRegexp.of("^[A-Z]{3}$").matches("EUR")).isFalse();
            assertThat(IRegexp.of("[^^]").matches("a")).isTrue();
            assertThat(IRegexp.of("[^^]").matches("^")).isFalse();
            assertThat(IRegexp.of("[a^]").matches("^")).isTrue();
        }

        @Test
        void escapesMatchTheCharactersTheyStandFor() {
            assertThat(IRegexp.of("\\(\\)\\*\\+\\-\\.\\?\\[\\\\\\]\\^\\{\\|\\}").matches("()*+-.?[\\]^{|}")).isTrue();
            assertThat(IRegexp.of("\\n\\r\\t").matches("\n\r\t")).isTrue();
            assertThat(IRegexp.of("[\\n\\r\\t]+").matches("\t\n")).isTrue();
            assertThat(IRegexp.of("[\\-\\]\\^\\[\\\\]+").matches("-]^[\\")).isTrue();
        }

        @Test
        void classesRangesAndNegation() {
            assertThat(IRegexp.of("[a-c]+").matches("abcba")).isTrue();
            assertThat(IRegexp.of("[a-c]+").matches("abd")).isFalse();
            assertThat(IRegexp.of("[^a-c]+").matches("xyz")).isTrue();
            assertThat(IRegexp.of("[^a-c]+").matches("xay")).isFalse();
            assertThat(IRegexp.of("[-a]+").matches("a-a")).isTrue();
            assertThat(IRegexp.of("[a-]+").matches("a-a")).isTrue();
            assertThat(IRegexp.of("[a-z-]+").matches("a-z")).isTrue();
            assertThat(IRegexp.of("[^\\n\\r]+").matches("a\nb")).isFalse();
        }

        @Test
        void unicodeCategories() {
            assertThat(IRegexp.of("\\p{Lu}").matches("A")).isTrue();
            assertThat(IRegexp.of("\\p{Lu}").matches("a")).isFalse();
            assertThat(IRegexp.of("\\p{Lu}+").matches("\u00c0\u00c9")).isTrue();
            assertThat(IRegexp.of("\\p{Lu}+").matches("\u00c0\u00e9")).isFalse();
            assertThat(IRegexp.of("\\P{Lu}").matches("a")).isTrue();
            assertThat(IRegexp.of("\\P{Lu}").matches("A")).isFalse();
            assertThat(IRegexp.of("\\p{L}+").matches("\u4e2d\u6587abc")).isTrue();
            assertThat(IRegexp.of("\\p{Nd}{2}").matches("\u0663\u0664")).isTrue();
            assertThat(IRegexp.of("\\p{N}").matches("a")).isFalse();
            assertThat(IRegexp.of("\\p{Sc}").matches("\u20ac")).isTrue();
            assertThat(IRegexp.of("\\p{Zs}").matches(" ")).isTrue();
            assertThat(IRegexp.of("[\\p{L}\\p{Nd}_-]+").matches("caf\u00e9_42-x")).isTrue();
            assertThat(IRegexp.of("[^\\p{L}]").matches("1")).isTrue();
            assertThat(IRegexp.of("[^\\p{L}]").matches("a")).isFalse();
            assertThat(IRegexp.of("[\\P{L}]").matches("a")).isFalse();
        }

        @Test
        void supplementaryCharactersAreOneCharacter() {
            String clef = "\uD834\uDD1E";

            assertThat(IRegexp.of(".").matches(clef)).isTrue();
            assertThat(IRegexp.of("..").matches(clef)).isFalse();
            assertThat(IRegexp.of(clef).matches(clef)).isTrue();
            assertThat(IRegexp.of("[" + clef + "]").matches(clef)).isTrue();
            assertThat(IRegexp.of("[\uD834\uDD1E-\uD834\uDD22]").matches("\uD834\uDD20")).isTrue();
            assertThat(IRegexp.of("[^a]").matches(clef)).isTrue();
        }

        @Test
        void aPatternWithCatastrophicBacktrackingMatchesALongValueQuickly() {
            IRegexp pattern = IRegexp.of("(a+)+b");
            String value = "a".repeat(10_000) + "c";

            boolean matched = assertTimeoutPreemptively(Duration.ofMillis(900), () -> pattern.matches(value));

            assertThat(matched).isFalse();
            assertThat(pattern.matches("aaab")).isTrue();
            assertThat(pattern.matches("b")).isFalse();
        }

        @Test
        void otherPatternsThatBacktrackExponentiallyAreLinearToo() {
            String value = "a".repeat(10_000) + "!";

            assertTimeoutPreemptively(Duration.ofMillis(900), () -> {
                assertThat(IRegexp.of("(a|aa)+$").matches(value)).isFalse();
                assertThat(IRegexp.of("(a*)*b").matches(value)).isFalse();
                assertThat(IRegexp.of("(.*a){20}").matches(value)).isFalse();
            });
        }
    }

    @Nested
    class AtTheLimits {

        @Test
        void patternsAtTheLimitsCompileAndMatchWithoutOverflowingTheStack() {
            for (String pattern : List.of("()".repeat(500), "a?".repeat(500), "(a*)".repeat(250), "a{0,500}",
                    "(a?){100}", "((a?){10}){10}", "(ab|cd|ef)".repeat(100), "ab|".repeat(500) + "ab",
                    "(".repeat(100) + "a" + ")".repeat(100), "(a{99}){100}", "\\p{L}{1000}".repeat(10))) {
                IRegexp parsed = IRegexp.of(pattern);

                assertThat(parsed.matches("a".repeat(2_000) + "!")).as(pattern).isFalse();
            }
            assertThat(IRegexp.of("()".repeat(500)).matches("")).isTrue();
            assertThat(IRegexp.of("a?".repeat(500)).matches("a".repeat(500))).isTrue();
            assertThat(IRegexp.of("a?".repeat(500)).matches("a".repeat(501))).isFalse();
            assertThat(IRegexp.of("(a*)".repeat(250)).matches("a".repeat(1_000))).isTrue();
            assertThat(IRegexp.of("a{0,500}").matches("a".repeat(500))).isTrue();
            assertThat(IRegexp.of("a{0,500}").matches("a".repeat(501))).isFalse();
            assertThat(IRegexp.of("ab|".repeat(500) + "ab").matches("ab")).isTrue();
        }

        @Test
        void parsingNeverThrowsWhateverTheStackIs() throws InterruptedException {
            List<Throwable> thrown = new ArrayList<>();
            List<IRegexp.Parsed> results = new ArrayList<>();
            Thread thread = new Thread(null, () -> {
                try {
                    results.add(IRegexp.parse("a{0,500}"));
                    results.add(IRegexp.parse("()".repeat(500)));
                } catch (Throwable e) {
                    thrown.add(e);
                }
            }, "small-stack", 160 * 1024);
            thread.start();
            thread.join();

            assertThat(thrown).isEmpty();
            assertThat(results).hasSize(2);
        }
    }

    @Nested
    class Translating {

        @Test
        void re2IsAnchoredAtBothEnds() {
            assertThat(IRegexp.of("[A-Z]{3}").re2()).isEqualTo("\\A(?:[A-Z]{3})\\z");
            assertThat(IRegexp.of("").re2()).isEqualTo("\\A(?:)\\z");
            assertThat(IRegexp.of("a|b").re2()).isEqualTo("\\A(?:a|b)\\z");
        }

        @Test
        void ecmaScriptIsAnchoredAtBothEnds() {
            assertThat(IRegexp.of("[A-Z]{3}").ecmaScript()).isEqualTo("^(?:[A-Z]{3})$");
            assertThat(IRegexp.of("").ecmaScript()).isEqualTo("^(?:)$");
            assertThat(IRegexp.of("a|b").ecmaScript()).isEqualTo("^(?:a|b)$");
        }

        @Test
        void everyUnescapedDotOutsideAClassBecomesAClassWithoutLineBreaks() {
            assertThat(IRegexp.of("a.b").re2()).isEqualTo("\\A(?:a[^\\n\\r]b)\\z");
            assertThat(IRegexp.of("a.b").ecmaScript()).isEqualTo("^(?:a[^\\n\\r]b)$");
            assertThat(IRegexp.of(".*.").re2()).isEqualTo("\\A(?:[^\\n\\r]*[^\\n\\r])\\z");
            assertThat(IRegexp.of("\\.").re2()).isEqualTo("\\A(?:\\.)\\z");
            assertThat(IRegexp.of("[.]").re2()).isEqualTo("\\A(?:[.])\\z");
            assertThat(IRegexp.of("[a.]b.").ecmaScript()).isEqualTo("^(?:[a.]b[^\\n\\r])$");
            assertThat(IRegexp.of("[\\].]").re2()).isEqualTo("\\A(?:[\\].])\\z");
            assertThat(IRegexp.of("\\\\.").re2()).isEqualTo("\\A(?:\\\\[^\\n\\r])\\z");
            assertThat(IRegexp.of("\\p{L}.").re2()).isEqualTo("\\A(?:\\p{L}[^\\n\\r])\\z");
        }

        @Test
        void caretAndDollarOutsideAClassAreEscapedBecauseTheyAreLiteral() {
            assertThat(IRegexp.of("a$").re2()).isEqualTo("\\A(?:a\\$)\\z");
            assertThat(IRegexp.of("^a").ecmaScript()).isEqualTo("^(?:\\^a)$");
            assertThat(IRegexp.of("[^a$]").re2()).isEqualTo("\\A(?:[^a$])\\z");
            assertThat(IRegexp.of("[a^]").ecmaScript()).isEqualTo("^(?:[a^])$");
            assertThat(IRegexp.of("\\^").re2()).isEqualTo("\\A(?:\\^)\\z");
        }

        @Test
        void aHyphenEscapeOutsideAClassIsWrittenPlainForEcmaScriptOnly() {
            assertThat(IRegexp.of("a\\-b").re2()).isEqualTo("\\A(?:a\\-b)\\z");
            assertThat(IRegexp.of("a\\-b").ecmaScript()).isEqualTo("^(?:a-b)$");
            assertThat(IRegexp.of("[\\-a]").ecmaScript()).isEqualTo("^(?:[\\-a])$");
            assertThat(IRegexp.of("\\\\-").ecmaScript()).isEqualTo("^(?:\\\\-)$");
        }

        @Test
        void categoriesAreKeptWholeEvenIfTheyLookLikeOtherThings() {
            assertThat(IRegexp.of("\\p{Lu}\\P{Nd}").re2()).isEqualTo("\\A(?:\\p{Lu}\\P{Nd})\\z");
            assertThat(IRegexp.of("[\\p{L}.]").ecmaScript()).isEqualTo("^(?:[\\p{L}.])$");
        }

        @Test
        void theSourceIsKept() {
            assertThat(IRegexp.of("[A-Z]{3}").source()).isEqualTo("[A-Z]{3}");
            assertThat(IRegexp.of("[A-Z]{3}")).hasToString("[A-Z]{3}");
        }
    }

    @Nested
    class Parsing {

        @Test
        void aValidPatternIsParsed() {
            assertThat(IRegexp.parse("[A-Z]{3}")).isInstanceOfSatisfying(IRegexp.Parsed.Valid.class,
                    valid -> assertThat(valid.pattern().source()).isEqualTo("[A-Z]{3}"));
        }

        @Test
        void anInvalidPatternIsParsedToItsViolation() {
            assertThat(IRegexp.parse("[A-Z")).isInstanceOfSatisfying(IRegexp.Parsed.Invalid.class,
                    invalid -> assertThat(invalid.violation().index()).isEqualTo(4));
        }

        @Test
        void ofRejectsAnInvalidPatternWithTheReason() {
            assertThatThrownBy(() -> IRegexp.of("\\d{3}")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("\\d{3}", "shortcut \\d", "index 0");
        }
    }
}

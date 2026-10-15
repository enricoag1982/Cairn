package io.github.enricoag1982.cairn.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.schema.IRegexpSyntax.Violation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The I-Regexp grammar of RFC 9485, and the limits of the matching engine (ADR-0020). */
class IRegexpSyntaxTest {

    private static Violation violation(String pattern) {
        Optional<Violation> found = IRegexpSyntax.check(pattern);
        assertThat(found).as("violation of " + pattern).isPresent();
        return found.get();
    }

    private static void assertValid(String... patterns) {
        for (String pattern : patterns) {
            assertThat(IRegexpSyntax.check(pattern)).as("violation of '" + pattern + "'").isEmpty();
        }
    }

    private static void assertInvalid(String... patterns) {
        for (String pattern : patterns) {
            assertThat(IRegexpSyntax.check(pattern)).as("violation of '" + pattern + "'").isPresent();
        }
    }

    @Nested
    class Valid {

        @Test
        void patternsOfTheReferenceDomain() {
            assertValid("[A-Z]{3}", "[A-Z0-9]{18}[0-9]{2}");
        }

        @Test
        void branchesPiecesAndGroups() {
            assertValid("", "a", "abc", "a|b", "|", "a|", "|a", "(a|b)c", "()", "(a)(b)", "((a))", "(a|)", "a||b");
        }

        @Test
        void quantifiers() {
            assertValid("a*", "a+", "a?", "a{3}", "a{3,}", "a{3,5}", "a{0}", "a{0,0}", "a{3,3}", "a{007}", "(ab){2,3}",
                    "[a-z]*", ".+", "\\.{2}", "a{1000}", "a{0,500}", "a{500,1000}");
        }

        @Test
        void normalCharacters() {
            assertValid(",", "-", "/", "@", "~", " ", "!", "\"", "#", "%", "&", "'", ":", ";", "<", "=", ">", "_", "`",
                    "é", "中", "𝄞", "\t", "\n", "\u0000");
        }

        @Test
        void caretAndDollarAreOrdinaryCharacters() {
            assertValid("^", "$", "a$", "^a", "^[A-Z]{3}$", "a^b", "[$^]", "[^^]", "[a^]", "[^$]");
        }

        @Test
        void theDotIsACharacterClass() {
            assertValid(".", "a.b", ".*", "[.]");
        }

        @Test
        void singleCharacterEscapes() {
            assertValid("\\(", "\\)", "\\*", "\\+", "\\-", "\\.", "\\?", "\\[", "\\\\", "\\]", "\\^", "\\{", "\\|",
                    "\\}", "\\n", "\\r", "\\t");
        }

        @Test
        void characterClasses() {
            assertValid("[a]", "[abc]", "[a-z]", "[a-zA-Z0-9_]", "[^a]", "[^a-z]", "[-a]", "[a-]", "[-]", "[^-]",
                    "[a-z-]", "[-a-z]", "[--]", "[\\-a]", "[a\\-z]", "[\\--\\-]", "[\\n\\r\\t]", "[\\^]", "[\\]]", "[\\[]",
                    "[\\\\]", "[!-/]", "[\\n-\\r]", "[a-a]", "[𝄞-𝄢]");
        }

        @Test
        void unicodeCategories() {
            for (String category : List.of("L", "Ll", "Lm", "Lo", "Lt", "Lu", "M", "Mc", "Me", "Mn", "N", "Nd", "Nl",
                    "No", "P", "Pc", "Pd", "Pe", "Pf", "Pi", "Po", "Ps", "Z", "Zl", "Zp", "Zs", "S", "Sc", "Sk", "Sm",
                    "So", "C", "Cc", "Cf", "Co")) {
                assertValid("\\p{" + category + "}", "\\P{" + category + "}", "[\\p{" + category + "}x]",
                        "[^\\P{" + category + "}]");
            }
            assertValid("\\p{L}+", "[\\p{L}\\p{Nd}_]+", "[a-z\\p{Lu}]", "[\\p{L}-]", "[-\\p{L}]");
        }

        @Test
        void aLongPatternWithinTheLimits() {
            assertValid("a".repeat(9_000), "(a{99}){100}", "[a-z]{1000}[0-9]{1000}", "a{500,}", "a{1,501}");
        }
    }

    @Nested
    class Invalid {

        @Test
        void aClassThatIsNotClosedIsReportedAtTheEndOfThePattern() {
            Violation violation = violation("[A-Z");

            assertThat(violation.index()).isEqualTo(4);
            assertThat(violation.reason()).contains("not closed");
            assertThat(violation.expected()).contains("]");
            assertThat(violation.found()).contains("end of the pattern").contains("4");
        }

        @Test
        void groups() {
            assertThat(violation("(a").reason()).contains("not closed");
            assertThat(violation("(a").index()).isEqualTo(2);
            assertThat(violation("a)").reason()).contains("no group to close");
            assertThat(violation("a)").index()).isEqualTo(1);
            assertThat(violation("a)").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\)"));
            assertThat(violation("(a))").index()).isEqualTo(3);
            assertInvalid("(", ")", "((a)", "(a|b");
        }

        @Test
        void aQuantifierNeedsSomethingToRepeat() {
            assertThat(violation("*a").index()).isZero();
            assertThat(violation("*a").reason()).contains("nothing to repeat");
            assertThat(violation("a|*").index()).isEqualTo(2);
            assertThat(violation("{3}").index()).isZero();
            assertThat(violation("(?:a)").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("(?:"));
            assertInvalid("+", "?", "(*)", "(+a)");
        }

        @Test
        void aQuantifierCannotFollowAQuantifier() {
            assertThat(violation("a**").index()).isEqualTo(2);
            assertThat(violation("a+?").reason()).contains("cannot follow another quantifier");
            assertThat(violation("a+?").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("lazy"));
            assertInvalid("a{2}{3}", "a?*", "a*{2}", "a*+");
        }

        @Test
        void aRangeQuantifierMustBeComplete() {
            assertThat(violation("a{").found()).contains("end of the pattern");
            assertThat(violation("a{").reason()).contains("needs a number");
            assertThat(violation("a{,3}").index()).isEqualTo(2);
            assertThat(violation("a{x}").index()).isEqualTo(2);
            assertThat(violation("a{1").reason()).contains("not closed");
            assertThat(violation("a{1").expected()).contains("}");
            assertThat(violation("a{1,").expected()).contains("number");
            assertThat(violation("a{1,2").expected()).isEqualTo("'}'");
            assertThat(violation("a{1;2}").index()).isEqualTo(3);
            assertThat(violation("a{").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\{"));
            assertInvalid("a{1,2,3}", "a{-1}", "a{1 }", "a{ 1}", "a{1.5}");
        }

        @Test
        void aRangeQuantifierWhoseMinimumExceedsItsMaximumIsRejected() {
            Violation violation = violation("a{3,2}");

            assertThat(violation.index()).isEqualTo(1);
            assertThat(violation.reason()).contains("minimum 3", "maximum 2");
            assertThat(violation("a{1000,999}").reason()).contains("greater than");
            assertValid("a{2,2}");
        }

        @Test
        void aBraceOrBracketThatClosesNothingMustBeEscaped() {
            assertThat(violation("a}").index()).isEqualTo(1);
            assertThat(violation("a}").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\}"));
            assertThat(violation("a]").index()).isEqualTo(1);
            assertThat(violation("a]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\]"));
        }

        @Test
        void emptyAndAmbiguousClasses() {
            assertThat(violation("[]").reason()).contains("empty");
            assertThat(violation("[]a]").index()).isEqualTo(1);
            assertThat(violation("[^]").reason()).contains("[^]");
            assertThat(violation("[^]").index()).isZero();
            assertThat(violation("[^]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("[\\^]"));
            assertThat(violation("[^]]").reason()).contains("[^]");
            assertThat(violation("a[^]").index()).isEqualTo(1);
            assertValid("[\\^]", "[^^]", "[^\\^]");
        }

        @Test
        void unclosedClasses() {
            assertThat(violation("[").index()).isEqualTo(1);
            assertThat(violation("[^").index()).isEqualTo(2);
            assertThat(violation("[a-").index()).isEqualTo(3);
            assertThat(violation("[a-z").reason()).contains("not closed");
            assertThat(violation("[\\p{L}").reason()).contains("not closed");
            assertThat(violation("[A-Z").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\["));
        }

        @Test
        void aRangeMustNotEndBeforeItStarts() {
            Violation violation = violation("[z-a]");

            assertThat(violation.index()).isEqualTo(1);
            assertThat(violation.reason()).contains("'z'-'a'", "ends before it starts");
            assertThat(violation.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'a'-'z'"));
            assertInvalid("[a-Z]", "[\\r-\\n]", "[9-0]", "[a-zz-a]");
            assertThat(violation("[\\r-\\n]").reason()).contains("U+000D", "U+000A");
        }

        @Test
        void aHyphenIsOnlyAllowedFirstLastOrInARange() {
            assertThat(violation("[a-z-9]").index()).isEqualTo(4);
            assertThat(violation("[a-z-9]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\-"));
            assertThat(violation("[--a]").index()).isEqualTo(2);
            assertThat(violation("[a--]").reason()).contains("cannot be a range end");
            assertThat(violation("[\\p{L}-z]").index()).isEqualTo(6);
            assertInvalid("[a-b-c]", "[ab-c-d]");
        }

        @Test
        void aRangeMustEndWithASingleCharacter() {
            assertThat(violation("[a-\\p{L}]").reason()).contains("single character");
            assertThat(violation("[a-\\p{L}]").index()).isEqualTo(3);
        }

        @Test
        void anOpeningBracketInsideAClassMustBeEscaped() {
            assertThat(violation("[[]").index()).isEqualTo(1);
            assertThat(violation("[a[]").index()).isEqualTo(2);
            assertThat(violation("[[]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\["));
            assertInvalid("[[:alpha:]]", "[a-[]");
        }

        @Test
        void escapes() {
            assertThat(violation("\\").reason()).contains("ends with a backslash");
            assertThat(violation("a\\").index()).isEqualTo(1);
            assertThat(violation("\\q").reason()).contains("\\q is not an escape");
            assertThat(violation("\\q").expected()).contains("\\n", "\\p{...}");
            assertThat(violation("a\\q").index()).isEqualTo(1);
            assertThat(violation("a\\q").found()).isEqualTo("\\q at index 1");
            assertThat(violation("\\q").hint()).isEmpty();
            assertInvalid("\\$", "\\/", "\\1", "\\b", "\\B", "\\A", "\\z", "\\x41", "\\u0041", "\\f", "\\0", "\\ ", "\\e");
        }

        @Test
        void escapesOfCharactersThatAreNotSpecialSuggestWritingThemWithoutTheBackslash() {
            assertThat(violation("\\$").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'$' without the"));
            assertThat(violation("\\/").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("'/' without the"));
            assertThat(violation("[\\,]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("',' without"));
        }

        @Test
        void otherEscapesGetHintsToo() {
            assertThat(violation("\\1").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Back-references"));
            assertThat(violation("\\b").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Word boundaries"));
            assertThat(violation("\\A").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("Anchors"));
            assertThat(violation("\\x41").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("character itself"));
            assertThat(violation("\\f").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\n, \\r and \\t"));
        }

        @Test
        void shortcutsForCharacterSetsSuggestTheReplacementOfTheRfc() {
            assertThat(violation("\\d{3}").index()).isZero();
            assertThat(violation("\\d{3}").reason()).contains("shortcut \\d");
            assertThat(violation("\\d{3}").found()).isEqualTo("\\d at index 0");
            assertThat(violation("\\d{3}").hint()).hasValueSatisfying(hint ->
                    assertThat(hint).isEqualTo("Write [0-9] instead of \\d."));
            assertHint("\\D", "Write [^0-9] instead of \\D.");
            assertHint("\\s", "Write [ \\t\\n\\r] instead of \\s.");
            assertHint("\\S", "Write [^ \\t\\n\\r] instead of \\S.");
            assertHint("\\w", "Write [a-zA-Z0-9_] instead of \\w (ASCII letters, digits and underscore).");
            assertHint("\\W", "Write [^a-zA-Z0-9_] instead of \\W.");
            assertThat(violation("x\\w").index()).isEqualTo(1);
        }

        private void assertHint(String pattern, String hint) {
            assertThat(violation(pattern).hint()).as(pattern).contains(hint);
        }

        @Test
        void shortcutsForXmlNameCharactersAreNotSupported() {
            for (String escape : List.of("\\i", "\\I", "\\c", "\\C")) {
                assertThat(violation(escape).reason()).contains("shortcut " + escape);
                assertThat(violation(escape).hint()).hasValueSatisfying(hint ->
                        assertThat(hint).contains(escape, "not supported", "character class"));
            }
        }

        @Test
        void shortcutsInsideAClassSuggestTheirContent() {
            assertThat(violation("[\\d.]").index()).isEqualTo(1);
            assertThat(violation("[\\d.]").hint()).hasValueSatisfying(hint ->
                    assertThat(hint).isEqualTo("Inside a character class, write 0-9 instead of \\d."));
            assertThat(violation("[a\\s]").hint()).hasValueSatisfying(hint ->
                    assertThat(hint).contains("write  \\t\\n\\r instead of \\s"));
            assertThat(violation("[\\w]").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("a-zA-Z0-9_"));
            assertThat(violation("[\\D]").hint()).hasValueSatisfying(hint ->
                    assertThat(hint).contains("negated shortcut", "[^0-9]"));
        }

        @Test
        void categories() {
            assertThat(violation("\\p").reason()).contains("must be followed by a category in braces");
            assertThat(violation("\\pL").index()).isEqualTo(2);
            assertThat(violation("\\pL").hint()).hasValueSatisfying(hint -> assertThat(hint).contains("\\p{L}"));
            assertThat(violation("\\p{").reason()).contains("not closed");
            assertThat(violation("\\p{L").index()).isEqualTo(4);
            assertThat(violation("\\p{Greek}").reason()).contains("'Greek' is not a Unicode general category");
            assertThat(violation("\\p{Greek}").index()).isEqualTo(3);
            assertThat(violation("\\p{Greek}").expected()).contains("Lu", "Nd");
            assertThat(violation("\\p{Greek}").hint()).hasValueSatisfying(hint ->
                    assertThat(hint).contains("scripts, blocks"));
            assertThat(violation("\\p{}").found()).contains("empty category");
            assertThat(violation("\\P{Alpha}").reason()).contains("Alpha");
            assertInvalid("\\p{lu}", "\\p{LU}", "\\p{ L}", "\\p{L }", "\\p{IsL}", "\\p{Cs}", "\\p{Lx}", "\\p{LL}",
                    "[\\p{Greek}]", "\\p{L}{");
        }

        @Test
        void theCategoryCnIsValidButTheMatchingEngineDoesNotKnowIt() {
            Violation violation = violation("a\\p{Cn}");

            assertThat(violation.index()).isEqualTo(1);
            assertThat(violation.reason()).contains("Cn", "valid I-Regexp", "matching engine");
            assertThat(violation.found()).isEqualTo("\\p{Cn} at index 1");
            assertThat(violation("\\P{Cn}").reason()).contains("Cn");
            assertThat(violation("[\\p{Cn}]").reason()).contains("Cn");
        }

        @Test
        void unpairedSurrogatesAreNotCharacters() {
            assertThat(violation("a\uD800").index()).isEqualTo(1);
            assertThat(violation("a\uD800").found()).contains("U+D800");
            assertInvalid("\uDC00", "[\uD800]", "[a-\uDFFF]");
        }
    }

    @Nested
    class MatchingEngineLimits {

        @Test
        void repetitionCountsAbove1000AreRejected() {
            Violation violation = violation("a{1001}");

            assertThat(violation.index()).isEqualTo(1);
            assertThat(violation.reason()).contains("above 1000", "matching engine");
            assertThat(violation.found()).contains("1001");
            assertInvalid("a{0,1001}", "a{1001,}", "a{99999999999999999999}", "a{99999999999999999999,99999999999999999999}");
            assertThat(violation("a{99999999999,5}").reason()).contains("greater than");
        }

        @Test
        void nestedRepetitionsThatExpandTooFarAreRejected() {
            Violation violation = violation("((a{1000}){1000}){1000}");

            assertThat(violation.reason()).contains("too large", "10000");
            assertThat(violation.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("nest"));
            assertInvalid("(a{500}){500}", "((a{100}){100}){100}", "a{1000}b{1000}c{1000}d{1000}e{1000}f{1000}g{1000}"
                    + "h{1000}i{1000}j{1000}k{1000}", "a".repeat(10_001));
        }

        @Test
        void theLimitOnTheExpandedSizeAppliesToTheWholePattern() {
            assertValid("a{1000}|b{1000}", "(a{99}){100}", "a".repeat(10_000));
            assertInvalid("(a{100}){100}", "(a{99}){101}", "a".repeat(5_000) + "|" + "b".repeat(5_000));
        }

        @Test
        void tooManyPlacesWhereMatchingCanGoEitherWayAreRejected() {
            assertValid("a?".repeat(500), "()".repeat(500), "a{0,500}", "a{1,501}", "(a|b)".repeat(250),
                    "(ab|cd|ef)".repeat(100), "ab|".repeat(500) + "ab", "(a*)".repeat(250), "(a?){100}",
                    "((a?){10}){10}");
            assertInvalid("a?".repeat(501), "()".repeat(501), "a{0,501}", "a{1,502}", "a{0,1000}", "a{1,1000}",
                    "(a|b)".repeat(251), "(ab|cd|ef)".repeat(200), "ab|".repeat(501) + "ab", "(a*)".repeat(251),
                    "(a?){1000}", "((a?){100}){6}");
        }

        @Test
        void aPatternThatIsTooComplexSaysSoAndHowToSimplifyIt() {
            Violation violation = violation("a{0,1000}");

            assertThat(violation.reason()).contains("too complex", "500");
            assertThat(violation.index()).isEqualTo(1);
            assertThat(violation.hint()).hasValueSatisfying(hint ->
                    assertThat(hint).contains("maximum length"));
        }

        @Test
        void aRepetitionThatCannotBeSkippedIsNotAPlaceToGoEitherWay() {
            assertValid("a{1000}", "[a-z]{1000}", "(ab){500}", "a{1000}b{1000}");
        }

        @Test
        void deeplyNestedGroupsAreRejectedBeforeTheyCanOverflowTheStack() {
            assertValid("(".repeat(100) + ")".repeat(100));
            Violation violation = violation("(".repeat(101) + ")".repeat(101));
            assertThat(violation.reason()).contains("nested more than 100 deep");
            assertThat(violation.index()).isEqualTo(100);
            assertThat(violation("(".repeat(200_000)).reason()).contains("nested more than 100 deep");
        }
    }
}

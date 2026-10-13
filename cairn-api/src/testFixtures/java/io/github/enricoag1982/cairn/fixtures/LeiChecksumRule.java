package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.data.EntityRule;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;

/**
 * The rule {@code lei-checksum} of the reference domain (ADR-0018, capability {@code entity-rules}): the check digits of
 * an organization's Legal Entity Identifier are valid, by ISO 7064 MOD 97-10 as ISO 17442 prescribes. An organization
 * without a LEI passes.
 *
 * <p>The rule reads only the version it checks, so it is deterministic. Its descriptor is the one that
 * {@link ReferenceModel#rules()} lists.
 */
public final class LeiChecksumRule implements EntityRule {

    /** The code the rule reports when the check digits are wrong. */
    public static final String INVALID_CHECK_DIGITS = "invalid-check-digits";

    /** The description of the rule: the page's row for {@code lei-checksum}, with the explanation of its code. */
    public static final RuleDescriptor DESCRIPTOR = new RuleDescriptor("lei-checksum", 1,
            "The LEI's check digits are valid (ISO 17442, ISO 7064 MOD 97-10).",
            "Organization",
            List.of(new RuleCode(INVALID_CHECK_DIGITS,
                    "The two check digits of the LEI do not match its other characters. Correct the LEI.")));

    /** The number of characters of a LEI. */
    private static final int LENGTH = 20;

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void check(EntityState version, RuleContext context) {
        if (version.values().get("lei") instanceof Value.StringValue lei && !hasValidCheckDigits(lei.value())) {
            context.problem(INVALID_CHECK_DIGITS, "lei", "The check digits of the LEI " + lei.value()
                    + " are wrong (ISO 7064 MOD 97-10).");
        }
    }

    /**
     * Tells whether a text is a LEI with valid check digits: 20 digits and uppercase letters which, with each letter
     * replaced by the two digits of its position in the alphabet counted from 10 (A is 10, Z is 35), make a number
     * that leaves the remainder 1 when divided by 97.
     *
     * @param lei the text
     * @return {@code true} if the check digits are valid; {@code false} for any other text
     */
    public static boolean hasValidCheckDigits(String lei) {
        if (lei.length() != LENGTH) {
            return false;
        }
        int remainder = 0;
        for (int i = 0; i < lei.length(); i++) {
            char c = lei.charAt(i);
            if (c >= '0' && c <= '9') {
                remainder = (remainder * 10 + (c - '0')) % 97;
            } else if (c >= 'A' && c <= 'Z') {
                remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
            } else {
                return false;
            }
        }
        return remainder == 1;
    }
}

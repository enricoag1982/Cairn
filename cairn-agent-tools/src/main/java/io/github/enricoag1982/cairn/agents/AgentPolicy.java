package io.github.enricoag1982.cairn.agents;

import java.util.Objects;

/**
 * The approval policy of a store served to agents (ADR-0017, commitment 8, design D4 of {@code add-agent-tools},
 * capability {@code agent-tools}): for schema registrations and for data commits, whether an agent's request is
 * allowed, needs a person's approval, or is denied. Purges exist now, but no agent tool offers them yet, so the policy
 * does not cover them; migrations do not exist yet. Both will come under it once agents are offered them.
 *
 * @param schemaRegistrations what happens to an agent's request to register a schema document
 * @param dataCommits         what happens to an agent's request to commit a transaction
 */
public record AgentPolicy(Decision schemaRegistrations, Decision dataCommits) {

    /** What the policy says about a kind of operation. */
    public enum Decision {
        /** The request is applied at once. */
        ALLOW,
        /** The request waits in the {@link ApprovalQueue} until a person approves or rejects it. */
        REQUIRE_APPROVAL,
        /** The request is rejected with the problem {@code APPROVAL_DENIED}. */
        DENY
    }

    /**
     * Rejects null decisions.
     */
    public AgentPolicy {
        Objects.requireNonNull(schemaRegistrations, "schemaRegistrations");
        Objects.requireNonNull(dataCommits, "dataCommits");
    }

    /**
     * The default policy: schema registrations and data commits are both allowed.
     *
     * @return the policy
     */
    public static AgentPolicy defaults() {
        return new AgentPolicy(Decision.ALLOW, Decision.ALLOW);
    }
}

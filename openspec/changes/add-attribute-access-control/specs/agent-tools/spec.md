## MODIFIED Requirements

### Requirement: Approval policy

An adapter that serves a store to agents SHALL have an approval policy that says, for schema registrations and for data commits, whether an agent's request is allowed, needs a person's approval, or is denied. By default both are allowed. WHEN a request needs approval, THE SYSTEM SHALL keep it as an approval request, with an ID, the request, who asked, for whom, why, when, and the clearance of the adapter that asked (see the `attribute-access` capability), and SHALL return the request instead of applying it. A request that would be rejected at the time it is made, and a registration that would change nothing, SHALL be answered at once instead of being kept. People SHALL list, approve and reject approval requests through the Java API; approving SHALL apply the request then, with that clearance and no other, as if submitted at that moment, and record who approved it; rejecting SHALL apply nothing and record who rejected it and why. `cairn_approval_status` SHALL return a request's status and, once decided, its outcome. A denied request SHALL be rejected with a problem.

#### Scenario: A commit waiting for approval

- **WHEN** the policy requires approval for data commits, and an agent commits a payload that creates ACME
- **THEN** the tool returns an approval request with the status pending, and ACME does not exist
- **AND** after a person approves the request, ACME exists and `cairn_approval_status` reports the receipt

#### Scenario: Denied schema registrations

- **WHEN** the policy denies schema registrations, and an agent registers a schema document
- **THEN** the tool returns a problem, and the schema is unchanged

#### Scenario: An approval request keeps the agent's clearance

- **WHEN** Account's `balance` is classified `finance`, the policy requires approval for data commits, and an agent without a clearance commits a payload that patches A-1's currency
- **THEN** the approval request reports no classification as its clearance, and approving it commits the patch with that clearance

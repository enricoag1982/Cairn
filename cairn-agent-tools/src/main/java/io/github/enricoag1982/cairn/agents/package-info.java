/**
 * The adapter that serves a store to AI agents as tools of the Model Context Protocol (ADR-0017, ADR-0007, capability
 * {@code agent-tools}). The public classes are:
 *
 * <ul>
 * <li>{@link io.github.enricoag1982.cairn.agents.AgentTools}: the tools as plain Java, one method for each, with JSON
 *     text in and out;
 * <li>{@link io.github.enricoag1982.cairn.agents.AgentPolicy}: the approval policy, which says for schema
 *     registrations and for data commits whether they are allowed, need a person's approval, or are denied;
 * <li>{@link io.github.enricoag1982.cairn.agents.ApprovalQueue} and
 *     {@link io.github.enricoag1982.cairn.agents.ApprovalRequest}: the requests that wait for a person, and how people
 *     list, approve and reject them;
 * <li>{@link io.github.enricoag1982.cairn.agents.McpAdapter}: the thin layer that registers the tools with the MCP
 *     SDK;
 * <li>{@link io.github.enricoag1982.cairn.agents.CairnAgentServer}: a server that speaks the protocol over standard
 *     input and output.
 * </ul>
 *
 * <p>The module has no {@code module-info.java}: the jars of the MCP Java SDK 2.0.1 declare automatic module names
 * that are not valid (for example {@code io.modelcontextprotocol.sdk.mcp-core}), so the Java module system cannot
 * resolve them. Everything but the classes above is package-private.
 */
package io.github.enricoag1982.cairn.agents;

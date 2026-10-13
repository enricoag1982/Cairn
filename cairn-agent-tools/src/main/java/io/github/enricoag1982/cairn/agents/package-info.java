/**
 * The adapter that serves a store to AI agents as tools of the Model Context Protocol (ADR-0017, ADR-0007, capability
 * {@code agent-tools}): the tools as plain Java ({@link io.github.enricoag1982.cairn.agents.AgentTools}), the approval
 * policy and the queue of requests that wait for a person, the thin layer that registers the tools with the MCP SDK
 * ({@link io.github.enricoag1982.cairn.agents.McpAdapter}), and a server that speaks the protocol over standard input
 * and output ({@link io.github.enricoag1982.cairn.agents.CairnAgentServer}).
 *
 * <p>The module has no {@code module-info.java}: the jars of the MCP Java SDK 2.0.1 declare automatic module names
 * that are not valid (for example {@code io.modelcontextprotocol.sdk.mcp-core}), so the Java module system cannot
 * resolve them. Everything here but the classes named above is package-private.
 */
package io.github.enricoag1982.cairn.agents;

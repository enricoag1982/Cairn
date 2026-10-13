plugins {
    id("cairn.java-library")
    id("cairn.reference-domain-tests")
    application
}

description = "Cairn agent tools: an adapter that serves a store as tools of the Model Context Protocol, the approval policy for agents, and a server that speaks the protocol over standard input and output"

// No mainModule: the jars of the MCP SDK declare automatic module names that are not valid (mcp-core, mcp-json-jackson3),
// so the module system cannot resolve them, and this project has no module-info.java. It runs on the class path.
application {
    mainClass = "io.github.enricoag1982.cairn.agents.CairnAgentServer"
}

dependencies {
    implementation(project(":cairn-core"))
    implementation(project(":cairn-documents"))
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)
    testImplementation(testFixtures(project(":cairn-api")))
}

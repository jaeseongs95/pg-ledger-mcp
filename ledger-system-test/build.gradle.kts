// The system test of the packaged product (docs/testing/TEST_CASES.md section 21, SYS-001 .. SYS-012): test code only, no main
// code, and no module depends on this one. The tests start the built jars of ledger-cli and ledger-mcp-server and the hook jar
// as separate Java processes and reach the server with the MCP Java SDK client, against the PostgreSQL of the test PC.

// the bootJar tasks of the two applications are looked up here, so the projects are configured first
evaluationDependsOn(":ledger-cli")
evaluationDependsOn(":ledger-mcp-server")

// The built client hook jar (ledger-client-hook.jar); the same arrangement as in ledger-mcp-server, the jar is one fat jar.
val hookJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // The test database support: the settings of the database and the passwords read from their files, the name of a test
    // schema, and the PostgreSQL JDBC driver (a test fixture dependency: the tests connect as the owner to read the rows).
    testImplementation(testFixtures(project(":ledger-database")))
    // The MCP Java SDK client (the SDK the server runs on) and the Jackson 3 mapper it writes JSON with (Boot BOM).
    testImplementation(libs.mcp.sdk)
    testImplementation("tools.jackson.core:jackson-databind")
    hookJar(project(":ledger-client-hook")) { isTransitive = false }
}

val cliJar = project(":ledger-cli").tasks.named<Jar>("bootJar")
val mcpJar = project(":ledger-mcp-server").tasks.named<Jar>("bootJar")

tasks.test {
    dependsOn(cliJar, mcpJar, hookJar)
    // The paths are passed in, never guessed. The jars are inputs, so a change of a product jar runs the test again
    // (the database is an input too, which is why the test is never up to date, see the root build).
    inputs.files(cliJar, mcpJar, hookJar)
    doFirst {
        systemProperty("ledger.cli.jar", cliJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("ledger.mcp.jar", mcpJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("ledger.hook.jar", hookJar.singleFile.absolutePath)
    }
}

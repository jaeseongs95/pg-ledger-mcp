rootProject.name = "pg-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// ledger-system-test is test code only: it starts the built jars of the other modules (docs/testing/TEST_CASES.md section 21).
// ledger-seed holds the seed bundle (the initial data of a new installation in one H2 file, SETUP) and is used by ledger-cli only.
include("ledger-domain", "ledger-attestation", "ledger-database", "ledger-application", "ledger-mcp-server",
    "ledger-admin-web", "ledger-cli", "ledger-seed", "ledger-client-hook", "ledger-system-test")

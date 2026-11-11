rootProject.name = "pg-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Future modules (application, mcp-server, admin-web, hooks, e2e) are listed here when they exist.
include("ledger-domain", "ledger-attestation", "ledger-database", "ledger-application", "ledger-mcp-server", "ledger-cli",
    "ledger-client-hook")

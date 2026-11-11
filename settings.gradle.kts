rootProject.name = "pg-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Future modules (e2e) are listed here when they exist.
include("ledger-domain", "ledger-attestation", "ledger-database", "ledger-application", "ledger-mcp-server",
    "ledger-admin-web", "ledger-cli", "ledger-client-hook")

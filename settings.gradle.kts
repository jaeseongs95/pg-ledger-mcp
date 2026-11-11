rootProject.name = "pg-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Future modules (hooks, e2e) are listed here when they exist.
include("ledger-domain", "ledger-database", "ledger-application", "ledger-mcp-server", "ledger-admin-web", "ledger-cli", "ledger-client-hook")

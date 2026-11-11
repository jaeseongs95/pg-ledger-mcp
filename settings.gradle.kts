rootProject.name = "pg-ledger-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Future modules (application, mcp-server, admin-web, hooks, e2e) are listed here when they exist.
include("ledger-domain", "ledger-database", "ledger-application", "ledger-cli", "ledger-client-hook")

// The wire format of the author attestation and the Ed25519 primitives behind it. JDK only (no dependency at all):
// the client hook signs with it and the server verifies with it, so the two sides cannot drift apart.

dependencies {
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared architecture and source rules live in the domain test fixtures (test classpath only).
    testImplementation(testFixtures(project(":ledger-domain")))
}

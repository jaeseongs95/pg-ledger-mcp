// The seed bundle: the initial data of a new installation (the default defect categories and the install defaults) in one file
// of the H2 format, written by the build from the text files of ledger-cli (SeedBuilder) and read by ledger-cli (SeedBundle).
// H2 is the format of this one file and nothing else: the ledger database is always a DBMS that the operator installs and
// the product refuses to register H2 as its database (user decision: 「dbms 설치가 전제라니까?」, 「h2는 초기 셋팅값이나 뭐 이것저것
// 담아두는 용도로만 쓰고 dbms에 마이그레이션하는 목적으로만 써.」). That is why the H2 driver is a dependency of this module and
// of ledger-cli only: the root build (verifyRuntimeClasspath, DRV-014) refuses it on every other module, and the system test
// (DRV-013) looks for it in the built jars. The module is plain Java: no Spring, no other module.
dependencies {
    implementation("com.h2database:h2")
}

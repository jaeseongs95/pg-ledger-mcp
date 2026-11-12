package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.deploy.DeployLayout;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.core.io.ClassPathResource;

/**
 * INS-021 .. INS-027 (deploy/install.sh) and INS-033 .. INS-037 (deploy/install.cmd): the two scripts are the one exception to
 * "everything in Java", so they are read as text and held to what the exception is: thin, only the operating system steps
 * that are listed, no secret on a command line, nothing downloaded or installed, the same names as the Java code, and the
 * documentation they follow named in their header. The scripts are never run here (they target a host with root or an
 * administrator); they run on the test server and on a Windows host in a later stage (INS-031, INS-038).
 */
class InstallScriptsTest {

    private static final String SH = "deploy/install.sh";
    private static final String CMD = "deploy/install.cmd";

    /** What install.sh may start besides the shell's own commands (the brief of the installer). */
    private static final Set<String> EXTERNAL = Set.of("useradd", "java", "runuser", "systemctl", "nginx");
    private static final Set<String> BUILTIN = Set.of("set", "umask", "echo", "exit", "shift", "command", "case", "esac", "for",
            "done", "fi", "[", "return", "}");
    /** An option that carries a secret value; {@code --ledger.secrets.home=} is a directory, not a secret. */
    private static final Pattern SECRET_OPTION = Pattern
            .compile("(?i)--(?!ledger\\.secrets\\.(home|instance)=)[a-z.-]*(password|token|secret)[a-z.-]*=");
    private static final Pattern FORBIDDEN_SH = Pattern.compile(
            "\\b(curl|wget|git|sed|awk|apt|apt-get|yum|dnf|zypper|pip|npm|psql|pg_dump|createuser|createdb|certbot|cloudflared|rm|"
                    + "bash|eval|export|env|read|exec|ssh|scp|tar|unzip)\\b");
    private static final Pattern FORBIDDEN_CMD = Pattern.compile(
            "(?i)\\b(powershell|pwsh|curl|wget|bitsadmin|certutil|msiexec|winget|choco|scoop|schtasks|reg|wmic|net|net1|"
                    + "takeown|xcopy|robocopy|move|del|erase|rd|rmdir|ren|ftp|tftp|cscript|wscript|mshta|git|icacls|cacls|mkdir|md|copy|attrib)\\b");

    private static String text(String script) {
        return Repository.read(script);
    }

    private static List<String> lines(String script) {
        return text(script).lines().toList();
    }

    // ---- the shell script: what its statements start ----

    private static boolean isComment(String line) {
        String stripped = line.strip();
        return stripped.startsWith("#");
    }

    private static List<String> shCodeLines() {
        return lines(SH).stream().filter(line -> !line.isBlank() && !isComment(line)).toList();
    }

    /**
     * The shell text of a line with what cannot start a command taken out: quoted text, parameter expansions, escaped quotes
     * and the names of options ({@code --from-1x-env} is a name, not the command {@code env}).
     */
    private static String bare(String line) {
        return line.replace("\\\"", "Q").replace("\\'", "Q").replaceAll("\\$\\{[^}]*}", "X").replaceAll("--[a-z][a-z0-9.-]*", "--O");
    }

    /** The words that start a command in the statements of the line (and of its command substitutions), functions defined by the script included. */
    private static List<String> commandWords(String line, boolean inCase, Set<String> functions) {
        List<String> words = new ArrayList<>();
        String rest = bare(line);
        Matcher definition = Pattern.compile("^\\s*([a-z_]+)\\(\\)\\s*\\{?\\s*(.*)$").matcher(rest);
        if (definition.matches()) {
            functions.add(definition.group(1));
            rest = definition.group(2);
        } else if (inCase) {
            Matcher pattern = Pattern.compile("^.*?\\)\\s*(.*)$").matcher(rest);
            if (pattern.matches()) {
                rest = pattern.group(1);
            }
        }
        List<String> segments = new ArrayList<>();
        Matcher substitution = Pattern.compile("\\$\\(([^()]*)\\)").matcher(rest);
        while (substitution.find()) {
            segments.add(substitution.group(1));
        }
        rest = substitution.replaceAll("X");
        rest = rest.replaceAll("\"[^\"]*\"", "\"\"").replaceAll("'[^']*'", "''");
        segments.addAll(List.of(rest.split("&&|\\|\\||;|\\|")));
        for (String segment : segments) {
            String command = segment.strip();
            boolean again = true;
            while (again) {
                again = false;
                for (String keyword : List.of("if ", "then ", "else ", "elif ", "do ", "! ", "{ ", "while ", "until ")) {
                    if (command.startsWith(keyword)) {
                        command = command.substring(keyword.length()).strip();
                        again = true;
                    }
                }
            }
            if (command.isEmpty() || "then".equals(command) || "do".equals(command) || "else".equals(command)
                    || command.matches("^[A-Za-z_][A-Za-z0-9_]*=.*")) {
                continue;
            }
            words.add(command.split("\\s+")[0]);
        }
        return words;
    }

    private static List<String> shCommandWords() {
        List<String> all = new ArrayList<>();
        Set<String> functions = new TreeSet<>();
        boolean inCase = false;
        for (String line : shCodeLines()) {
            all.addAll(commandWords(line, inCase, functions));
            String stripped = line.strip();
            if (stripped.startsWith("case ")) {
                inCase = true;
            } else if ("esac".equals(stripped)) {
                inCase = false;
            }
        }
        all.removeAll(functions);
        return all;
    }

    @Test
    @DisplayName("INS-021 (changed by INS-046) every command install.sh starts is a shell builtin or one of useradd, java, runuser, systemctl, nginx: what Java cannot do (create the operating system user, switch to it, the service manager, the web server) and the Java commands")
    void ins021_onlyTheAllowedCommands() {
        Set<String> allowed = new TreeSet<>(EXTERNAL);
        allowed.addAll(BUILTIN);

        assertThat(shCommandWords()).isNotEmpty().allSatisfy(word -> assertThat(allowed).as("command %s", word).contains(word));
        assertThat(new TreeSet<>(shCommandWords())).as("the steps that stay in the script are all there").contains("useradd",
                "runuser", "systemctl", "nginx", "java");
    }

    @Test
    @DisplayName("INS-022 install.sh starts with #!/bin/sh, set -eu and umask 022, and has no secret in an option or an operand, no read, no export, no environment")
    void ins022_safeShellAndNoSecretOnTheCommandLine() {
        List<String> all = lines(SH);

        assertThat(all.getFirst()).isEqualTo("#!/bin/sh");
        assertThat(all).contains("set -eu", "umask 022");
        for (String line : shCodeLines()) {
            assertThat(line).as(line).doesNotContainPattern(SECRET_OPTION);
            if (line.contains("secret-set") || line.contains("token-generate")) {
                // SEC-024: --stdin is the flag (passed on as $STDIN) that makes Java read the secret from standard input
                assertThat(line).as("only the name of the secret follows the command").matches(
                        ".*(secret-set|token-generate) ledger\\.[a-z.-]+( --print| --if-absent| \\$STDIN| --copy-to=ledger\\.[a-z.-]+)*");
            }
        }
        assertThat(shCodeLines().stream().map(InstallScriptsTest::bare).map(line -> line.replaceAll("\"[^\"]*\"", "\"\"")))
                .noneMatch(line -> line.matches(".*\\b(export|read|env|eval)\\b.*") || line.contains("sh -c"));
    }

    @Test
    @DisplayName("INS-023 install.sh uses none of curl, wget, git, sed, awk, a package manager, a database tool, certbot or a deletion, and does not name Cloudflare")
    void ins023_noForbiddenCommand() {
        for (String line : shCodeLines()) {
            String code = bare(line).replaceAll("\"[^\"]*\"", "\"\"").replaceAll("'[^']*'", "''");
            assertThat(FORBIDDEN_SH.matcher(code).find()).as(line).isFalse();
        }
        assertThat(text(SH).toLowerCase(Locale.ROOT)).doesNotContain("cloudflare");
    }

    @Test
    @DisplayName("INS-024 (changed by INS-046) install.sh is thin: at most 100 lines in all (a third of it the header that names the documentation it follows and the comment of each step that stays) and at most 55 lines of commands (the option loop, the check of the options, one user, one prepare-host call, the secrets and tokens, one migrate call, units, nginx; the directories, the copies and the modes are Java now)")
    void ins024_theLineBudget() {
        assertThat(lines(SH)).hasSizeLessThanOrEqualTo(100);
        assertThat(shCodeLines()).hasSizeLessThanOrEqualTo(55);
    }

    @Test
    @DisplayName("INS-025 install.sh uses the names of the Java code (the service user, the second service, the directories, the jars) and gives ledger-cli only commands and options that exist")
    void ins025_theNamesAreTheOnesOfTheCode() throws IOException {
        String script = text(SH);
        DeployLayout layout = DeployLayout.linux("x");

        // the script only composes the names and the paths from the name of the instance; the Java renderer decides which names are good
        assertThat(script).contains("\nNAME=" + DeployLayout.DEFAULT_NAME + "\n", "\nSVC=$NAME\n", "\nADMIN=$NAME-admin\n",
                "\nLIB=/opt/$NAME\n", "\nETC=/etc/$NAME\n");
        assertThat(layout.serviceUser()).isEqualTo("x");
        assertThat(layout.adminName()).isEqualTo("x-admin");
        assertThat(layout.lib()).isEqualTo("/opt/x");
        assertThat(layout.etc()).isEqualTo("/etc/x");
        // the script names the jar of the CLI only; the other jars are copied by prepare-host
        assertThat(script).contains(DeployLayout.CLI_JAR);
        Properties usage = usageTexts();
        Set<String> known = new TreeSet<>(Stream.of(CliCommand.values()).map(CliCommand::cliName).toList());
        Pattern call = Pattern.compile("(?<![\\w-])(?:as_svc|cli)\\s+(?:--\\S+\\s+)*([a-z][a-z0-9-]+)(.*)");
        int calls = 0;
        for (String line : shCodeLines()) {
            Matcher matcher = call.matcher(line);
            if (matcher.find() && !line.strip().endsWith("() {") && !line.contains("() {")) {
                calls++;
                assertThat(known).as("a command of ledger-cli in: %s", line).contains(matcher.group(1));
                assertOptionsAreDeclared(line, matcher.group(1), usage);
            }
        }
        assertThat(calls).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("INS-026 (changed by INS-046) a re-run keeps what exists: secret-set only with --if-absent, the agent token as a copy (--copy-to, made once by Java), the user only when it does not exist (useradd status 9), no deletion")
    void ins026_aRerunKeepsWhatExists() {
        for (String line : shCodeLines()) {
            if (line.contains("secret-set")) {
                assertThat(line).as(line).contains(" --if-absent");
            }
            if (line.contains("useradd")) {
                assertThat(line).as(line).endsWith("|| [ $? = 9 ]");
            }
        }
        assertThat(text(SH)).contains("token-generate ledger.admin.token --print", "token-generate ledger.mcp.token --print");
        assertThat(shCodeLines().stream().filter(line -> line.contains("ledger.admin.agent-token"))).allMatch(
                line -> line.contains("token-generate ledger.mcp.token --print --copy-to=ledger.admin.agent-token"));
    }

    @Test
    @DisplayName("INS-027 deploy/ holds the two installers and nothing else: the 1.x installers and the unit templates are gone")
    void ins027_theOldInstallerFilesAreGone() {
        assertThat(Repository.filesDirectlyIn("deploy")).containsExactly("install.cmd", "install.sh");
        assertThat(Repository.subdirectories("deploy")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { SH, CMD })
    @DisplayName("INS-021 both scripts name in their header the documentation they follow (Spring Boot, Installing Spring Boot Applications) and say that they are thin")
    void ins021_theHeaderCitesTheDocumentation(String script) {
        String header = String.join("\n", lines(script).stream().limit(25).toList());

        assertThat(header).contains("https://docs.spring.io/spring-boot/how-to/deployment/installing.html").containsIgnoringCase("thin");
    }

    // ---- the Windows script ----

    private static List<String> cmdCodeLines() {
        return lines(CMD).stream().filter(line -> !line.isBlank() && !line.strip().toLowerCase(Locale.ROOT).startsWith("rem ")
                && !"@echo off".equalsIgnoreCase(line.strip())).toList();
    }

    @Test
    @DisplayName("INS-033 (changed by INS-046) install.cmd starts no PowerShell, no download, no package installer, no network or registry tool, no deletion, and no icacls, mkdir or copy (Java does that now): only java, WinSW and sc.exe beside the cmd.exe commands")
    void ins033_onlyTheAllowedCommands() {
        for (String line : cmdCodeLines()) {
            String code = line.replaceAll("\"[^\"]*\"", "\"\"");
            assertThat(FORBIDDEN_CMD.matcher(code).find()).as(line).isFalse();
        }
        Pattern executable = Pattern.compile("[^\\s\"(]*\\.exe");
        Set<String> found = new TreeSet<>();
        for (String line : cmdCodeLines()) {
            Matcher matcher = executable.matcher(line);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        assertThat(found).allSatisfy(exe -> assertThat("java.exe".equals(exe) || "sc.exe".equals(exe)
                || exe.endsWith("%SVC%.exe") || exe.endsWith("%ADMIN%.exe") || exe.endsWith("%%S.exe")).as(exe).isTrue());
        assertThat(String.join("\n", cmdCodeLines())).contains("sc.exe config", "stop /stopwait", "uninstall", "install ");
        assertThat(text(CMD).toLowerCase(Locale.ROOT)).doesNotContain("cloudflare");
    }

    @Test
    @DisplayName("INS-034 install.cmd gives no secret on a command line, reads no environment value of a secret, and only the name follows secret-set and token-generate")
    void ins034_noSecretOnTheCommandLine() {
        for (String line : cmdCodeLines()) {
            assertThat(line).as(line).doesNotContainPattern(SECRET_OPTION);
            if (line.contains("secret-set") || line.contains("token-generate")) {
                assertThat(line).as("only the name of the secret follows the command").matches(
                        ".*(secret-set|token-generate) ledger\\.[a-z.-]+( --print| --if-absent| \"--copy-to=ledger\\.[a-z.-]+\")*( \\|\\| exit /b 1)?");
            }
        }
    }

    @Test
    @DisplayName("INS-035 (changed by INS-046) install.cmd is thin: at most 80 lines in all (half of it comments that name the documentation and the reasons) and at most 40 lines of commands")
    void ins035_theLineBudget() {
        assertThat(lines(CMD)).hasSizeLessThanOrEqualTo(80);
        assertThat(cmdCodeLines()).hasSizeLessThanOrEqualTo(40);
    }

    @Test
    @DisplayName("INS-036 install.cmd uses the names of the Java code (the services, the one service account LocalService, the home, the jars, the platform) and gives ledger-cli only commands and options that exist")
    void ins036_theNamesAreTheOnesOfTheCode() throws IOException {
        String script = text(CMD);
        String home = "C:/ProgramData/x";
        DeployLayout layout = DeployLayout.windows("x", home);

        assertThat(layout.mcpName()).isEqualTo("x");
        assertThat(layout.adminName()).isEqualTo("x-admin");
        assertThat(script).contains("set \"NAME=" + DeployLayout.DEFAULT_NAME + "\"", "set \"SVC=%NAME%\"",
                "set \"ADMIN=%NAME%-admin\"", "set \"ACCOUNT=" + DeployLayout.WINDOWS_SERVICE_ACCOUNT + "\"",
                "set \"WHOME=" + home.replace("/x", "/%NAME%").replace('/', '\\') + "\"",
                "set \"HOME=" + home.replace("/x", "/%NAME%") + "\"",
                "--platform=" + Platform.WINDOWS.name().toLowerCase(Locale.ROOT), "--home=%HOME%",
                "--ledger.secrets.home=%HOME%" + layout.etc().substring(home.length()));
        // the script names the jar of the CLI only; the other jars, WinSW and the driver are copied by prepare-host
        assertThat(script).contains(DeployLayout.CLI_JAR);
        Properties usage = usageTexts();
        Set<String> known = new TreeSet<>(Stream.of(CliCommand.values()).map(CliCommand::cliName).toList());
        Pattern call = Pattern.compile("(?:%CLI%|--ledger\\.secrets\\.home=\\S+)\\s+(?:--\\S+\\s+)*([a-z][a-z-]+)(.*)");
        int calls = 0;
        for (String line : cmdCodeLines()) {
            Matcher matcher = call.matcher(line);
            if (matcher.find() && !line.startsWith("set ")) {
                calls++;
                assertThat(known).as("a command of ledger-cli in: %s", line).contains(matcher.group(1));
                assertOptionsAreDeclared(line, matcher.group(1), usage);
            }
        }
        assertThat(calls).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("INS-037 (changed by INS-046) install.cmd keeps what exists on a re-run (secret-set only with --if-absent, the agent token as a copy made once by Java, no deletion); the folders, ACLs and the hand-over of the secrets are prepare-host and seal-host, and sc.exe sets the one service account")
    void ins037_aRerunKeepsWhatExistsAndTheSecretsAreSealed() {
        String code = String.join("\n", cmdCodeLines());

        for (String line : cmdCodeLines()) {
            if (line.contains("secret-set")) {
                assertThat(line).as(line).contains(" --if-absent");
            }
        }
        assertThat(code).contains("sc.exe config %%S obj= \"%ACCOUNT%\"", "prepare-host", "seal-host",
                "token-generate ledger.admin.token --print", "token-generate ledger.mcp.token --print \"--copy-to=ledger.admin.agent-token\"");
    }

    // ---- the name of the instance (INS-041) ----

    @Test
    @DisplayName("INS-041 install.sh passes --name on as it is, has no instance name of its own except the default, and runs render-deploy --check (the Java validation of every option) before the first operating system step")
    void ins041_theShellScriptTakesTheNameAndChecksItFirst() {
        List<String> code = shCodeLines();
        int check = firstIndex(code, "render-deploy --check");
        int firstStep = firstIndex(code, "useradd");

        assertThat(code).contains("    --name=*) NAME=${arg#*=}; set -- \"$@\" \"$arg\" ;;");
        assertThat(check).as("the check is there").isNotNegative();
        assertThat(check).as("the check comes before the first operating system step").isLessThan(firstStep);
        assertThat(check).isLessThan(firstIndex(code, "prepare-host"));
        assertThat(code.stream().filter(line -> line.contains("pg-ledger")).toList()).containsExactly("NAME=pg-ledger-mcp");
    }

    @Test
    @DisplayName("INS-041 install.cmd takes the name as its own argument (8th, optional), composes the services and the home from it, passes it on quoted, and runs render-deploy --check before the first operating system step")
    void ins041_theCmdScriptTakesTheNameAndChecksItFirst() {
        List<String> code = cmdCodeLines();
        int check = firstIndex(code, "render-deploy --check");

        assertThat(code).contains("set \"NAME=%~8\"", "if \"%NAME%\"==\"\" set \"NAME=pg-ledger-mcp\"");
        // the name goes on once, quoted, in the options that the check, prepare-host and seal-host share
        assertThat(code.stream().filter(line -> line.contains("\"--name=%NAME%\"")).count()).isEqualTo(1);
        assertThat(code.stream().filter(line -> line.contains("render-deploy --check %RENDER%") || line.contains("prepare-host")
                || line.contains("seal-host")).allMatch(line -> line.contains("%RENDER%"))).isTrue();
        assertThat(check).as("the check is there").isNotNegative();
        assertThat(check).as("the check comes before the first operating system step").isLessThan(firstIndex(code, "stop /stopwait"));
        assertThat(check).isLessThan(firstIndex(code, "prepare-host"));
        assertThat(code.stream().filter(line -> line.contains("pg-ledger")).toList())
                .containsExactly("if \"%NAME%\"==\"\" set \"NAME=pg-ledger-mcp\"");
        assertThat(code.stream().filter(line -> line.contains("--check")).count()).isEqualTo(1);
    }

    // ---- the migration from 1.x and the passwords on standard input (INS-043, SEC-024) ----

    @Test
    @DisplayName("INS-043 install.sh takes --from-1x-env=<file> and --stdin itself (neither goes on to render-deploy), checks that the file can be read before the first operating system step, and imports it before any token is generated")
    void ins043_theMigrationOptionsAndTheirOrder() {
        List<String> code = shCodeLines();
        int imported = firstIndex(code, "secret-import-1x");
        int firstToken = firstIndex(code, "token-generate");

        assertThat(code).contains("    --from-1x-env=*) FROM_ENV=${arg#*=} ;;", "    --stdin) STDIN=--stdin ;;");
        assertThat(firstIndex(code, "[ -r \"$FROM_ENV\" ]")).as("the file is checked").isNotNegative()
                .isLessThan(firstIndex(code, "useradd"));
        assertThat(imported).as("the import is there").isNotNegative();
        assertThat(imported).as("the tokens of 1.x are in the store before a token is generated").isLessThan(firstToken);
        assertThat(code.get(imported)).as("root opens the file of root, Java reads standard input, the file is no argument")
                .isEqualTo("[ -z \"$FROM_ENV\" ] || as_svc secret-import-1x --stdin < \"$FROM_ENV\"");
        assertThat(code.stream().filter(line -> line.contains("FROM_ENV") && line.contains("render-deploy"))).isEmpty();
    }

    @Test
    @DisplayName("INS-043 install.sh stops, disables and removes nothing of another installation: no systemctl stop, disable, mask or rm, and the only unit it enables or restarts are its own two")
    void ins043_the1xServicesAreNotTouched() {
        for (String line : shCodeLines()) {
            if (line.contains("systemctl")) {
                assertThat(line).as(line).containsPattern("systemctl (daemon-reload|enable \"\\$SVC\" \"\\$ADMIN\"|restart \"\\$SVC\" \"\\$ADMIN\")$");
            }
        }
        assertThat(text(SH)).doesNotContain("pg-ledger-mcp.service");
    }

    @Test
    @DisplayName("SEC-024 the passwords of install.sh go on standard input only through the flag --stdin of the Java commands: $STDIN follows secret-set and migrate, nothing else reads it, and no secret is an argument")
    void sec024_stdinIsOnlyAFlagForJava() {
        List<String> uses = shCodeLines().stream().filter(line -> line.contains("$STDIN")).toList();

        assertThat(uses).hasSize(2);
        assertThat(uses).anySatisfy(line -> assertThat(line).endsWith("as_svc secret-set ledger.jdbc.password --if-absent $STDIN"));
        assertThat(uses).anySatisfy(line -> assertThat(line).endsWith("--app-account=\"$APP_ACCOUNT\" $STDIN"));
        assertThat(shCodeLines().stream().filter(line -> line.matches(".*\\s<\\s.*")).toList()).as("the one redirect is the 1.x file")
                .hasSize(1);
    }

    // ---- what stays in the scripts, and why (INS-046) ----

    @Test
    @DisplayName("INS-046 install.sh: each step that stays in the script has a comment above it that says why Java cannot do it (useradd, runuser, systemctl, nginx), and prepare-host takes the options it is given: all of them are declared in its usage")
    void ins046_everyStepThatStaysSaysWhy() throws IOException {
        List<String> all = lines(SH);
        Pattern stays = Pattern.compile("(?:^|[{;&|]\\s*)(useradd|runuser|systemctl|nginx)\\s");
        int found = 0;
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i).strip();
            if (!isComment(line) && stays.matcher(line).find()) {
                found++;
                boolean explained = all.subList(Math.max(0, i - 3), i).stream().anyMatch(InstallScriptsTest::isComment);
                assertThat(explained).as("a comment above: %s", line).isTrue();
            }
        }
        assertThat(found).as("useradd, runuser, three systemctl, two nginx").isEqualTo(7);
        Properties usage = usageTexts();
        for (String line : shCodeLines()) {
            if (line.contains(" prepare-host ")) {
                assertOptionsAreDeclared(line, "prepare-host", usage);
            }
            if (line.contains(" render-deploy ")) {
                assertOptionsAreDeclared(line, "render-deploy", usage);
            }
        }
    }

    @Test
    @DisplayName("INS-046 install.cmd: the order is the check, the stop of what runs, prepare-host, the secrets, driver-register, migrate, the services (WinSW, sc.exe), seal-host, the start; it starts no icacls, mkdir or copy and its secrets are never copied or given on a command line")
    void ins046_theOrderOfTheWindowsInstaller() {
        List<String> code = cmdCodeLines();
        List<Integer> order = Stream.of("render-deploy --check", "stop /stopwait", "prepare-host", "secret-set", "driver-register",
                " migrate ", "sc.exe config", "seal-host", "\" start ").map(fragment -> firstIndex(code, fragment)).toList();

        assertThat(order).doesNotContain(-1).isSorted();
        assertThat(String.join("\n", code).toLowerCase(Locale.ROOT)).doesNotContain("icacls", "mkdir", "copy ", "java -version");
        assertThat(code.stream().filter(line -> line.contains("secret-set"))).allMatch(line -> line.contains(" --if-absent"));
    }

    private static int firstIndex(List<String> lines, String fragment) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    // ---- helpers ----

    private static Properties usageTexts() throws IOException {
        Properties bundle = new Properties();
        try (InputStream stream = new ClassPathResource("messages.properties").getInputStream()) {
            bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return bundle;
    }

    /** Every {@code --option} given to the command on the line is in the usage text of that command (Spring properties have a dot). */
    private static void assertOptionsAreDeclared(String line, String command, Properties usage) {
        String text = usage.getProperty(CliCommand.parse(command).orElseThrow().usageKey());
        Matcher option = Pattern.compile("--([a-z][a-z-]*)(?![a-z.-])").matcher(line);
        while (option.find()) {
            assertThat(text).as("--%s in the usage of %s", option.group(1), command).contains("--" + option.group(1));
        }
    }
}

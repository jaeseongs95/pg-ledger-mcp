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
    private static final Set<String> EXTERNAL = Set.of("id", "useradd", "install", "mkdir", "chown", "chmod", "cp", "java",
            "runuser", "systemctl", "nginx");
    private static final Set<String> BUILTIN = Set.of("set", "umask", "echo", "exit", "shift", "command", "case", "esac", "for",
            "done", "fi", "[", "return", "}");
    /** An option that carries a secret value; {@code --ledger.secrets.home=} is a directory, not a secret. */
    private static final Pattern SECRET_OPTION = Pattern
            .compile("(?i)--(?!ledger\\.secrets\\.home=)[a-z.-]*(password|token|secret)[a-z.-]*=");
    private static final Pattern FORBIDDEN_SH = Pattern.compile(
            "\\b(curl|wget|git|sed|awk|apt|apt-get|yum|dnf|zypper|pip|npm|psql|pg_dump|createuser|createdb|certbot|cloudflared|rm|"
                    + "bash|eval|export|env|read|exec|ssh|scp|tar|unzip)\\b");
    private static final Pattern FORBIDDEN_CMD = Pattern.compile(
            "(?i)\\b(powershell|pwsh|curl|wget|bitsadmin|certutil|msiexec|winget|choco|scoop|schtasks|reg|wmic|net|net1|"
                    + "takeown|xcopy|robocopy|move|del|erase|rd|rmdir|ren|ftp|tftp|cscript|wscript|mshta|git)\\b");

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

    /** The shell text of a line with what cannot start a command taken out: quoted text, parameter expansions, escaped quotes. */
    private static String bare(String line) {
        return line.replace("\\\"", "Q").replace("\\'", "Q").replaceAll("\\$\\{[^}]*}", "X");
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
    @DisplayName("INS-021 every command install.sh starts is a shell builtin or one of id, useradd, install, mkdir, chown, chmod, cp, java, runuser, systemctl, nginx")
    void ins021_onlyTheAllowedCommands() {
        Set<String> allowed = new TreeSet<>(EXTERNAL);
        allowed.addAll(BUILTIN);

        assertThat(shCommandWords()).isNotEmpty().allSatisfy(word -> assertThat(allowed).as("command %s", word).contains(word));
        assertThat(new TreeSet<>(shCommandWords())).as("the steps the plan lists are all there").contains("useradd", "install",
                "runuser", "systemctl", "nginx", "java", "id");
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
                assertThat(line).as("only the name of the secret follows the command").matches(
                        ".*(secret-set|token-generate) ledger\\.[a-z.-]+( --print)?");
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
    @DisplayName("INS-024 install.sh is thin: at most 95 lines in all (a third of it the header that names the documentation it follows) and at most 60 lines of commands (the budget is the steps of the plan: java check, one user, directories and jars, one render call, secrets and tokens, one migrate call, units, nginx)")
    void ins024_theLineBudget() {
        assertThat(lines(SH)).hasSizeLessThanOrEqualTo(95);
        assertThat(shCodeLines()).hasSizeLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("INS-025 install.sh uses the names of the Java code (the service user, the second service, the directories, the jars) and gives ledger-cli only commands and options that exist")
    void ins025_theNamesAreTheOnesOfTheCode() throws IOException {
        String script = text(SH);
        DeployLayout layout = DeployLayout.linux();

        assertThat(script).contains("\nSVC=" + DeployLayout.SERVICE_USER + "\n", "\nADMIN=" + DeployLayout.ADMIN_NAME + "\n",
                "\nLIB=" + layout.lib() + "\n", "\nETC=" + layout.etc() + "\n");
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            assertThat(script).contains(jar.replace(".jar", ""));
        }
        Properties usage = usageTexts();
        Set<String> known = new TreeSet<>(Stream.of(CliCommand.values()).map(CliCommand::cliName).toList());
        Pattern call = Pattern.compile("(?<![\\w-])(?:as_svc|cli)\\s+(?:--\\S+\\s+)*([a-z][a-z-]+)(.*)");
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
    @DisplayName("INS-026 a re-run keeps what exists: secret-set only when the file is absent, the copy of the agent token only when absent, the user only when it does not exist, no deletion")
    void ins026_aRerunKeepsWhatExists() {
        for (String line : shCodeLines()) {
            if (line.contains("secret-set") || line.contains("useradd")) {
                assertThat(line).as(line).containsPattern("(\\[ -e |id -u )");
            }
        }
        assertThat(text(SH)).contains("token-generate ledger.admin.token --print", "token-generate ledger.mcp.token --print");
        assertThat(shCodeLines().stream().filter(line -> line.contains("ledger.admin.agent-token"))).allMatch(
                line -> line.contains("[ -e "));
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
    @DisplayName("INS-033 install.cmd starts no PowerShell, no download, no package installer, no network or registry tool, no deletion: only java, WinSW, sc.exe, icacls, mkdir and copy beside the cmd.exe commands")
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
        assertThat(String.join("\n", cmdCodeLines())).contains("icacls ", "mkdir ", "copy ", "sc.exe config", "java -version");
        assertThat(text(CMD).toLowerCase(Locale.ROOT)).doesNotContain("cloudflare");
    }

    @Test
    @DisplayName("INS-034 install.cmd gives no secret on a command line, reads no environment value of a secret, and only the name follows secret-set and token-generate")
    void ins034_noSecretOnTheCommandLine() {
        for (String line : cmdCodeLines()) {
            assertThat(line).as(line).doesNotContainPattern(SECRET_OPTION);
            if (line.contains("secret-set") || line.contains("token-generate")) {
                assertThat(line).as("only the name of the secret follows the command").matches(
                        ".*(secret-set|token-generate) ledger\\.[a-z.-]+( --print)?( \\|\\| exit /b 1)?");
            }
        }
    }

    @Test
    @DisplayName("INS-035 install.cmd is thin: at most 100 lines in all (a quarter of it comments that name the documentation and the reasons) and at most 60 lines of commands")
    void ins035_theLineBudget() {
        assertThat(lines(CMD)).hasSizeLessThanOrEqualTo(100);
        assertThat(cmdCodeLines()).hasSizeLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("INS-036 install.cmd uses the names of the Java code (the services, the one service account LocalService, the home, the jars, the platform) and gives ledger-cli only commands and options that exist")
    void ins036_theNamesAreTheOnesOfTheCode() throws IOException {
        String script = text(CMD);
        String home = "C:/ProgramData/pg-ledger-mcp";
        DeployLayout layout = DeployLayout.windows(home);

        assertThat(script).contains("set \"SVC=" + DeployLayout.MCP_NAME + "\"", "set \"ADMIN=" + DeployLayout.ADMIN_NAME + "\"",
                "set \"ACCOUNT=" + DeployLayout.WINDOWS_SERVICE_ACCOUNT + "\"",
                "set \"WHOME=" + home.replace('/', '\\') + "\"", "set \"HOME=" + home + "\"",
                "--platform=" + Platform.WINDOWS.name().toLowerCase(Locale.ROOT), "--home=%HOME%",
                "--ledger.secrets.home=%HOME%" + layout.etc().substring(home.length()));
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            assertThat(script).contains(jar.replace(".jar", ""));
        }
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
    @DisplayName("INS-037 install.cmd keeps what exists on a re-run (the secret and the agent token only when absent, no deletion), closes the home to everybody but SYSTEM and Administrators, and hands the secrets to the one service account")
    void ins037_aRerunKeepsWhatExistsAndTheSecretsAreSealed() {
        String code = String.join("\n", cmdCodeLines());

        for (String line : cmdCodeLines()) {
            if (line.contains("secret-set") || line.contains("copy \"%SECRETS%\\ledger.mcp.token\"")) {
                assertThat(line).as(line).startsWith("if not exist ");
            }
        }
        // the well-known SIDs of SYSTEM and Administrators (language independent), then the hand-over of the secrets
        assertThat(code).contains("*S-1-5-18", "*S-1-5-32-544", "/inheritance:r", "/setowner \"%ACCOUNT%\"",
                "sc.exe config %%S obj= \"%ACCOUNT%\"");
        assertThat(code).contains("token-generate ledger.admin.token --print", "token-generate ledger.mcp.token --print");
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

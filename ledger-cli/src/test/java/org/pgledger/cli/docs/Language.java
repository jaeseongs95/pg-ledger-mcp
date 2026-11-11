package org.pgledger.cli.docs;

import java.util.List;

/**
 * The two languages of the documents: where the README of each language is, how it names the table headers and the
 * language part of a release note, how it marks a removed item, and the statements it must make in its own words. The
 * facts themselves (tool names, commands, keys) are code and are the same in both.
 *
 * @param readmeFile        the README of this language at the root of the repository
 * @param toolHeader        first header cell of a table of MCP tools
 * @param commandHeader     first header cell of a table of ledger-cli commands
 * @param sectionHeader     first header cell of the table of sections in the release note format
 * @param partHeading       the level-2 heading of this language in a file that holds both languages
 * @param writtenInHangul   whether the text of this language contains Hangul
 * @param removedMarker     lower-case word that marks a removed item in a line
 * @param requiredPhrases   statements the README must make (the DBMS wording and the install command)
 * @param sections          the sections of a release note, in order, named in this language
 */
enum Language {

    EN("README.md", "Tool", "Command", "Section", "English", false, "removed",
            List.of("ANSI SQL", "DBMS-specific", "JDBC driver", "bundled", "UTC", "sudo deploy/install.sh",
                    "deploy\\install.cmd", "macOS is not supported"),
            List.of("Summary", "Breaking changes", "Migration", "New", "Changed", "Removed", "Security", "Requirements",
                    "Known limitations")),
    KO("README.ko.md", "도구", "명령", "섹션", "한국어", true, "제거",
            List.of("ANSI SQL", "DBMS 고유", "JDBC 드라이버", "동봉", "UTC", "sudo deploy/install.sh",
                    "deploy\\install.cmd", "macOS는 지원하지 않습니다"),
            List.of("요약", "호환되지 않는 변경", "이전 절차", "추가", "변경", "제거", "보안", "요구 사항", "알려진 제한"));

    private final String readmeFile;
    private final String toolHeader;
    private final String commandHeader;
    private final String sectionHeader;
    private final String partHeading;
    private final boolean writtenInHangul;
    private final String removedMarker;
    private final List<String> requiredPhrases;
    private final List<String> sections;

    Language(String readmeFile, String toolHeader, String commandHeader, String sectionHeader, String partHeading,
            boolean writtenInHangul, String removedMarker, List<String> requiredPhrases, List<String> sections) {
        this.readmeFile = readmeFile;
        this.toolHeader = toolHeader;
        this.commandHeader = commandHeader;
        this.sectionHeader = sectionHeader;
        this.partHeading = partHeading;
        this.writtenInHangul = writtenInHangul;
        this.removedMarker = removedMarker;
        this.requiredPhrases = requiredPhrases;
        this.sections = sections;
    }

    String readmeFile() {
        return readmeFile;
    }

    String toolHeader() {
        return toolHeader;
    }

    String commandHeader() {
        return commandHeader;
    }

    String sectionHeader() {
        return sectionHeader;
    }

    String partHeading() {
        return partHeading;
    }

    boolean writtenInHangul() {
        return writtenInHangul;
    }

    String removedMarker() {
        return removedMarker;
    }

    List<String> requiredPhrases() {
        return requiredPhrases;
    }

    List<String> sections() {
        return sections;
    }

    /** The sentence of the old README that said the installer was planned: it is untrue now and must not be there (DOC-030). */
    String plannedInstallerSentence() {
        return switch (this) {
            case EN -> "installer is planned";
            case KO -> "설치기는 계획";
        };
    }

    Language other() {
        return switch (this) {
            case EN -> KO;
            case KO -> EN;
        };
    }

    Markdown readme() {
        return Markdown.of(Repository.read(readmeFile));
    }
}

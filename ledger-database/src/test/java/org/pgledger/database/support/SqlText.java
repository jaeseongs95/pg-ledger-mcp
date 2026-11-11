package org.pgledger.database.support;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL text for the static ANSI checks. {@link #stripped()} removes comments and string literals first, so a banned word
 * inside a comment, a literal or an identifier is not a finding; matching is word based and case-insensitive.
 */
public record SqlText(String name, String raw) {

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /** The groups of bans of the catalogue. */
    public enum Group {
        VENDOR_SYNTAX, DDL_OBJECT, SESSION_STATEMENT, TIME_FUNCTION
    }

    /** One banned construct with the pattern that finds it (ANSI-001 .. ANSI-003, ANSI-012, ANSI-014). */
    public enum Banned {
        LIMIT(Group.VENDOR_SYNTAX, "\\bLIMIT\\b"),
        ROWNUM(Group.VENDOR_SYNTAX, "\\bROWNUM\\b"),
        TOP(Group.VENDOR_SYNTAX, "\\bTOP\\b"),
        ILIKE(Group.VENDOR_SYNTAX, "\\bILIKE\\b"),
        REGEX_OPERATOR(Group.VENDOR_SYNTAX, "!~|~\\*?"),
        CAST_OPERATOR(Group.VENDOR_SYNTAX, "::"),
        RETURNING(Group.VENDOR_SYNTAX, "\\bRETURNING\\b"),
        ON_CONFLICT(Group.VENDOR_SYNTAX, "\\bON\\s+CONFLICT\\b"),
        MERGE(Group.VENDOR_SYNTAX, "\\bMERGE\\b"),
        ON_DUPLICATE_KEY(Group.VENDOR_SYNTAX, "\\bON\\s+DUPLICATE\\s+KEY\\b"),
        FOR_UPDATE_OR_SHARE(Group.VENDOR_SYNTAX, "\\bFOR\\s+(UPDATE|SHARE)\\b"),
        WITH_RECURSIVE(Group.VENDOR_SYNTAX, "\\bWITH\\s+RECURSIVE\\b"),
        JSON_FUNCTION(Group.VENDOR_SYNTAX, "\\bJSONB?\\b|\\bJSON_\\w+"),
        ARRAY_FUNCTION(Group.VENDOR_SYNTAX, "\\b(UNNEST|ARRAY_AGG|STRING_AGG)\\b"),
        NOW_FUNCTION(Group.VENDOR_SYNTAX, "\\bNOW\\s*\\("),
        UUID_FUNCTION(Group.VENDOR_SYNTAX, "\\bGEN_RANDOM_UUID\\b"),
        ADVISORY_LOCK(Group.VENDOR_SYNTAX, "\\bPG_ADVISORY\\w*"),
        SEQUENCE_CALL(Group.VENDOR_SYNTAX, "\\b(NEXTVAL|CURRVAL)\\b|\\bNEXT\\s+VALUE\\s+FOR\\b"),
        NULLS_NOT_DISTINCT(Group.VENDOR_SYNTAX, "\\bNULLS\\s+NOT\\s+DISTINCT\\b"),
        ROW_VALUE_COMPARISON(Group.VENDOR_SYNTAX, "\\(\\s*[\\w.]+\\s*,\\s*[\\w.]+\\s*\\)\\s*(<=|>=|<|>|=)\\s*\\("),
        PARTIAL_INDEX(Group.VENDOR_SYNTAX, "\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b[^;]*\\bWHERE\\b"),
        TRIGGER(Group.DDL_OBJECT, "\\bCREATE\\s+(OR\\s+REPLACE\\s+)?TRIGGER\\b"),
        PROCEDURE(Group.DDL_OBJECT, "\\bCREATE\\s+(OR\\s+REPLACE\\s+)?PROCEDURE\\b"),
        FUNCTION(Group.DDL_OBJECT, "\\bCREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\b"),
        VIEW(Group.DDL_OBJECT, "\\bCREATE\\s+(OR\\s+REPLACE\\s+)?VIEW\\b"),
        SEQUENCE(Group.DDL_OBJECT, "\\bCREATE\\s+SEQUENCE\\b"),
        EXTENSION(Group.DDL_OBJECT, "\\bCREATE\\s+EXTENSION\\b"),
        ROLE(Group.DDL_OBJECT, "\\bCREATE\\s+ROLE\\b"),
        IDENTITY(Group.DDL_OBJECT, "\\b(IDENTITY|GENERATED|SERIAL|BIGSERIAL|AUTO_INCREMENT)\\b"),
        DO_BLOCK(Group.DDL_OBJECT, "\\bDO\\s+\\$"),
        SET_STATEMENT(Group.SESSION_STATEMENT, "(?m)^\\s*SET\\b"),
        ALTER_SESSION(Group.SESSION_STATEMENT, "\\bALTER\\s+SESSION\\b"),
        PRAGMA(Group.SESSION_STATEMENT, "\\bPRAGMA\\b"),
        COMMENT_ON(Group.SESSION_STATEMENT, "\\bCOMMENT\\s+ON\\b"),
        CURRENT_TIME_FUNCTION(Group.TIME_FUNCTION,
                "\\b(CURRENT_TIMESTAMP|LOCALTIMESTAMP|CURRENT_DATE|CURRENT_TIME|LOCALTIME|SYSDATE|GETDATE)\\b");

        private final Group group;
        private final Pattern pattern;

        Banned(Group group, String regex) {
            this.group = group;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }

        public Group group() {
            return group;
        }

        boolean foundIn(String stripped) {
            return pattern.matcher(stripped).find();
        }
    }

    /** The text without comments and string literals (a literal becomes an empty one). */
    public String stripped() {
        String noBlock = BLOCK_COMMENT.matcher(raw).replaceAll(" ");
        return LITERAL.matcher(LINE_COMMENT.matcher(noBlock).replaceAll(" ")).replaceAll("''");
    }

    /** The bans of {@code groups} that this text violates. */
    public Set<Banned> findings(Set<Group> groups) {
        String text = stripped();
        return Arrays.stream(Banned.values()).filter(b -> groups.contains(b.group())).filter(b -> b.foundIn(text))
                .collect(() -> EnumSet.noneOf(Banned.class), EnumSet::add, EnumSet::addAll);
    }

    public Set<Banned> findings(Group... groups) {
        return findings(Set.copyOf(List.of(groups)));
    }
}

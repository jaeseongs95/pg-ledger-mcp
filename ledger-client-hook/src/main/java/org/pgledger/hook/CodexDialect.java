package org.pgledger.hook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;

/**
 * Codex PreToolUse. The host conversation id is {@code session_id} (Codex also gives sub-agents the parent id). A stamped
 * call answers {@code allow} with {@code updatedInput}; a refusal answers {@code deny}. Malformed input is denied
 * explicitly with exit 0 (a failing hook process would not be a reliable block).
 *
 * <p>The tool names that are signed for:
 * <ul>
 * <li>{@code mcp__<server>__ledger_<op>}, or {@code mcp__<server>__<server>_ledger_<op>} when the tool reaches the host
 *     through a gateway that prefixes the tool names with the name of its upstream. The prefix before {@code ledger_}
 *     must be exactly the server segment, so a look-alike server is not signed for.
 * <li>The Codex Apps namespace {@code mcp__codex_apps__app_<app>_ledger_<op>} (a real path: the tool of the app
 *     {@code <app>} reaches Codex as {@code app_<app>_ledger_<op>} in the pseudo server {@code codex_apps}, for example
 *     {@code mcp__codex_apps__app_ledger_ledger_save} for the app {@code ledger}). The audience here is the app, not the
 *     pseudo server that every app shares, so the server identity is {@code codex_apps.<app>} and the key is kept per
 *     (that identity, author): the owner registers the key printed by {@code session-key codex_apps.<app> <author>}. An
 *     app with another name gets another identity and another key, so a look-alike app never gets a signature that
 *     verifies for the real one.
 * </ul>
 * The operation is one of the ten known ones, exactly.
 */
record CodexDialect(JsonCodec codec) implements HostDialect {

    private static final String EVENT_NAME = "PreToolUse";
    private static final String CODEX_APPS = "codex_apps";
    private static final int MAX_APP_LENGTH = McpToolName.MAX_SERVER_LENGTH - CODEX_APPS.length() - 1;
    /** Group 1 is the server, group 2 the operation; compiled once. */
    private static final Pattern TOOL_NAME = Pattern.compile("^mcp__([A-Za-z0-9_-]{1," + McpToolName.MAX_SERVER_LENGTH
            + "}?)__(?:\\1_)?ledger_" + WriteTool.alternation() + "$");
    /** Group 1 is the app, group 2 the operation; compiled once. */
    private static final Pattern APP_TOOL_NAME = Pattern.compile("^mcp__" + CODEX_APPS + "__app_([A-Za-z0-9][A-Za-z0-9_-]{0,"
            + (MAX_APP_LENGTH - 1) + "}?)_ledger_" + WriteTool.alternation() + "$");

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        return Intake.ofFlatEvent(codec.readEvent(stdin, FlatEvent.class), CodexDialect::toolName);
    }

    /** The shapes of the list above: the plain server shape first, then the Codex Apps namespace. */
    private static Optional<McpToolName> toolName(String hostToolName) {
        Matcher plain = TOOL_NAME.matcher(hostToolName);
        if (plain.matches()) {
            return McpToolName.ofOperation(plain.group(1), plain.group(2));
        }
        Matcher app = APP_TOOL_NAME.matcher(hostToolName);
        if (app.matches()) {
            return McpToolName.ofOperation(CODEX_APPS + "." + app.group(1), app.group(2));
        }
        return Optional.empty();
    }

    @Override
    public Response malformedInput() {
        return deny(Refusal.MALFORMED_INPUT);
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Silent();
    }

    @Override
    public Response deny(Refusal reason) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "allow", null, stamped.arguments()));
    }

    private static Response document(HostDocument.PreToolUse.Specific specific) {
        return new Response.Document(new HostDocument.PreToolUse(specific));
    }
}

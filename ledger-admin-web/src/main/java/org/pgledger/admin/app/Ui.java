package org.pgledger.admin.app;

import org.springframework.stereotype.Component;

/** What the page templates ask about the application (the navigation differs in setup mode). */
@Component("ui")
public class Ui {

    private final AdminMode mode;
    private final LedgerSourceProperties source;

    public Ui(AdminMode mode, LedgerSourceProperties source) {
        this.mode = mode;
        this.source = source;
    }

    /** Where the source code is offered (the link in the footer of every page, AGPL section 13). */
    public String sourceUrl() {
        return source.url();
    }

    public boolean console() {
        return mode == AdminMode.CONSOLE;
    }

    public boolean setup() {
        return mode == AdminMode.SETUP;
    }
}

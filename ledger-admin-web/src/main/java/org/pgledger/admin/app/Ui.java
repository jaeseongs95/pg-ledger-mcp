package org.pgledger.admin.app;

import org.springframework.stereotype.Component;

/** What the page templates ask about the application (the navigation differs in setup mode). */
@Component("ui")
public class Ui {

    private final AdminMode mode;

    public Ui(AdminMode mode) {
        this.mode = mode;
    }

    public boolean console() {
        return mode == AdminMode.CONSOLE;
    }

    public boolean setup() {
        return mode == AdminMode.SETUP;
    }
}

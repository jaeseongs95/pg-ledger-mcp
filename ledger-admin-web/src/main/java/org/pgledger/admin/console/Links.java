package org.pgledger.admin.console;

import org.springframework.stereotype.Component;

/** What the pages may turn into a link: evidence addresses are shown as links only when they are http or https. */
@Component("links")
public class Links {

    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";

    /** True for an address that starts with {@code http://} or {@code https://} (any case). */
    public boolean isHttp(String address) {
        return address != null && (address.regionMatches(true, 0, HTTP, 0, HTTP.length())
                || address.regionMatches(true, 0, HTTPS, 0, HTTPS.length()));
    }
}

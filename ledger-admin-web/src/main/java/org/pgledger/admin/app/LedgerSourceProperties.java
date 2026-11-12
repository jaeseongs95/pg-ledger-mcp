package org.pgledger.admin.app;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where the source code of the running program is offered ({@code ledger.source.url}): the admin web shows it as a link in
 * the footer of its pages. The product is licensed under the GNU Affero General Public License, whose section 13 asks whoever
 * runs a modified version as a network service to offer the users of that service the corresponding source code; such an
 * operator sets this to the place where the source of the modified version is offered. The default is the public repository
 * of the project. Only the users of the admin web see it; the answers of the MCP server do not carry it.
 *
 * @param url an http or https address with a host and without user information
 */
@Validated
@ConfigurationProperties(prefix = "ledger.source")
public record LedgerSourceProperties(@DefaultValue(LedgerSourceProperties.PUBLIC_REPOSITORY) String url) {

    /** The public repository of the project (the default). */
    public static final String PUBLIC_REPOSITORY = "https://github.com/jaeseongs95/pg-ledger-mcp";

    /**
     * The address is an http or https address with a host and without user information: it becomes the target of a link, so
     * no other scheme (such as {@code javascript:}) may reach a page. A boolean, so the failure names the setting and never
     * prints the value.
     */
    @AssertTrue(message = "ledger.source.url must be an http or https address with a host and without user information")
    public boolean isUrlUsable() {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        var address = UriComponentsBuilder.fromUriString(url).build();
        String scheme = address.getScheme();
        boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        return web && StringUtils.hasText(address.getHost()) && address.getUserInfo() == null;
    }
}

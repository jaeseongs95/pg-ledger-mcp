package org.pgledger.cli.deploy;

import java.util.List;
import java.util.stream.Stream;

/**
 * The nginx site of the public host names of the two services (the same on every platform): one {@code server} block for the names of
 * the MCP server, one for the names of the admin web when it has any (several names are one {@code server_name}, the same names as
 * the allowed hosts of the service). It overwrites {@code X-Forwarded-For} with the
 * address of the connection ({@code $remote_addr}) and never appends to what the client sent, because the admin web trusts
 * that header from the loopback proxy only and counts its failed attempts per address; {@code Host} is the host of the
 * request. See https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header.
 *
 * <p>The TLS certificate is the operator's own (nothing here issues or renews it), or it is automatic
 * ({@link DeployParameters.Acme}, {@code --tls-acme}): then every own host name gets a port 80 block that serves the HTTP-01
 * proof of the certificate authority ({@code /.well-known/acme-challenge/} from the web root of the instance) and redirects
 * everything else to https, and the 443 blocks use the PEM files that {@code tls-issue} and {@code tls-renew} write. The 443
 * blocks are written only when those files exist ({@link DeployParameters.Acme#certificateIssued()}): nginx refuses to load a
 * {@code listen 443 ssl} block whose certificate file is missing, and the first certificate can only be ordered while nginx
 * answers on port 80. The site holds the {@code server} blocks of its own host names and nothing else: it takes no
 * {@code default_server}, touches no other site and sets no directive that changes another site, so it can live next to any
 * other service of the host.
 *
 * <p>The port 80 blocks are hardened (FUP-NGX), because that port is open to the whole network. A block serves a file of the web
 * root only for {@code /.well-known/acme-challenge/<token>} with a base64url token (RFC 8555 section 8.3) and the methods GET
 * and HEAD, answers 404 to everything else below that path, and redirects the rest to the fixed name of the block (never to
 * {@code $host}). It limits the size and the time of a request, the rate of the requests and the number of connections of an
 * address (answer 429), and hides the version ({@code server_tokens off}, in the 443 blocks too). The only lines outside a
 * {@code server} block are the two shared memory zones of those limits, {@code limit_req_zone} and {@code limit_conn_zone}, which
 * nginx allows in the {@code http} context only: the file is included there ({@code conf.d} or {@code sites-enabled}). Their names
 * carry the name of the instance, so a zone is used by this site only; declaring a zone changes the behaviour of no other site
 * and of no other instance, and it is the one thing the site sets outside its blocks. Directives and their contexts:
 * https://nginx.org/en/docs/http/ngx_http_limit_req_module.html, https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html,
 * https://nginx.org/en/docs/http/ngx_http_core_module.html (client_max_body_size, client_header_timeout, client_body_timeout,
 * keepalive_timeout, server_tokens, default_type, limit_except, try_files).
 */
final class NginxSite {

    private NginxSite() {
    }

    static String render(DeployParameters p) {
        StringBuilder site = new StringBuilder();
        DeployParameters.Acme acme = p.nginx().acme();
        Text.line(site, Text.GENERATED);
        if (acme == null) {
            Text.line(site, "# TLS: the certificate and the key are the operator's own; nothing here issues or renews a certificate.");
        } else {
            Text.line(site, "# TLS: the certificate is made and renewed by `ledger-cli tls-issue` and `tls-renew` (ACME, HTTP-01): the port 80 blocks");
            Text.line(site, "# serve the proof from the web root and redirect everything else to https. The blocks of port 443 are written once the");
            Text.line(site, "# certificate files exist (nginx cannot load them before): run render-deploy again after the first tls-issue.");
        }
        Text.line(site, "# X-Forwarded-For is overwritten with the address of the connection and never appended to: the admin web trusts it");
        Text.line(site, "# from this proxy only and counts failed attempts per address.");
        if (acme != null) {
            zones(site, p.layout().name());
            // one port 80 block for each own name (a redirect names the name it answered to), the names of the MCP server first
            List<String> names = Stream.concat(p.mcpHosts().stream(), p.adminHosts().stream()).toList();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    Text.line(site, "");
                }
                challenge(site, p, names.get(i));
            }
            if (!acme.certificateIssued()) {
                return site.toString();
            }
            Text.line(site, "");
        }
        // one block for the names of each service (its names in one server_name, the same names as its allowed hosts); the admin
        // web has a block only when it has a public name
        server(site, p, p.mcpHosts(), p.mcpPort(), true);
        if (!p.adminHosts().isEmpty()) {
            Text.line(site, "");
            server(site, p, p.adminHosts(), p.adminPort(), false);
        }
        return site.toString();
    }

    /** The name of the zone that counts the requests per address of the port 80 blocks of an instance. */
    private static String requestZone(String instance) {
        return instance + "-acme-req";
    }

    /** The name of the zone that counts the connections per address of the port 80 blocks of an instance. */
    private static String connectionZone(String instance) {
        return instance + "-acme-conn";
    }

    /**
     * The two shared memory zones of the port 80 blocks, the first lines after the comments (outside every block: the file is
     * included in the {@code http} context, where {@code limit_req_zone} and {@code limit_conn_zone} are allowed). The key is
     * the address of the connection in its binary form (a fixed size, IPv4 and IPv6); 1 MiB holds about 16000 states.
     */
    private static void zones(StringBuilder site, String instance) {
        Text.line(site, "# Port 80 is open to the whole network, so its blocks serve the proof file only and limit what a client may do.");
        Text.line(site, "# The numbers are generous on purpose: the certificate authority checks from several places at the same time, each from its own address,");
        Text.line(site, "# and asks a few times per address and order, so 10 requests per second (a burst of 20) and 20 connections per address are");
        Text.line(site, "# far above what it needs and far below what a flood needs. The zone names carry the instance name: no other site is affected.");
        Text.line(site, "limit_req_zone $binary_remote_addr zone=" + requestZone(instance) + ":1m rate=10r/s;");
        Text.line(site, "limit_conn_zone $binary_remote_addr zone=" + connectionZone(instance) + ":1m;");
        Text.line(site, "");
    }

    /**
     * The port 80 block of one own host name: the proof of the certificate authority, and a redirect for everything else. The
     * token of the proof is base64url without padding (RFC 8555 section 8.3), so the regular expression of the first location lets a
     * path through only when it has such a token; GET includes HEAD for {@code limit_except}, so no other method passes. Every
     * other request below the proof path is a 404, and the redirect names the host of the block, never {@code $host}.
     */
    private static void challenge(StringBuilder site, DeployParameters p, String host) {
        String instance = p.layout().name();
        Text.line(site, "server {");
        Text.line(site, "    listen 80;");
        Text.line(site, "    listen [::]:80;");
        Text.line(site, "    server_name " + host + ";");
        Text.line(site, "");
        Text.line(site, "    server_tokens off;");
        Text.line(site, "    client_max_body_size 1k;");
        Text.line(site, "    client_header_timeout 10s;");
        Text.line(site, "    client_body_timeout 10s;");
        Text.line(site, "    keepalive_timeout 10s;");
        Text.line(site, "    limit_req zone=" + requestZone(instance) + " burst=20 nodelay;");
        Text.line(site, "    limit_req_status 429;");
        Text.line(site, "    limit_conn " + connectionZone(instance) + " 20;");
        Text.line(site, "    limit_conn_status 429;");
        Text.line(site, "");
        Text.line(site, "    location ~ \"^/\\.well-known/acme-challenge/[A-Za-z0-9_-]+$\" {");
        Text.line(site, "        limit_except GET { deny all; }");
        Text.line(site, "        root " + p.layout().acmeWebroot() + ";");
        Text.line(site, "        default_type text/plain;");
        Text.line(site, "        try_files $uri =404;");
        Text.line(site, "    }");
        Text.line(site, "    location /.well-known/acme-challenge/ {");
        Text.line(site, "        return 404;");
        Text.line(site, "    }");
        Text.line(site, "    location / {");
        Text.line(site, "        return 301 https://" + host + "$request_uri;");
        Text.line(site, "    }");
        Text.line(site, "}");
    }

    private static void server(StringBuilder site, DeployParameters p, List<String> hosts, int port, boolean streaming) {
        Text.line(site, "server {");
        Text.line(site, "    listen 443 ssl;");
        Text.line(site, "    listen [::]:443 ssl;");
        Text.line(site, "    server_name " + String.join(" ", hosts) + ";");
        Text.line(site, "");
        if (p.nginx().acme() != null) {
            // the version is hidden in the blocks of the automatic site; the operator's own certificate site stays as it was
            Text.line(site, "    server_tokens off;");
        }
        Text.line(site, "    ssl_certificate " + p.nginx().certificate() + ";");
        Text.line(site, "    ssl_certificate_key " + p.nginx().key() + ";");
        Text.line(site, "    ssl_protocols TLSv1.2 TLSv1.3;");
        Text.line(site, "");
        Text.line(site, "    location / {");
        Text.line(site, "        proxy_pass http://127.0.0.1:" + port + ";");
        Text.line(site, "        proxy_http_version 1.1;");
        Text.line(site, "        proxy_set_header Host $host;");
        Text.line(site, "        proxy_set_header X-Forwarded-For $remote_addr;");
        Text.line(site, "        proxy_set_header X-Forwarded-Proto $scheme;");
        Text.line(site, "        proxy_set_header Connection \"\";");
        if (streaming) {
            // Streamable HTTP answers may be event streams: no buffering, and a read timeout that suits a long call
            Text.line(site, "        proxy_buffering off;");
            Text.line(site, "        proxy_read_timeout 3600s;");
        }
        Text.line(site, "    }");
        Text.line(site, "}");
    }
}

package org.pgledger.cli.deploy;

/**
 * The nginx site of the two public host names (the same on every platform). It overwrites {@code X-Forwarded-For} with the
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
 * {@code default_server}, touches no other site and sets no global directive, so it can live next to any other service of
 * the host.
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
            challenge(site, p, p.mcpHost());
            Text.line(site, "");
            challenge(site, p, p.adminHost());
            if (!acme.certificateIssued()) {
                return site.toString();
            }
            Text.line(site, "");
        }
        server(site, p, p.mcpHost(), p.mcpPort(), true);
        Text.line(site, "");
        server(site, p, p.adminHost(), p.adminPort(), false);
        return site.toString();
    }

    /** The port 80 block of one own host name: the proof of the certificate authority, and a redirect for everything else. */
    private static void challenge(StringBuilder site, DeployParameters p, String host) {
        Text.line(site, "server {");
        Text.line(site, "    listen 80;");
        Text.line(site, "    listen [::]:80;");
        Text.line(site, "    server_name " + host + ";");
        Text.line(site, "");
        Text.line(site, "    location /.well-known/acme-challenge/ {");
        Text.line(site, "        root " + p.layout().acmeWebroot() + ";");
        Text.line(site, "    }");
        Text.line(site, "    location / {");
        Text.line(site, "        return 301 https://" + host + "$request_uri;");
        Text.line(site, "    }");
        Text.line(site, "}");
    }

    private static void server(StringBuilder site, DeployParameters p, String host, int port, boolean streaming) {
        Text.line(site, "server {");
        Text.line(site, "    listen 443 ssl;");
        Text.line(site, "    listen [::]:443 ssl;");
        Text.line(site, "    server_name " + host + ";");
        Text.line(site, "");
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

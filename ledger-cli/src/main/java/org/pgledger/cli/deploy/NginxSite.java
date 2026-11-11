package org.pgledger.cli.deploy;

/**
 * The nginx site of the two public host names (the same on every platform). It overwrites {@code X-Forwarded-For} with the
 * address of the connection ({@code $remote_addr}) and never appends to what the client sent, because the admin web trusts
 * that header from the loopback proxy only and counts its failed attempts per address; {@code Host} is the host of the
 * request. See https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header. The TLS certificate and key are
 * the operator's own: nothing here issues or renews a certificate.
 */
final class NginxSite {

    private NginxSite() {
    }

    static String render(DeployParameters p) {
        StringBuilder site = new StringBuilder();
        Text.line(site, Text.GENERATED);
        Text.line(site, "# TLS: the certificate and the key are the operator's own; nothing here issues or renews a certificate.");
        Text.line(site, "# X-Forwarded-For is overwritten with the address of the connection and never appended to: the admin web trusts it");
        Text.line(site, "# from this proxy only and counts failed attempts per address.");
        server(site, p, p.mcpHost(), p.mcpPort(), true);
        Text.line(site, "");
        server(site, p, p.adminHost(), p.adminPort(), false);
        return site.toString();
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

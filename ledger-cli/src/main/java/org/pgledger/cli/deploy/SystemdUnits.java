package org.pgledger.cli.deploy;

/**
 * The systemd unit of a service (the Linux way to run it). The units follow "Installing Spring Boot Applications"
 * (https://docs.spring.io/spring-boot/how-to/deployment/installing.html): a unit in {@code /etc/systemd/system}, a user that
 * is not root, {@code SuccessExitStatus=143} (the exit status of a Java process that ends on SIGTERM), enabled with
 * {@code systemctl enable}. The sandbox is systemd.exec(5)
 * (https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html): {@code ProtectSystem=strict} makes the whole
 * file system read-only for the service except the paths of {@code ReadWritePaths=}; {@code CapabilityBoundingSet=} with no
 * value drops every capability; {@code UMask=0077} makes what the service creates owner-only. {@code MemoryDenyWriteExecute}
 * is not set because the JIT compiler of the JVM needs memory that is writable and executable. Both units run as the one
 * service user.
 */
final class SystemdUnits {

    private SystemdUnits() {
    }

    /** The path of the one privileged command of the renewal service; systemd needs an absolute path. */
    static final String RELOAD_NGINX = "/usr/bin/systemctl reload nginx";

    /**
     * The service that renews the certificate ({@code tls-renew}), started by {@link #renewTimer}. It runs as the service user
     * and is as closed as the other two: no capability, no new privilege, a read-only file system but the TLS directory (the
     * keys and the certificate) and the web root (the token files). Its one privileged step is
     * {@code ExecStartPost=+/usr/bin/systemctl reload nginx}: the {@code +} prefix runs that single command with full
     * privileges outside the sandbox (systemd.service(5)), because only root may reload nginx. It is a fixed command line of
     * the unit, which only root can change: no shell, no argument and no input from the service user, no sudo rule and no
     * polkit rule to maintain, and the process that talks to the network and writes the keys keeps no privilege at all. A
     * reload of a server whose certificate did not change is harmless, so it runs after every successful run, due or not. The
     * {@code UMask=0022} is on purpose: the mode of a file is cut by the umask, and the token files and the chain have to be
     * readable by the web server (the keys are created {@code rw-------} explicitly).
     * Documentation: https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html .
     */
    static String renewService(DeployParameters p) {
        DeployLayout layout = p.layout();
        StringBuilder unit = new StringBuilder();
        Text.line(unit, Text.GENERATED);
        Text.line(unit, "# Renews the certificate of " + layout.name() + " (tls-renew) and reloads nginx; started by " + layout.tlsRenewName()
                + ".timer. The one privileged step is the line with the + prefix: see systemd.service(5), ExecStart.");
        Text.line(unit, "[Unit]");
        Text.line(unit, "Description=" + layout.tlsRenewName() + ": renew the TLS certificate of " + layout.name());
        Text.line(unit, "After=network-online.target");
        Text.line(unit, "Wants=network-online.target");
        Text.line(unit, "");
        Text.line(unit, "[Service]");
        Text.line(unit, "Type=oneshot");
        Text.line(unit, "User=" + layout.serviceUser());
        Text.line(unit, "Group=" + layout.serviceUser());
        StringBuilder start = new StringBuilder("ExecStart=" + p.java() + " -Duser.timezone=UTC -jar " + layout.lib() + "/"
                + DeployLayout.CLI_JAR + " --ledger.secrets.instance=" + layout.name() + " tls-renew --name=" + layout.name()
                + " --mcp-host=" + p.mcpHost() + " --admin-host=" + p.adminHost());
        if (p.nginx().acme().directory() != null) {
            start.append(" --acme-directory=").append(p.nginx().acme().directory());
        }
        Text.line(unit, start.toString());
        Text.line(unit, "ExecStartPost=+" + RELOAD_NGINX);
        Text.line(unit, "NoNewPrivileges=true");
        Text.line(unit, "ProtectSystem=strict");
        Text.line(unit, "ReadWritePaths=" + layout.tlsDirectory() + " " + layout.acmeWebroot());
        Text.line(unit, "ProtectHome=true");
        Text.line(unit, "PrivateTmp=true");
        Text.line(unit, "PrivateDevices=true");
        Text.line(unit, "ProtectKernelTunables=true");
        Text.line(unit, "ProtectKernelModules=true");
        Text.line(unit, "ProtectControlGroups=true");
        Text.line(unit, "RestrictSUIDSGID=true");
        Text.line(unit, "LockPersonality=true");
        Text.line(unit, "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX");
        Text.line(unit, "CapabilityBoundingSet=");
        Text.line(unit, "UMask=0022");
        return unit.toString();
    }

    /** The timer of the renewal: twice a day (a certificate that is not due costs nothing), at a random time, and a missed run is made up. */
    static String renewTimer(DeployParameters p) {
        DeployLayout layout = p.layout();
        StringBuilder unit = new StringBuilder();
        Text.line(unit, Text.GENERATED);
        Text.line(unit, "# See systemd.timer(5): https://www.freedesktop.org/software/systemd/man/latest/systemd.timer.html");
        Text.line(unit, "[Unit]");
        Text.line(unit, "Description=" + layout.tlsRenewName() + ": twice a day, renew the TLS certificate of " + layout.name()
                + " when it is due");
        Text.line(unit, "");
        Text.line(unit, "[Timer]");
        Text.line(unit, "OnCalendar=*-*-* 00,12:00:00");
        Text.line(unit, "RandomizedDelaySec=3600");
        Text.line(unit, "Persistent=true");
        Text.line(unit, "Unit=" + layout.tlsRenewName() + ".service");
        Text.line(unit, "");
        Text.line(unit, "[Install]");
        Text.line(unit, "WantedBy=timers.target");
        return unit.toString();
    }

    /**
     * @param after         the units this one starts after
     * @param readWritePath the one path the service may write, or null (the whole file system is read-only for it)
     */
    static String unit(DeployParameters p, Service service, String after, String readWritePath) {
        StringBuilder unit = new StringBuilder();
        Text.line(unit, Text.GENERATED);
        Text.line(unit, "# Follows \"Installing Spring Boot Applications\" (systemd service) and systemd.exec(5) (sandboxing).");
        Text.line(unit, "[Unit]");
        Text.line(unit, "Description=" + service.name() + ": " + service.description());
        Text.line(unit, "After=" + after);
        Text.line(unit, "Wants=network-online.target");
        Text.line(unit, "");
        Text.line(unit, "[Service]");
        Text.line(unit, "Type=simple");
        Text.line(unit, "User=" + p.layout().serviceUser());
        Text.line(unit, "Group=" + p.layout().serviceUser());
        Text.line(unit, "ExecStart=" + p.java() + " " + service.arguments(p.layout()));
        Text.line(unit, "SuccessExitStatus=143");
        Text.line(unit, "Restart=on-failure");
        Text.line(unit, "RestartSec=5");
        Text.line(unit, "NoNewPrivileges=true");
        Text.line(unit, "ProtectSystem=strict");
        if (readWritePath != null) {
            // the driver page of the admin web writes the registration file next to its settings
            Text.line(unit, "ReadWritePaths=" + readWritePath);
        }
        Text.line(unit, "ProtectHome=true");
        Text.line(unit, "PrivateTmp=true");
        Text.line(unit, "PrivateDevices=true");
        Text.line(unit, "ProtectKernelTunables=true");
        Text.line(unit, "ProtectKernelModules=true");
        Text.line(unit, "ProtectControlGroups=true");
        Text.line(unit, "RestrictSUIDSGID=true");
        Text.line(unit, "LockPersonality=true");
        Text.line(unit, "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX");
        Text.line(unit, "CapabilityBoundingSet=");
        Text.line(unit, "UMask=0077");
        Text.line(unit, "");
        Text.line(unit, "[Install]");
        Text.line(unit, "WantedBy=multi-user.target");
        return unit.toString();
    }
}

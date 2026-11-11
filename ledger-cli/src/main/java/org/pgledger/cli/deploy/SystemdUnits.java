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
        Text.line(unit, "User=" + DeployLayout.SERVICE_USER);
        Text.line(unit, "Group=" + DeployLayout.SERVICE_USER);
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

package org.pgledger.cli.deploy;

/**
 * The WinSW configuration of a service (the Windows way to run it). Spring Boot's documentation for Windows services
 * ("Microsoft Windows Services" in https://docs.spring.io/spring-boot/how-to/deployment/installing.html) points to WinSW
 * (https://github.com/winsw/winsw): the executable of WinSW, renamed to the id of the service, and a file of the same name
 * with the extension {@code .xml} next to it. The elements used here are those of the XML configuration file of WinSW
 * (https://github.com/winsw/winsw/blob/v2.12.0/docs/xml-config-file.md): {@code id}, {@code name}, {@code description},
 * {@code executable}, {@code arguments}, {@code logpath}, {@code log}, {@code onfailure}, {@code resetfailure},
 * {@code startmode}. Restarting after a failure is WinSW's {@code onfailure}; the account the service runs as is not set
 * here but by {@code sc.exe config} in {@code deploy/install.cmd}, because it is the one account of the installation
 * ({@link DeployLayout#WINDOWS_SERVICE_ACCOUNT}). WinSW itself is obtained by the operator and passed to the installer, like
 * the JDBC driver: the product bundles and downloads nothing.
 *
 * <p>Paths are written with backslashes only where Windows needs them (the executable and the log directory); the arguments
 * are read by Java and Spring Boot, which accept {@code /}.
 */
final class WinswServices {

    private WinswServices() {
    }

    static String configuration(DeployParameters p, Service service) {
        StringBuilder xml = new StringBuilder();
        Text.line(xml, "<!-- " + Text.MARKER + " (deploy/install.cmd). Do not edit: the next run replaces this file. -->");
        Text.line(xml, "<service>");
        Text.line(xml, "  <id>" + service.name() + "</id>");
        Text.line(xml, "  <name>" + service.name() + "</name>");
        Text.line(xml, "  <description>" + service.name() + ": " + service.description() + "</description>");
        Text.line(xml, "  <executable>" + backslashes(p.java()) + "</executable>");
        Text.line(xml, "  <arguments>" + service.arguments(p.layout()) + "</arguments>");
        Text.line(xml, "  <startmode>Automatic</startmode>");
        Text.line(xml, "  <logpath>" + backslashes(p.layout().logs()) + "</logpath>");
        Text.line(xml, "  <log mode=\"roll-by-size\">");
        Text.line(xml, "    <sizeThreshold>10240</sizeThreshold>");
        Text.line(xml, "    <keepFiles>8</keepFiles>");
        Text.line(xml, "  </log>");
        Text.line(xml, "  <onfailure action=\"restart\" delay=\"10 sec\"/>");
        Text.line(xml, "  <onfailure action=\"restart\" delay=\"30 sec\"/>");
        Text.line(xml, "  <onfailure action=\"restart\" delay=\"60 sec\"/>");
        Text.line(xml, "  <resetfailure>1 hour</resetfailure>");
        Text.line(xml, "</service>");
        return xml.toString();
    }

    private static String backslashes(String path) {
        return path.replace('/', '\\');
    }
}

#!/bin/sh
# deploy/install.sh: the installer of pg-ledger-mcp 2.x for a Linux host (a Linux server). It is thin on purpose: it starts
# only what Java cannot do (create the operating system user, switch to it, enable and restart the units, reload nginx) and
# everything else is `java -jar ledger-cli.jar <command>`: render-deploy --check (every option, validated first), prepare-host
# (directories with owner and mode, the jars and the driver copied, units, settings, nginx site), secret-import-1x,
# secret-set, token-generate, driver-register and migrate (schema and ANSI GRANTs).
# Documentation it follows: Spring Boot, "Installing Spring Boot Applications" (a systemd unit in /etc/systemd/system,
# `systemctl enable`): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; systemd.exec(5) (User=, the
# sandboxing options of the rendered units); nginx proxy_module `proxy_set_header` (the rendered site).
# One service user (named after the instance, default pg-ledger-mcp, never root) runs the MCP server and the admin web: the
# secrets are readable by it alone, so the accounts the agents run as cannot read them. --name=<instance> gives an instance of
# its own (services <name> and <name>-admin, /opt/<name>, /etc/<name>), so two can live on one host with their own ports; the
# name is validated by Java (render-deploy --check) before anything is created. Two database accounts: the application account
# (both servers and the owner commands) and the DDL account (migrate and the grants; its password is typed and not stored).
# Run as root: sudo deploy/install.sh --jars=<dir> --driver-jar=<file> --driver-class=<class> --owner-account=<account>
#   --app-account=<account> --jdbc-url=<url> [the other options of render-deploy]
# Both accounts are created beforehand with the DBMS's own tools. It installs no package and no database. A re-run is an
# upgrade: jars, driver jar and files are replaced, the schema and the grants are applied again, the services are restarted;
# secrets, tokens and data are never replaced. The Java 25 check is the JVM's: the jars are compiled for it and no other runtime loads them.
# Migration from 1.x (the operator stops the 1.x services first, see the README: this script never stops, disables or removes a
# service of another installation): --from-1x-env=<file> takes the two tokens of the 1.x environment file over as secrets before
# any token is generated, so the clients and the admin sign-in keep working; the file belongs to root, so this script opens it
# and Java reads it from standard input. A unit file of 1.x that a new unit replaces is kept as <file>.1x-backup by prepare-host.
# --stdin gives the passwords on standard input instead of the terminal (an installation without one), as docker login
# --password-stdin does: first the password of the application account (read only while it is not stored), then the DDL one.
set -eu
umask 022

NAME=pg-ledger-mcp
JARS=
DRIVER_JAR=
DRIVER_CLASS=
OWNER=
APP_ACCOUNT=
NGINX=
FROM_ENV=
STDIN=

die() { echo "install.sh: $*" >&2; exit 1; }
# runuser: a Java process cannot become another user, so the commands that touch the secrets are started as the service user.
as_svc() { runuser -u "$SVC" -- java -Duser.timezone=UTC -jar "$LIB/ledger-cli.jar" --ledger.secrets.instance="$NAME" "$@"; }

# The options read here are taken out; the rest (a JDBC URL, ports, host names, TLS paths) goes on to Java.
for arg in "$@"; do
  shift
  case $arg in
    --name=*) NAME=${arg#*=}; set -- "$@" "$arg" ;;
    --jars=*) JARS=${arg#*=} ;;
    --driver-jar=*) DRIVER_JAR=${arg#*=} ;;
    --driver-class=*) DRIVER_CLASS=${arg#*=} ;;
    --owner-account=*) OWNER=${arg#*=} ;;
    --app-account=*) APP_ACCOUNT=${arg#*=}; set -- "$@" "$arg" ;;
    --nginx-conf=*) NGINX=1; set -- "$@" "$arg" ;;
    --from-1x-env=*) FROM_ENV=${arg#*=} ;;
    --stdin) STDIN=--stdin ;;
    *) set -- "$@" "$arg" ;;
  esac
done
SVC=$NAME
ADMIN=$NAME-admin
LIB=/opt/$NAME
ETC=/etc/$NAME
for v in "$JARS" "$DRIVER_JAR" "$DRIVER_CLASS" "$OWNER" "$APP_ACCOUNT"; do [ -n "$v" ] || die "an option is missing: see the header of this file"; done
[ -z "$FROM_ENV" ] || [ -r "$FROM_ENV" ] || die "the 1.x environment file cannot be read: $FROM_ENV"
JAVA=$(command -v java) || die "java is not installed"
# Every option, the name included, is validated by Java before anything is created.
java -Duser.timezone=UTC -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent render-deploy --check --java="$JAVA" "$@"

# useradd: Java has no API to create an operating system user. Status 9 is "name in use": the user of an earlier run stays.
useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$SVC" || [ $? = 9 ]
# Directories with owner and mode, the jars, the driver, the units, the settings and the nginx site: Java, as root.
java -Duser.timezone=UTC -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent prepare-host --jars="$JARS" --driver-jar="$DRIVER_JAR" --java="$JAVA" "$@"

# Secrets: typed with hidden input or generated, as the service user; an existing one is kept. The tokens are shown once.
# The tokens of 1.x come first, so that they are kept and not generated (the file is root's: root opens it, Java reads it).
[ -z "$FROM_ENV" ] || as_svc secret-import-1x --stdin < "$FROM_ENV"
as_svc secret-set ledger.jdbc.password --if-absent $STDIN
as_svc token-generate ledger.admin.token --print
# The admin web must know the agents' token to refuse it: the same token under a second name.
as_svc token-generate ledger.mcp.token --print --copy-to=ledger.admin.agent-token

# The driver is registered after the secrets (a registered driver makes the commands connect).
as_svc driver-register --jar="$ETC/drivers/${DRIVER_JAR##*/}" --class="$DRIVER_CLASS"

# Schema and grants through the DDL account; its password is typed and not stored.
as_svc migrate --owner-account="$OWNER" --app-account="$APP_ACCOUNT" $STDIN

# systemctl: the service manager of the host, Java does not talk to it.
systemctl daemon-reload
systemctl enable "$SVC" "$ADMIN"
systemctl restart "$SVC" "$ADMIN"
# nginx: the check and the reload of a running server are its own commands.
if [ -n "$NGINX" ]; then
  nginx -t
  nginx -s reload
fi
echo "install.sh: done. Check the MCP server with: java -jar $LIB/ledger-cli.jar mcp-check --url=<its public URL>"

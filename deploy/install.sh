#!/bin/sh
# deploy/install.sh: the installer of pg-ledger-mcp 2.x for a Linux host (a Linux server). It is thin on purpose: it does only
# the operating system steps that Java should not do (check java, create the service user, set owners and modes, copy the
# jars, enable the units, reload nginx). Everything else is `java -jar ledger-cli.jar <command>`: render-deploy (units,
# settings, nginx site), secret-set, token-generate, driver-register and migrate (schema and ANSI GRANTs).
# Documentation it follows: Spring Boot, "Installing Spring Boot Applications" (a systemd unit in /etc/systemd/system,
# `systemctl enable`): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; systemd.exec(5) (User=, the
# sandboxing options of the rendered units); nginx proxy_module `proxy_set_header` (the rendered site).
# One service user (named after the instance, default pg-ledger-mcp, never root) runs the MCP server and the admin web: the
# secrets are readable by it alone, so the accounts the agents run as cannot read them. --name=<instance> gives an instance of
# its own (services <name> and <name>-admin, /opt/<name>, /etc/<name>), so two can live on one host with their own ports; the
# name is validated by Java (render-deploy --check) before anything is created. Two database accounts: the application account (both servers and the
# owner commands) and the DDL account (migrate and the grants; its password is typed and not stored).
# Run as root: sudo deploy/install.sh --jars=<dir> --driver-jar=<file> --driver-class=<class> --owner-account=<account>
#   --app-account=<account> --jdbc-url=<url> [the other options of render-deploy]
# Both accounts are created beforehand with the DBMS's own tools. It installs no package and no database. A re-run is an
# upgrade: jars, driver jar and files are replaced, the schema and the grants are applied again, the services are restarted;
# secrets, tokens and data are never replaced.
set -eu
umask 022

NAME=pg-ledger-mcp
JARS=
DRIVER_JAR=
DRIVER_CLASS=
OWNER=
APP_ACCOUNT=
NGINX=

die() { echo "install.sh: $*" >&2; exit 1; }
cli() { java -Duser.timezone=UTC -jar "$LIB/ledger-cli.jar" "$@"; }
as_svc() { runuser -u "$SVC" -- java -Duser.timezone=UTC -jar "$LIB/ledger-cli.jar" --ledger.secrets.instance="$NAME" "$@"; }

# The options read here are taken out; the rest (a JDBC URL, ports, host names, TLS paths) goes on to render-deploy.
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
    *) set -- "$@" "$arg" ;;
  esac
done
SVC=$NAME
ADMIN=$NAME-admin
LIB=/opt/$NAME
ETC=/etc/$NAME
[ "$(id -u)" = 0 ] || die "run as root (sudo)"
for v in "$JARS" "$DRIVER_JAR" "$DRIVER_CLASS" "$OWNER" "$APP_ACCOUNT"; do [ -n "$v" ] || die "an option is missing: see the header of this file"; done
JAVA=$(command -v java) || die "java is not installed"
case $(java -version 2>&1) in
  *\"25.* | *\"25\"*) ;;
  *) die "Java 25 is required" ;;
esac
# Every option, the name included, is validated by Java before anything is created.
java -Duser.timezone=UTC -jar "$JARS/ledger-cli.jar" --ledger.secrets.home=/nonexistent render-deploy --check --java="$JAVA" "$@"

# User, directories, jars.
id -u "$SVC" >/dev/null 2>&1 || useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$SVC"
install -d -m 0755 -o root -g root "$LIB"
for j in ledger-cli ledger-mcp-server ledger-admin-web; do install -m 0644 -o root -g root "$JARS/$j.jar" "$LIB/$j.jar"; done
install -d -m 0750 -o "$SVC" -g "$SVC" "$ETC"
install -d -m 0755 -o root -g root "$ETC/drivers"
install -d -m 0700 -o "$SVC" -g "$SVC" "$ETC/secrets"
install -m 0644 -o root -g root "$DRIVER_JAR" "$ETC/drivers/"

# The units and the settings (root writes them; no instance, so root reads no registration and no secret).
cli --ledger.secrets.home=/nonexistent render-deploy --java="$JAVA" "$@"

# Secrets: typed with hidden input or generated, as the service user; an existing one is kept. The tokens are shown once.
[ -e "$ETC/secrets/ledger.jdbc.password" ] || as_svc secret-set ledger.jdbc.password
as_svc token-generate ledger.admin.token --print
as_svc token-generate ledger.mcp.token --print
# The admin web must know the agents' token to refuse it: a copy of the MCP token in the same secret directory.
[ -e "$ETC/secrets/ledger.admin.agent-token" ] || install -m 0600 -o "$SVC" -g "$SVC" "$ETC/secrets/ledger.mcp.token" "$ETC/secrets/ledger.admin.agent-token"

# The driver is registered after the secrets (a registered driver makes the commands connect).
as_svc driver-register --jar="$ETC/drivers/${DRIVER_JAR##*/}" --class="$DRIVER_CLASS"

# Schema and grants through the DDL account; its password is typed and not stored.
as_svc migrate --owner-account="$OWNER" --app-account="$APP_ACCOUNT"

systemctl daemon-reload
systemctl enable "$SVC" "$ADMIN"
systemctl restart "$SVC" "$ADMIN"
if [ -n "$NGINX" ]; then
  nginx -t
  nginx -s reload
fi
echo "install.sh: done. Check the MCP server with: java -jar $LIB/ledger-cli.jar mcp-check --url=<its public URL>"

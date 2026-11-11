#!/bin/sh
# deploy/install.sh: the installer of pg-ledger-mcp 2.x. It is the one shell script of the product, and it is thin on purpose:
# it does only the operating system steps that Java should not do (check java, create the service users, set owners and modes,
# copy the jars, enable the units, reload nginx). Everything else is `java -jar ledger-cli.jar <command>`: render-deploy
# (units, settings, nginx site), secret-set, token-generate, driver-register and migrate (schema and ANSI GRANTs).
# Documentation it follows: Spring Boot, "Installing Spring Boot Applications" (a systemd unit in /etc/systemd/system,
# `systemctl enable`): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; systemd.exec(5) (User=, the
# sandboxing options of the rendered units); nginx proxy_module `proxy_set_header` (the rendered site).
# Run as root: sudo deploy/install.sh --jars=<dir> --driver-jar=<file> --driver-class=<class> --owner-account=<account>
#   --mcp-account=<account> --admin-account=<account> --jdbc-url=<url> [the other options of render-deploy]
# Needs a database whose accounts (the owner, the MCP server's and the admin web's) were created beforehand with the DBMS's
# own tools. It installs no package and no database. A re-run is an upgrade: jars, driver jar and files are replaced, the
# schema and the grants are applied again, the services are restarted; secrets, tokens and data are never replaced.
set -eu
umask 022

MCP=pg-ledger-mcp
ADMIN=pg-ledger-admin
LIB=/opt/pg-ledger-mcp
JARS=
DRIVER_JAR=
DRIVER_CLASS=
OWNER=
MCP_ACCOUNT=
ADMIN_ACCOUNT=
NGINX=

die() { echo "install.sh: $*" >&2; exit 1; }
run_as() { user=$1; shift; runuser -u "$user" -- java -Duser.timezone=UTC -jar "$LIB/ledger-cli.jar" --ledger.secrets.instance="$user" "$@"; }

# The options read here are taken out; the rest (a JDBC URL, ports, host names, TLS paths) goes on to render-deploy.
for arg in "$@"; do
  shift
  case $arg in
    --jars=*) JARS=${arg#*=} ;;
    --driver-jar=*) DRIVER_JAR=${arg#*=} ;;
    --driver-class=*) DRIVER_CLASS=${arg#*=} ;;
    --owner-account=*) OWNER=${arg#*=} ;;
    --mcp-account=*) MCP_ACCOUNT=${arg#*=}; set -- "$@" "$arg" ;;
    --admin-account=*) ADMIN_ACCOUNT=${arg#*=}; set -- "$@" "$arg" ;;
    --nginx-conf=*) NGINX=1; set -- "$@" "$arg" ;;
    *) set -- "$@" "$arg" ;;
  esac
done
[ "$(id -u)" = 0 ] || die "run as root (sudo)"
[ -n "$JARS$DRIVER_JAR$DRIVER_CLASS$OWNER$MCP_ACCOUNT$ADMIN_ACCOUNT" ] || die "options are missing: see the header of this file"
for v in "$JARS" "$DRIVER_JAR" "$DRIVER_CLASS" "$OWNER" "$MCP_ACCOUNT" "$ADMIN_ACCOUNT"; do [ -n "$v" ] || die "an option is missing"; done
JAVA=$(command -v java) || die "java is not installed"
case $(java -version 2>&1) in
  *'version "25.'* | *'version "25"'*) ;;
  *) die "Java 25 is required" ;;
esac

# Users, directories, jars. The MCP server and the admin web are two users with two secret directories, so the MCP server
# cannot read the admin password or the admin tokens.
install -d -m 0755 -o root -g root "$LIB"
for j in ledger-cli ledger-mcp-server ledger-admin-web; do install -m 0644 -o root -g root "$JARS/$j.jar" "$LIB/$j.jar"; done
for u in "$MCP" "$ADMIN"; do
  id -u "$u" >/dev/null 2>&1 || useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$u"
  install -d -m 0750 -o "$u" -g "$u" "/etc/$u"
  install -d -m 0755 -o root -g root "/etc/$u/drivers"
  install -d -m 0700 -o "$u" -g "$u" "/etc/$u/secrets"
  install -m 0644 -o root -g root "$DRIVER_JAR" "/etc/$u/drivers/"
done

# The units, the settings and the nginx site (root writes them; no instance, so root reads no registration and no secret).
java -Duser.timezone=UTC -jar "$LIB/ledger-cli.jar" --ledger.secrets.instance=none render-deploy --java="$JAVA" "$@"

# Secrets: typed with hidden input or generated, as each service's own user; an existing one is kept. The tokens are shown once.
for u in "$MCP" "$ADMIN"; do
  [ -e "/etc/$u/secrets/ledger.jdbc.password" ] || run_as "$u" secret-set ledger.jdbc.password
done
run_as "$ADMIN" token-generate ledger.admin.token --print
run_as "$MCP" token-generate ledger.mcp.token --print
# The admin web must know the agents' token to refuse it: a copy of the MCP token in the admin's own directory.
[ -e "/etc/$ADMIN/secrets/ledger.admin.agent-token" ] || install -m 0600 -o "$ADMIN" -g "$ADMIN" "/etc/$MCP/secrets/ledger.mcp.token" "/etc/$ADMIN/secrets/ledger.admin.agent-token"

# The driver is registered after the secrets (a registered driver makes the commands connect). To replace it, delete the file.
for u in "$MCP" "$ADMIN"; do
  [ -e "/etc/$u/jdbc-driver.yaml" ] || run_as "$u" driver-register --jar="/etc/$u/drivers/${DRIVER_JAR##*/}" --class="$DRIVER_CLASS"
done

# Schema and grants through the owner account; its password is typed and not stored.
run_as "$ADMIN" migrate --owner-account="$OWNER" --mcp-account="$MCP_ACCOUNT" --admin-account="$ADMIN_ACCOUNT"

systemctl daemon-reload
systemctl enable "$MCP" "$ADMIN"
systemctl restart "$MCP" "$ADMIN"
if [ -n "$NGINX" ]; then
  nginx -t
  nginx -s reload
fi
echo "install.sh: done. Check the MCP server with: ledger-cli mcp-check --url=<its public URL>"

@echo off
rem deploy\install.cmd: the installer of pg-ledger-mcp 2.x for Windows (a personal PC or a Windows server). Thin on purpose,
rem like deploy/install.sh: it starts only what Java cannot do (the service manager: the WinSW executables and sc.exe) and
rem everything else is `java -jar ledger-cli.jar <command>`: render-deploy --check (every option, validated first),
rem prepare-host (folders, ACLs, the jars, WinSW and the driver copied, the WinSW configurations and the settings), secret-set,
rem token-generate, driver-register, setup (schema, ANSI GRANTs and the seed codes) and seal-host (the access of the service account and the
rem hand-over of the secrets). No PowerShell, no download, no package, no icacls, mkdir or copy.
rem Documentation it follows: Spring Boot, "Installing Spring Boot Applications", section "Microsoft Windows Services"
rem (WinSW): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; the LocalService account:
rem https://learn.microsoft.com/en-us/windows/win32/services/localservice-account ; sc config (obj=):
rem https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/sc-config
rem One service account runs both services: the predefined account LocalService (no person's account, no password). The
rem secret folder is handed over to it by seal-host and is then readable by it alone (the installing administrator keeps only
rem the right to see that a file exists), so the accounts the agents run as cannot read the ledger's secrets. Two database
rem accounts: the application account (both servers and the owner commands) and the DDL account (setup and the grants; its
rem password is typed and not stored). WinSW.exe (2.x) is obtained by the operator and passed here, like the JDBC driver.
rem Run in an elevated prompt (Java refuses to change the folders of the installation otherwise; there is no probe here):
rem   deploy\install.cmd <jars-dir> <winsw.exe> <driver-jar> <driver-class> <owner-account> <app-account> "<jdbc-url>" ["<name>" ["<more render-deploy options>"]]
rem The name of the instance (default pg-ledger-mcp) gives the services <name> and <name>-admin and the home C:\ProgramData\<name>,
rem so two instances can live on one host with their own ports; the Java validation of the name comes before any change.
rem A re-run is an upgrade: jars, driver jar and files are replaced and the services are recreated; secrets, tokens and
rem data are never replaced. The Java 25 check is the JVM's: the jars are compiled for it and no other runtime loads them.
rem A personal PC gives no host names (loopback only); a Windows server that is reached from outside gives them and puts a
rem reverse proxy of its own in front. Migration from 1.x: see the README (the 1.x service is stopped by the operator).
setlocal EnableExtensions
if "%~7"=="" echo install.cmd: arguments are missing: see the header of this file& exit /b 1
for %%I in (java.exe) do set "JAVA=%%~$PATH:I"
if not defined JAVA echo install.cmd: java is not installed& exit /b 1
set "JAVA=%JAVA:\=/%"
rem The default name and the default MCP port are install defaults of the seed bundle, the one place the defaults are written: Java tells them.
set "NAME=%~8"
if "%NAME%"=="" for /f "usebackq delims=" %%N in (`""%JAVA%" -Duser.timezone=UTC -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent install-default instance-name"`) do set "NAME=%%N"
for /f "usebackq delims=" %%P in (`""%JAVA%" -Duser.timezone=UTC -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent install-default mcp-port"`) do set "MCPPORT=%%P"
set "SVC=%NAME%"
set "ADMIN=%NAME%-admin"
set "ACCOUNT=NT AUTHORITY\LocalService"
set "WHOME=C:\ProgramData\%NAME%"
set "HOME=C:/ProgramData/%NAME%"
set RENDER=--platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%~7" "--app-account=%~6" %~9
set JARCMD="%JAVA%" -Duser.timezone=UTC -jar "%WHOME%\lib\ledger-cli.jar"
set CLI=%JARCMD% --ledger.secrets.home=%HOME%/etc
rem The hint at the end names the port of the MCP server: the value after --mcp-port in the render options (cmd splits it at the
rem equals sign), the install default (above) when there is none. Java validates the option itself before anything is changed.
for %%P in (%~9) do if defined TAKE (set "MCPPORT=%%~P" & set "TAKE=") else if /i "%%~P"=="--mcp-port" set "TAKE=1"
rem Every option, the name included, is validated by Java before anything is created (the name is only ever used in quotes until then).
"%JAVA%" -Duser.timezone=UTC -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent render-deploy --check %RENDER% || exit /b 1

rem WinSW: stop what runs (the service manager is not Java's to talk to; the jars of a running service are locked).
if exist "%WHOME%\lib\%SVC%.exe" "%WHOME%\lib\%SVC%.exe" stop /stopwait >nul 2>&1
if exist "%WHOME%\lib\%ADMIN%.exe" "%WHOME%\lib\%ADMIN%.exe" stop /stopwait >nul 2>&1
rem Folders and their ACLs, the secrets of an earlier run given back for this run, the jars, WinSW and the driver copied, the
rem WinSW configurations and the settings rendered: Java (no secret is read: the home of this call has none).
"%JAVA%" -Duser.timezone=UTC -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent prepare-host "--jars=%~1" "--winsw=%~2" "--driver-jar=%~3" %RENDER% || exit /b 1

rem Secrets: typed with hidden input or generated, as the administrator; an existing one is kept. The tokens are shown once.
%CLI% secret-set ledger.jdbc.password --if-absent || exit /b 1
%CLI% token-generate ledger.admin.token --print || exit /b 1
rem The admin web must know the agents' token to refuse it: the same token under a second name.
%CLI% token-generate ledger.mcp.token --print "--copy-to=ledger.admin.agent-token" || exit /b 1

rem The driver is registered after the secrets (a registered driver makes the commands connect).
%CLI% driver-register "--jar=%HOME%/etc/drivers/%~nx3" "--class=%~4" || exit /b 1

rem The first setup through the DDL account (schema, grants, the seed codes); its password is typed and not stored.
%CLI% setup "--owner-account=%~5" "--app-account=%~6" || exit /b 1

rem WinSW registers the services from the configurations, sc.exe sets the one account (the service manager again).
for %%S in (%SVC% %ADMIN%) do (
  "%WHOME%\lib\%%S.exe" uninstall >nul 2>&1
  "%WHOME%\lib\%%S.exe" install || exit /b 1
  sc.exe config %%S obj= "%ACCOUNT%" >nul || exit /b 1
)

rem The service account gets its access and the secrets are handed over to it (Java; the installer keeps no access to them).
%JARCMD% --ledger.secrets.home=C:/nonexistent seal-host %RENDER% || exit /b 1

for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" start || exit /b 1
echo install.cmd: done. Check the MCP server with: %CLI% mcp-check --url=http://127.0.0.1:%MCPPORT%/mcp

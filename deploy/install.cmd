@echo off
rem deploy\install.cmd: the installer of pg-ledger-mcp 2.x for Windows (a personal PC or a Windows server). Thin on purpose,
rem like deploy/install.sh: only the operating system steps (folders, ACLs, copying, registering the services). Everything
rem else is `java -jar ledger-cli.jar <command>`: render-deploy --platform=windows (WinSW configurations, settings),
rem secret-set, token-generate, driver-register, migrate (schema and ANSI GRANTs). No PowerShell, no download, no package.
rem Documentation it follows: Spring Boot, "Installing Spring Boot Applications", section "Microsoft Windows Services"
rem (WinSW): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; the LocalService account:
rem https://learn.microsoft.com/en-us/windows/win32/services/localservice-account ; icacls and sc config (obj=):
rem https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls
rem https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/sc-config
rem One service account runs both services: the predefined account LocalService (no person's account, no password). The
rem secret folder is handed over to it and is then readable by it alone (the installing administrator keeps only the right
rem to see that a file exists), so the accounts the agents run as cannot read the ledger's secrets. Two database accounts:
rem the application account (both servers and the owner commands) and the DDL account (migrate and the grants; its password
rem is typed and not stored). WinSW.exe (2.x) is obtained by the operator and passed here, like the JDBC driver.
rem Run in an elevated prompt:
rem   deploy\install.cmd <jars-dir> <winsw.exe> <driver-jar> <driver-class> <owner-account> <app-account> "<jdbc-url>" ["<name>" ["<more render-deploy options>"]]
rem The name of the instance (default pg-ledger-mcp) gives the services <name> and <name>-admin and the home C:\ProgramData\<name>,
rem so two instances can live on one host with their own ports; the Java validation of the name comes before any change.
rem A re-run is an upgrade: jars, driver jar and files are replaced and the services are recreated; secrets, tokens and
rem data are never replaced. A personal PC gives no host names (loopback only); a Windows server that is reached from
rem outside gives them and puts a reverse proxy of its own in front.
setlocal EnableExtensions
set "NAME=%~8"
if "%NAME%"=="" set "NAME=pg-ledger-mcp"
set "SVC=%NAME%"
set "ADMIN=%NAME%-admin"
set "ACCOUNT=NT AUTHORITY\LocalService"
set "WHOME=C:\ProgramData\%NAME%"
set "HOME=C:/ProgramData/%NAME%"
set "SECRETS=%WHOME%\etc\secrets"
if "%~7"=="" echo install.cmd: arguments are missing: see the header of this file& exit /b 1
icacls "%SystemRoot%\System32\config" >nul 2>&1 || (echo install.cmd: run in an elevated prompt& exit /b 1)
for %%I in (java.exe) do set "JAVA=%%~$PATH:I"
if not defined JAVA echo install.cmd: java is not installed& exit /b 1
for /f "tokens=3" %%V in ('java -version 2^>^&1') do if not defined JV set "JV=%%~V"
if not "%JV:~0,3%"=="25." if not "%JV%"=="25" echo install.cmd: Java 25 is required& exit /b 1
set "JAVA=%JAVA:\=/%"
rem Every option, the name included, is validated by Java before anything is created (the name is only ever used in quotes until then).
"%JAVA%" -Duser.timezone=UTC -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent render-deploy --check --platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%~7" "--app-account=%~6" %~9 || exit /b 1
set JARCMD="%JAVA%" -Duser.timezone=UTC -jar "%WHOME%\lib\ledger-cli.jar"
set CLI=%JARCMD% --ledger.secrets.home=%HOME%/etc

rem Stop what runs (its jars are locked). The folder is closed to every account but SYSTEM and Administrators; the service
rem account gets only what it needs, after the schema and the secrets are in place (the drivers folder must not be writable
rem by it while the administrator registers the driver).
if exist "%WHOME%\lib\%SVC%.exe" "%WHOME%\lib\%SVC%.exe" stop /stopwait >nul 2>&1
if exist "%WHOME%\lib\%ADMIN%.exe" "%WHOME%\lib\%ADMIN%.exe" stop /stopwait >nul 2>&1
mkdir "%WHOME%\lib" "%WHOME%\etc\drivers" "%SECRETS%" "%WHOME%\logs" >nul 2>&1
icacls "%WHOME%" /inheritance:r /remove:g "%ACCOUNT%" /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" >nul || exit /b 1
rem After an earlier run the secrets belong to the service account: take them back for this run (the folder first, then its
rem files), so that the commands below can read them as the administrator; they are handed over again at the end.
for %%T in ("%SECRETS%" "%SECRETS%\*") do (
  icacls %%T /setowner "%USERDOMAIN%\%USERNAME%" /C >nul 2>&1
  icacls %%T /grant:r "%USERDOMAIN%\%USERNAME%:(OI)(CI)F" /C >nul 2>&1
  icacls %%T /remove:g "%ACCOUNT%" /C >nul 2>&1
)
for %%J in (ledger-cli ledger-mcp-server ledger-admin-web) do copy /y "%~1\%%J.jar" "%WHOME%\lib\" >nul || exit /b 1
copy /y "%~2" "%WHOME%\lib\%SVC%.exe" >nul || exit /b 1
copy /y "%~2" "%WHOME%\lib\%ADMIN%.exe" >nul || exit /b 1
copy /y "%~3" "%WHOME%\etc\drivers\" >nul || exit /b 1

rem The WinSW configurations and the settings (no secret is read: the home of this call has none).
%JARCMD% --ledger.secrets.home=C:/nonexistent render-deploy --platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%~7" "--app-account=%~6" %~9 || exit /b 1

rem Secrets: typed with hidden input or generated, as the administrator; an existing one is kept. The tokens are shown once.
if not exist "%SECRETS%\ledger.jdbc.password" %CLI% secret-set ledger.jdbc.password || exit /b 1
%CLI% token-generate ledger.admin.token --print || exit /b 1
%CLI% token-generate ledger.mcp.token --print || exit /b 1
rem The admin web must know the agents' token to refuse it: a copy of the MCP token in the same secret folder.
if not exist "%SECRETS%\ledger.admin.agent-token" copy "%SECRETS%\ledger.mcp.token" "%SECRETS%\ledger.admin.agent-token" >nul

rem The driver is registered after the secrets (a registered driver makes the commands connect).
%CLI% driver-register "--jar=%HOME%/etc/drivers/%~nx3" "--class=%~4" || exit /b 1

rem Schema and grants through the DDL account; its password is typed and not stored.
%CLI% migrate "--owner-account=%~5" "--app-account=%~6" || exit /b 1

rem The services: WinSW registers them from the configurations, sc.exe sets the one account, WinSW starts them.
for %%S in (%SVC% %ADMIN%) do (
  "%WHOME%\lib\%%S.exe" uninstall >nul 2>&1
  "%WHOME%\lib\%%S.exe" install || exit /b 1
  sc.exe config %%S obj= "%ACCOUNT%" >nul || exit /b 1
)

rem The service account: read and run the jars, change the settings folder (the driver page of the admin web writes the
rem registration there) and write its logs. The secrets are handed over (the files first, then the folder): owner and full
rem access for the service account only, the right to see that a file exists for Administrators, nothing for the installer.
icacls "%WHOME%\lib" /grant "%ACCOUNT%:(OI)(CI)RX" >nul || exit /b 1
icacls "%WHOME%\etc" /grant "%ACCOUNT%:(OI)(CI)M" >nul || exit /b 1
icacls "%WHOME%\logs" /grant "%ACCOUNT%:(OI)(CI)M" >nul || exit /b 1
for %%T in ("%SECRETS%\*" "%SECRETS%") do (
  icacls %%T /setowner "%ACCOUNT%" /C >nul || exit /b 1
  icacls %%T /grant:r "%ACCOUNT%:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)(RA,RC,S)" /C >nul || exit /b 1
  icacls %%T /inheritance:r /remove:g "%USERDOMAIN%\%USERNAME%" /C >nul || exit /b 1
)

for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" start || exit /b 1
echo install.cmd: done. Check the MCP server with: %CLI% mcp-check --url=http://127.0.0.1:8765/mcp

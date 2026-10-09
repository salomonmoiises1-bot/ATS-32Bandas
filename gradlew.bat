@echo off
setlocal
set "APP_HOME=%~dp0"
set "PROPS=%APP_HOME%gradle\wrapper\gradle-wrapper.properties"
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "$line=Get-Content -LiteralPath '%PROPS%' | Where-Object { $_ -match '^distributionUrl=' } | Select-Object -First 1; if ($line) { ($line -replace '^distributionUrl=', '') -replace '\\:', ':' }"`) do set "DIST_URL=%%A"
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "if ('%DIST_URL%' -match 'gradle-([0-9][0-9.]*)-bin\.zip') { $Matches[1] }"`) do set "GRADLE_VERSION=%%A"
if not defined GRADLE_VERSION (
  echo Cannot determine Gradle version from %PROPS% 1>&2
  exit /b 1
)
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "DIST_HOME=%GRADLE_USER_HOME%\manual-wrapper\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%DIST_HOME%\bin\gradle.bat"
set "DOWNLOAD_DIR=%GRADLE_USER_HOME%\manual-wrapper\downloads"
set "ZIP=%DOWNLOAD_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
if not exist "%GRADLE_BIN%" (
  if not exist "%DOWNLOAD_DIR%" mkdir "%DOWNLOAD_DIR%"
  if not exist "%ZIP%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP%'"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%GRADLE_USER_HOME%\manual-wrapper' -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" -p "%APP_HOME%" %*
exit /b %ERRORLEVEL%

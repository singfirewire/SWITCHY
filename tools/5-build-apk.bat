@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\android"

if "%JAVA_HOME%"=="" (
  if exist "%USERPROFILE%\tools\jdk21\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\tools\jdk21"
    echo ใช้ JDK: %JAVA_HOME%
  ) else (
    echo [x] ไม่พบ JAVA_HOME และไม่พบ JDK ที่ %USERPROFILE%\tools\jdk21
    echo     ติดตั้ง JDK 17+ แล้วตั้งค่า JAVA_HOME ก่อน
    pause
    exit /b 1
  )
)

echo ============================================
echo   บิลด์ APK (debug)
echo ============================================
echo.

call gradlew.bat assembleDebug %*
if errorlevel 1 (
  echo.
  echo [x] บิลด์ไม่สำเร็จ - ดูข้อความด้านบน
  pause
  exit /b 1
)

echo.
echo [ok] APK อยู่ที่:
echo      android\app\build\outputs\apk\debug\app-debug.apk
echo.
echo ติดตั้งลงมือถือที่ต่อ USB:  adb install -r app\build\outputs\apk\debug\app-debug.apk
echo หรือรัน tools\6-install-apk.bat
pause

@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

set ADB=%USERPROFILE%\tools\android-sdk\platform-tools\adb.exe
if not exist "%ADB%" (
  where adb >nul 2>nul
  if errorlevel 1 (
    echo [x] ไม่พบ adb - ติดตั้ง Android platform-tools ก่อน
    pause
    exit /b 1
  )
  set ADB=adb
)

set APK=android\app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
  echo ยังไม่มี APK - กำลังบิลด์...
  call tools\5-build-apk.bat || exit /b 1
)

echo ============================================
echo   ติดตั้ง APK ลงมือถือ ^(ต้องเปิด USB debugging^)
echo ============================================
"%ADB%" devices
echo.
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo.
  echo [x] ติดตั้งไม่สำเร็จ - เช็คว่ามือถือต่อ USB และอนุญาต debugging แล้ว
) else (
  echo.
  echo [ok] ติดตั้งแล้ว - เปิดแอป SWITCHY บนมือถือได้เลย
)
pause

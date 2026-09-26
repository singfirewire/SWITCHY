@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0..\server"

if not exist dist\switchy-server.exe (
  echo ยังไม่มีไฟล์บิลด์ - กำลังบิลด์ให้ก่อน...
  call "%~dp01-build-server.bat" || exit /b 1
)

rem ใช้ config.json ใน server\ ถ้าไม่มีให้คัดลอกจากตัวอย่าง
if not exist config.json if exist run\config.json copy /y run\config.json config.json >nul

echo ============================================
echo   SWITCHY Server - กำลังเริ่มทำงาน
echo   ^(ปิดหน้าต่างนี้ = ปิดเซิร์ฟเวอร์^)
echo ============================================
echo.

dist\switchy-server.exe -config config.json %*

echo.
echo เซิร์ฟเวอร์หยุดทำงานแล้ว
pause

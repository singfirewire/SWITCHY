@echo off
chcp 65001 >nul
cd /d "%~dp0..\server"

echo ============================================
echo   vMix จำลอง (TCP 8099) - สำหรับทดสอบ
echo   สลับ program/preview อัตโนมัติทุก 3 วินาที
echo   ^(ปิดหน้าต่างนี้ = หยุด^)
echo ============================================
echo.

where python >nul 2>nul
if errorlevel 1 (
  echo [x] ไม่พบ python ใน PATH
  echo     ติดตั้ง Python หรือรัน: py scripts\fake-vmix.py
  pause
  exit /b 1
)

python scripts\fake-vmix.py --inputs 8 --cycle 3 %*
pause

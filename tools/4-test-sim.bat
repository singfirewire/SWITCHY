@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\server"

set SRV=%1
if "%SRV%"=="" set /p SRV=ใส่ IP ของ Mini PC (เช่น 192.168.1.50) แล้วกด Enter: 
if "%SRV%"=="" set SRV=127.0.0.1

if not exist dist\switchy-sim.exe (
  echo ยังไม่มี switchy-sim - กำลังบิลด์...
  call "%~dp01-build-server.bat" || exit /b 1
)

echo.
echo ============================================
echo   ทดสอบกับ %SRV% : มือถือจำลอง 3 เครื่อง, 20 วินาที
echo   - ต้องเปิดเซิร์ฟเวอร์ ^(2-run-server.bat^) ไว้ก่อน
echo   - สลับกันกด PTT เพื่อทดสอบเสียง + การกันเสียง
echo ============================================
echo.

dist\switchy-sim.exe -ws %SRV%:8090 -clients 3 -seconds 20
pause

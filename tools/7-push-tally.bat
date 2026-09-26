@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

set SRV=%1
if "%SRV%"=="" set /p SRV=ใส่ IP ของ Mini PC: 
if "%SRV%"=="" set SRV=127.0.0.1

set CAM=%2
if "%CAM%"=="" set /p CAM=รหัสกล้อง (cam1/cam2/cam3): 
if "%CAM%"=="" set CAM=cam1

set ST=%3
if "%ST%"=="" set /p ST=สถานะ (program/preview/safe): 
if "%ST%"=="" set ST=program

echo.
echo ยิงสถานะ Tally: %CAM% = %ST%  ไปที่ %SRV%:8090
echo.

curl -s -X POST "http://%SRV%:8090/api/tally" ^
     -H "Content-Type: application/json" ^
     -d "{\"camera\":\"%CAM%\",\"state\":\"%ST%\"}"
echo.
echo.
echo ตัวอย่างอื่น:
echo   ตั้งหลายกล้องพร้อมกัน:
echo   curl -X POST http://%SRV%:8090/api/tally -H "Content-Type: application/json" -d "{\"states\":{\"cam1\":\"program\",\"cam2\":\"preview\"}}"
echo.
echo   ดูสถานะปัจจุบัน:
echo   curl http://%SRV%:8090/api/state
echo.
pause

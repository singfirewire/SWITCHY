@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

set SRV=%1
if "%SRV%"=="" set /p SRV=ใส่ IP ของ Mini PC: 
if "%SRV%"=="" set SRV=127.0.0.1

set FROM=%2
if "%FROM%"=="" set FROM=โปรดิวเซอร์

echo.
echo ============================================
echo   ส่งข้อความจากศูนย์ควบคุม -^> เครื่องลูกทุกเครื่อง
echo   (เครื่องลูกอ่านได้อย่างเดียว ตอบกลับได้แค่ปุ่มสำเร็จรูป)
echo ============================================
echo.

set /p TEXT=พิมพ์ข้อความ: 
if "%TEXT%"=="" (
  echo ยกเลิก: ไม่ได้พิมพ์ข้อความ
  pause
  exit /b 1
)

curl -s -X POST "http://%SRV%:8090/api/message" ^
     -H "Content-Type: application/json" ^
     -d "{\"text\":\"%TEXT%\",\"from\":\"%FROM%\",\"kind\":\"info\"}"
echo.
echo.
echo ดูข้อความ + การตอบกลับทั้งหมด:
echo   curl http://%SRV%:8090/api/messages
echo.
echo ส่งแบบด่วน (ขึ้นเตือนบนมือถือตัวหนา):
echo   curl -X POST http://%SRV%:8090/api/message -H "Content-Type: application/json" -d "{\"text\":\"...\",\"kind\":\"alert\"}"
echo.
echo หรือใช้หน้า dashboard (มีช่องพิมพ์ + ดูการตอบกลับ): http://%SRV%:8090/
echo.
pause

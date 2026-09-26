@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\server"

echo ============================================
echo   SWITCHY - บิลด์เซิร์ฟเวอร์ (Go)
echo ============================================
where go >nul 2>nul
if errorlevel 1 (
  echo [x] ไม่พบ Go ใน PATH - ติดตั้ง Go ก่อน ^(https://go.dev/dl/^)
  pause
  exit /b 1
)

echo [1/3] ดาวน์โหลด dependency...
go mod tidy || goto :fail

echo [2/3] บิลด์ตัวเซิร์ฟเวอร์...
go build -o dist\switchy-server.exe .\cmd\switchy-server || goto :fail

echo [3/3] บิลด์มือถือจำลอง (switchy-sim)...
go build -o dist\switchy-sim.exe .\cmd\switchy-sim || goto :fail

echo.
echo [ok] ได้ไฟล์:
echo      server\dist\switchy-server.exe
echo      server\dist\switchy-sim.exe
echo.
pause
exit /b 0

:fail
echo.
echo [x] บิลด์ไม่สำเร็จ - ดูข้อความด้านบน
pause
exit /b 1

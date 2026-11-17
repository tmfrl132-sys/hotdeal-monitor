@echo off
chcp 65001 > nul
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo [준비] 처음 실행이라 필요한 프로그램을 설치합니다...
  python -m venv .venv
  .venv\Scripts\python -m pip install -q -r requirements.txt
)
echo 핫딜 모니터를 시작합니다. 끄려면 이 창을 닫거나 Ctrl+C 를 누르세요.
.venv\Scripts\python -m monitor
pause

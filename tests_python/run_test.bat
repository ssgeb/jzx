@echo off
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
conda run --no-capture-output -n leetcode python "%~dp0test_agent.py"
exit /b %ERRORLEVEL%

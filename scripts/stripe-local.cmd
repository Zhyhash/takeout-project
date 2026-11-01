@echo off
setlocal

if /I "%~1"=="--help" goto help
where stripe >nul 2>nul
if errorlevel 1 goto missing
if /I "%~1"=="--check" goto version_once

:menu
echo.
echo Stripe takeout SANDBOX - local webhook helper
echo [1] Check Stripe CLI version
echo [2] Sign in through the browser - authorize takeout sandbox only
echo [3] Select the takeout sandbox
echo [4] Forward test webhooks to Spring Boot on port 8080
echo [Q] Quit
choice /C 1234Q /N /M "Choose 1, 2, 3, 4 or Q: "
if errorlevel 5 exit /b 0
if errorlevel 4 goto listen
if errorlevel 3 goto select_sandbox
if errorlevel 2 goto login
if errorlevel 1 goto version
goto menu

:version
call stripe --version
pause
goto menu

:version_once
call stripe --version
exit /b %errorlevel%

:login
echo Complete browser authorization for takeout SANDBOX only.
echo Do not authorize live mode or paste API keys into this helper.
call stripe login
pause
goto menu

:select_sandbox
echo Select takeout sandbox from the authorized environments.
call stripe switch --live=false
pause
goto menu

:listen
echo Keep this window open during the payment test.
echo Copy the signing secret displayed by Stripe CLI into IDEA only.
echo Do not send that secret to chat or save this terminal output to a log.
echo Close this window to stop forwarding.
call stripe listen --live=false --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to http://127.0.0.1:8080/webhooks/stripe
pause
goto menu

:missing
echo Stripe CLI was not found in this terminal's PATH.
echo Install it manually: winget install Stripe.StripeCLI
echo Official instructions: https://docs.stripe.com/cli/install
echo Then open a new terminal and run this helper again.
if /I "%~1"=="--check" exit /b 2
pause
exit /b 2

:help
echo Double-click stripe-local.cmd for the interactive menu.
echo stripe-local.cmd --check checks CLI availability and version only.
echo This helper never accepts or writes API keys or signing secrets.
exit /b 0

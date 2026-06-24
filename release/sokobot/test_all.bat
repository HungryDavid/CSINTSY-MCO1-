@echo off
echo Cleaning old class files...
del /s /q src\main\*.class src\solver\*.class src\gui\*.class src\reader\*.class >nul 2>&1

echo Compiling automated test suite...
javac src/main/TestAll.java -cp src
if %errorlevel% neq 0 (
    echo.
    echo Error: Compilation failed.
    pause
    exit /b %errorlevel%
)

echo.
java -classpath src main.TestAll
echo.
pause

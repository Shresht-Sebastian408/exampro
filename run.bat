@echo off
rem Launches ExamPro from the shaded jar. Run "mvnw.cmd package" first if the jar is missing.
rem Add the demo exam once with:  run.bat demo
cd /d "%~dp0"
if not exist target\exampro-1.0.0-shaded.jar (
    echo target\exampro-1.0.0-shaded.jar not found. Run: mvnw.cmd package
    exit /b 1
)
if /i "%1"=="demo" (
    java -cp target\exampro-1.0.0-shaded.jar com.exampro.config.schema.DemoSeeder
    exit /b %errorlevel%
)
java -jar target\exampro-1.0.0-shaded.jar

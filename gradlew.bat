@rem
@rem Copyright 2015 the original author or authors.
@rem Gradle startup script for Windows (NiftySellerFootprint)
@rem
@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%

set JAVA_EXE=java.exe
if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
)

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if exist "%CLASSPATH%" (
    "%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
) else (
    where gradle >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        gradle %*
    ) else (
        echo ERROR: gradle-wrapper.jar not found and 'gradle' is not in PATH.
        echo Open this project in Android Studio or run 'gradle wrapper --gradle-version 8.7'.
        exit /b 1
    )
)

if "%OS%"=="Windows_NT" endlocal

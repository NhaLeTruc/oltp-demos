@REM Maven Wrapper startup script for Windows

@echo off
setlocal

set MAVEN_PROJECTBASEDIR=%~dp0

set WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
set WRAPPER_PROPERTIES="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"

if exist %WRAPPER_JAR% (
    java -jar %WRAPPER_JAR% %*
) else (
    echo Error: Maven Wrapper JAR not found
    exit /b 1
)

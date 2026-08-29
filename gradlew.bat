@echo off
setlocal

set APP_HOME=%~dp0
set JAVA_EXE=java
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
    echo gradle-wrapper.jar is missing. Run 'gradle wrapper' in a Gradle environment once.
    exit /b 1
)

"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

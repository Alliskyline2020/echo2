#!/bin/sh
# Gradle wrapper — 首次运行自动下载 Gradle + AGP
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "首次运行：下载 Gradle Wrapper JAR..."
    curl -sSL "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$DIR/gradle/wrapper/gradle-wrapper.jar"
fi
exec java -cp "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"

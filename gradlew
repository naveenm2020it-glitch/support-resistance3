#!/bin/sh

##############################################################################
#
#   Gradle start up script for POSIX generated for NiftySellerFootprint
#   Bootstraps Gradle 8.7 distribution if gradle-wrapper.jar is not pre-cached
#
##############################################################################

APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
GRADLE_VERSION="8.7"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
    exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    if command -v gradle >/dev/null 2>&1; then
        exec gradle "$@"
    fi
    CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
    mkdir -p "$CACHE_ROOT"
    DIST_ZIP="$CACHE_ROOT/gradle-${GRADLE_VERSION}-bin.zip"
    if [ ! -d "$CACHE_ROOT/gradle-${GRADLE_VERSION}" ]; then
        echo "Downloading Gradle ${GRADLE_VERSION} distribution..."
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL "$DIST_URL" -o "$DIST_ZIP" || exit 1
        elif command -v wget >/dev/null 2>&1; then
            wget -q "$DIST_URL" -O "$DIST_ZIP" || exit 1
        else
            echo "ERROR: Neither gradle, gradle-wrapper.jar, curl, nor wget is available." >&2
            exit 1
        fi
        unzip -q -o "$DIST_ZIP" -d "$CACHE_ROOT" || exit 1
    fi
    exec "$CACHE_ROOT/gradle-${GRADLE_VERSION}/bin/gradle" "$@"
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"

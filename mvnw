#!/bin/sh
# Maven Wrapper startup script

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")"; pwd)}"
MAVEN_OPTS="${MAVEN_OPTS:-}"
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -r "$WRAPPER_PROPERTIES" ]; then
    DOWNLOAD_URL=$(cat "$WRAPPER_PROPERTIES" | grep "^wrapperUrl=" | cut -d'=' -f2-)
    MAVEN_DIST_URL=$(cat "$WRAPPER_PROPERTIES" | grep "^distributionUrl=" | cut -d'=' -f2-)
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper JAR..."
    mkdir -p "$(dirname "$WRAPPER_JAR")"
    if command -v wget > /dev/null; then
        wget -q "$DOWNLOAD_URL" -O "$WRAPPER_JAR"
    elif command -v curl > /dev/null; then
        curl -fsSL "$DOWNLOAD_URL" -o "$WRAPPER_JAR"
    else
        echo "Error: wget or curl required to download Maven Wrapper"
        exit 1
    fi
fi

exec java -jar "$WRAPPER_JAR" "$@"

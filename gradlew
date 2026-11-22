#!/usr/bin/env sh
set -eu

DIR=$(dirname "$0")
APP_HOME=$DIR

if [ -n "${JAVA_HOME:-}" ]; then
  case "$JAVA_HOME" in
    *\ *)
      echo "ERROR: JAVA_HOME is set to a path with spaces: $JAVA_HOME" >&2
      exit 1
      ;;
  esac
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA_EXE="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVA_EXE" ]; then
    JAVA_EXE="$JAVA_HOME/jre/bin/java"
  fi
  if [ ! -x "$JAVA_EXE" ]; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    exit 1
  fi
else
  JAVA_EXE="java"
  if ! command -v "$JAVA_EXE" >/dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
    exit 1
  fi
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVA_EXE" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

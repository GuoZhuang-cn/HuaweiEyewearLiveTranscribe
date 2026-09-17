#!/bin/sh
# Gradle start up script for POSIX
app_path=$0
while [ -h "$app_path" ]; do
  ls=$( ls -ld "$app_path" )
  link=${ls#*' -> '}
  case $link in
    /*) app_path=$link ;;
    *) app_path=$( dirname "$app_path" )/$link ;;
  esac
done
APP_HOME=$( cd "${app_path%/*}" && pwd -P ) || exit
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ] ; then
  JAVACMD=$JAVA_HOME/bin/java
else
  JAVACMD=java
fi
exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

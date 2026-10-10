#!/bin/sh
set -eu
cd "$(dirname "$0")"
reposilite_jar=${1:-/app/reposilite.jar}
mkdir -p build/test-classes
find src/main/java src/test/java -name '*.java' > build/test-sources.txt
javac --release 11 -encoding UTF-8 -cp "$reposilite_jar" -d build/test-classes @build/test-sources.txt
cp -R src/main/resources/. build/test-classes/
java -cp "build/test-classes:$reposilite_jar" com.frostbite.reposilite.quota.QuotaCoreTest

#!/usr/bin/env bash
# Runs an ISA-JSON export through the MARS ENA adapter without submitting anything:
# MARS builds the ENA study/experiment/run XML, which is printed instead of sent to Webin.
#
# Usage: scripts/mars-offline-check/run.sh <export.json>
# Needs git and Docker. MARS and mars-repository-lib are cloned into target/mars-offline.
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage: $0 <export.json>" >&2
  exit 1
fi

MARS_COMMIT=8cc6fca592ff1695bd795144d7816bef984712df
MARS_LIB_TAG=v0.0.2

here=$(cd "$(dirname "$0")" && pwd)
work=${MARS_WORK_DIR:-$here/../../target/mars-offline}
mkdir -p "$work"
work=$(cd "$work" && pwd)

checkout() {
  [ -d "$work/$2" ] || git clone -q "$1" "$work/$2"
  git -C "$work/$2" checkout -q "$3"
}
checkout https://github.com/elixir-europe/MARS.git mars "$MARS_COMMIT"
checkout https://github.com/elixir-europe/mars-repository-lib.git mars-repository-lib "$MARS_LIB_TAG"

ena=mars/repository-services/isajson-ena
cp "$here/MetatrackOfflineXmlTest.java" "$work/$ena/src/main/java/com/elixir/biohackaton/ISAToSRA/"
cp "$1" "$work/input.json"

# mars-repository-lib is not published to Maven Central, so it is built and installed locally first.
docker run --rm -v "$work:/work" -w /work -e HOME=/work/home -e GRADLE_USER_HOME=/work/home/gradle \
  eclipse-temurin:17-jdk bash -c "
    set -e
    (cd mars-repository-lib && ./gradlew -q publishToMavenLocal)
    (cd $ena && ./gradlew -q bootJar)
    java -cp $ena/build/libs/*.jar -Dloader.main=com.elixir.biohackaton.ISAToSRA.MetatrackOfflineXmlTest \
      org.springframework.boot.loader.PropertiesLauncher input.json"

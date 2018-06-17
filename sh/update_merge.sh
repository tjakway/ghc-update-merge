#!/usr/bin/env bash

#go to script definition dir
#see https://stackoverflow.com/questions/3349105/how-to-set-current-working-directory-to-the-directory-of-the-script
cd "$(dirname "$0")"

#we should be in update_merge/sh
UPDATE_MERGE_BASE_DIR=$(pwd)/..
TARGET_DIR="$UPDATE_MERGE_BASE_DIR/target/scala-2.12"
ASSEMBLY_JAR_NAME="update-merge-assembly-1.0.jar"

ASSEMBLY_JAR="$TARGET_DIR/$ASSEMBLY_JAR_NAME"

function build_jar {
    if [[ ! -f "$UPDATE_MERGE_BASE_DIR/build.sbt" ]]; then
        echo "Could not find build.sbt" 
        exit 1 
    fi

    #run sbt to build everything
    local SBT_OUTPUT=$(mktemp)
    ( cd "$UPDATE_MERGE_BASE_DIR" && sbt -batch assembly &> "$SBT_OUTPUT" )

    #print any errors that appear
    local SBT_EXIT_RES=$?
    if [[ ${SBT_EXIT_RES} -ne 0 ]]; then
        echo "Error building the combined jar (sbt exited with $SBT_EXIT_RES): "
        cat "$SBT_OUTPUT"
        exit 1
    fi
}

function run_jar {
    java -jar "$ASSEMBLY_JAR" "$@"
}

if [[ ! -f "$ASSEMBLY_JAR" ]]; then
    echo "Building the combined jar..."
    build_jar
fi

run_jar "$@"

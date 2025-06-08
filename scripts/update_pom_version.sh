#!/bin/bash

# This script updates the version in pom.xml with the version from version.txt

VERSION_FILE="version.txt"
POM_FILE="pom.xml"

# Check if version.txt exists
if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Check if pom.xml exists
if [ ! -f "$POM_FILE" ]; then
    echo "Error: $POM_FILE not found"
    exit 1
fi

# Read version from version.txt
VERSION=$(cat "$VERSION_FILE")

# Update version in pom.xml
# Using grep and sed to replace only the project version line (the first occurrence)
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS requires a different sed syntax
    LINE_NUM=$(grep -n "<version>" "$POM_FILE" | head -1 | cut -d: -f1)
    sed -i '' "${LINE_NUM}s|<version>.*</version>|<version>$VERSION</version>|" "$POM_FILE"
else
    # Linux
    LINE_NUM=$(grep -n "<version>" "$POM_FILE" | head -1 | cut -d: -f1)
    sed -i "${LINE_NUM}s|<version>.*</version>|<version>$VERSION</version>|" "$POM_FILE"
fi

echo "Updated pom.xml version to $VERSION"
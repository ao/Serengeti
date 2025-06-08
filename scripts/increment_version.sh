#!/bin/bash

# This script increments the version number in version.txt
# It follows semantic versioning (MAJOR.MINOR.PATCH)

VERSION_FILE="version.txt"

# Check if version.txt exists
if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Read current version
CURRENT_VERSION=$(cat "$VERSION_FILE")

# Split version into components
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# Increment patch version
NEW_PATCH=$((PATCH + 1))
NEW_VERSION="$MAJOR.$MINOR.$NEW_PATCH"

# Write new version back to file
echo "$NEW_VERSION" > "$VERSION_FILE"

echo "Version incremented from $CURRENT_VERSION to $NEW_VERSION"
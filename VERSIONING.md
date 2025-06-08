# Versioning System for Serengeti

This document describes the versioning system used in the Serengeti project.

## Overview

Serengeti uses semantic versioning (MAJOR.MINOR.PATCH) for all artifacts. The version is centrally defined in the `version.txt` file at the root of the project.

## How It Works

1. The version is stored in `version.txt` at the root of the project
2. The version is automatically incremented (PATCH version) with each push to the main branch
3. All artifacts (JAR files, GitHub releases, Docker images) use this consistent version

## Version Components

- **MAJOR**: Incremented for incompatible API changes
- **MINOR**: Incremented for backward-compatible functionality additions
- **PATCH**: Incremented for backward-compatible bug fixes and minor changes

## Automated Versioning

The versioning system is automated through GitHub Actions:

1. On each push to the main branch, the CI workflow:
   - Increments the PATCH version in `version.txt`
   - Updates the version in `pom.xml`
   - Commits these changes back to the repository

2. When the CI workflow completes successfully, the release workflow:
   - Reads the version from `version.txt`
   - Creates a GitHub release with the tag `v{VERSION}`
   - Builds and uploads the JAR file with the correct version
   - Pushes Docker images with appropriate version tags

## Manual Version Updates

For MAJOR or MINOR version updates, manually edit the `version.txt` file and commit the changes. The CI system will handle the rest.

## Version Artifacts

The following artifacts are versioned consistently:

1. Maven POM version
2. JAR file names
3. GitHub release tags
4. DockerHub image tags
# Changelog

All notable changes to the Serengeti project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Comprehensive documentation for all components
- User guides for getting started, basic operations, and troubleshooting
- Architecture documentation including system architecture, component interactions, and design decisions
- CONTRIBUTING.md with guidelines for contributors
- CHANGELOG.md to track project changes

## [0.0.1] - 2024-12-15

### Added
- Initial release of Serengeti distributed database
- Core functionality:
  - Autonomous node discovery
  - Automatic data replication
  - Basic query processing
  - Web-based dashboard and interactive console
- Components:
  - Storage system with LSM storage engine
  - Indexing system with B-tree indexes
  - Query engine with SQL-like syntax
  - Network component for node communication
  - Server component for client interaction
- Testing:
  - Comprehensive test suite
  - Fast tests for development
  - Integration tests
  - Performance tests

### Known Issues
- Limited to single subnet deployment
- No authentication or encryption
- Limited query optimization
- No support for complex joins
- No support for transactions

## Types of Changes

- `Added` for new features.
- `Changed` for changes in existing functionality.
- `Deprecated` for soon-to-be removed features.
- `Removed` for now removed features.
- `Fixed` for any bug fixes.
- `Security` in case of vulnerabilities.

## Future Release Template

```
## [x.y.z] - YYYY-MM-DD

### Added
- New features

### Changed
- Changes in existing functionality

### Deprecated
- Soon-to-be removed features

### Removed
- Now removed features

### Fixed
- Bug fixes

### Security
- Vulnerabilities fixed
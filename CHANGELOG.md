# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Unit tests for core logic:
  - `ChangeSetReducer`
  - `ApplyPreconditions`
  - `DirtyMap`
  - `RepositoryLimitResolver`
  - `RepositoryState`
  - `PlayerRepositoryStore`
- GitHub Actions CI workflow (`clean build` on push/PR).
- Release checklist and smoke test runbook documentation.

### Changed
- `README.md` rewritten (Chinese/English) with installation, configuration, backup, and benchmark guidance.
- Test toolchain dependencies updated for JUnit + Mockito + Paper API test classpath.

### Fixed
- Tab completion no longer triggers repository initialization side effects.
- Recovery enqueue failure paths now mark working tree dirty instead of silently exiting.
- Mutation ticket release safety improved for branch creation exception paths.
- Runtime tracking hot path avoids unnecessary sorting on every block event.
- Repository name normalization hardened.
- `commitmsg` template persistence fixed for players without repositories.

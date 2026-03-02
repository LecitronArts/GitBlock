# Release Checklist

Use this checklist before publishing a new version.

## 1. Code And Build

- [ ] `.\gradlew.bat clean build` passes locally
- [ ] `.\gradlew.bat test` passes locally
- [ ] No unresolved critical bugs (`data loss`, `state corruption`, `deadlock`)
- [ ] `CHANGELOG.md` updated

## 2. Config And Locale

- [ ] New/changed config keys documented in `README.md`
- [ ] New/changed locale keys added for both `en_us.yml` and `zh_cn.yml`
- [ ] Permission nodes in `plugin.yml`, code, and docs are consistent

## 3. Runtime Validation

- [ ] Manual smoke flow completed (see `docs/SMOKE_TEST.md`)
- [ ] Large apply/rollback path executed at least once
- [ ] Merge conflict path executed at least once

## 4. Backup And Upgrade Safety

- [ ] Backup tested for:
  - `plugins/GitBlock/repos/`
  - `plugins/GitBlock/player-repositories.yml`
  - `plugins/GitBlock/config.yml`
  - `plugins/GitBlock/lang/`
- [ ] Upgrade notes prepared (migration expectations and rollback plan)

## 5. Packaging

- [ ] `plugin.yml` version is correct
- [ ] Built jar validated on a clean server
- [ ] Release notes include:
  - highlights
  - breaking changes
  - config migration notes
  - known limitations

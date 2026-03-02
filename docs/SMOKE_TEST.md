# Smoke Test Runbook

This runbook validates the critical GitBlock workflow on a local Paper server.

## Preparation

1. Build plugin:
   `.\gradlew.bat clean build`
2. Start dev server:
   `.\gradlew.bat runServer`
3. Join server with an operator account.

## Core Flow

1. Select and initialize repository region:
   - `/gitblock pos1`
   - `/gitblock pos2`
   - `/gitblock init`
2. Place/break a few blocks inside region.
3. Commit:
   - `/gitblock commit smoke baseline`
4. Confirm history:
   - `/gitblock log 10`
5. Branch flow:
   - `/gitblock branch smoke`
   - `/gitblock switch smoke`
6. Make edits and commit on `smoke` branch.
7. Merge back:
   - `/gitblock switch main`
   - `/gitblock merge smoke`
8. Diff and checkout:
   - `/gitblock diff HEAD main`
   - `/gitblock checkout HEAD`
9. Revert latest commit:
   - `/gitblock revert <commitId>`

## Operations Flow

1. Check running jobs:
   - `/gitblock jobs`
2. Trigger checkpoint:
   - `/gitblock checkpoint now`
3. Benchmark quick pass:
   - `/gitblock bench baseline`
   - `/gitblock bench run 1 minecraft:stone minecraft:andesite`

## Expected Results

- No command throws unhandled exception in server logs.
- Repository status remains consistent (`status`, `log`, branch heads).
- Failed apply/recovery paths mark dirty state and do not silently lose state.
- Benchmark produces `BENCH_RESULT` and optionally `BENCH_ROLLBACK_RESULT`.

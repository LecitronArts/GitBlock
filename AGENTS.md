# Repository Guidelines

## Project Structure & Module Organization
This repository is a Gradle-based Paper plugin project.

- Java source: `src/main/java/io/froststream/gitblock`
- Resources and plugin metadata: `src/main/resources` (`plugin.yml`, `config.yml`, `lang/*.yml`)
- Utility scripts: `scripts` (for example, `bench_500x500.ps1`)
- Build tooling: `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper`
- Generated output: `build` (do not edit or commit generated artifacts)

Code is organized by responsibility (`command`, `commit`, `checkout`, `diff`, `repo`, `storage`, `model`, `i18n`).

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root:

- `.\gradlew.bat clean build` - compile, run tests, and package the plugin
- `.\gradlew.bat test` - run JUnit 5 unit tests only
- `.\gradlew.bat runServer` - launch a local Paper 1.21 dev server with this plugin
- `pwsh .\scripts\bench_500x500.ps1` - parse `logs/latest.log` for `BENCH_RESULT` baseline checks

On Unix-like shells, replace `.\gradlew.bat` with `./gradlew`.

## Coding Style & Naming Conventions
- Java version: 21 (`options.release = 21`), UTF-8 source/resource encoding
- Indentation: 4 spaces; follow existing brace and wrapping style in `src/main/java`
- Naming: classes/interfaces in `PascalCase`, methods/fields in `lowerCamelCase`, constants in `UPPER_SNAKE_CASE`
- Keep package root under `io.froststream.gitblock`
- Prefer descriptive suffixes aligned with existing code (`*Service`, `*Handler`, `*Summary`, `*State`)

No formatter/linter plugin is configured; keep style consistent with surrounding files and verify with `build`.

## Testing Guidelines
- Test framework: JUnit Jupiter (`testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")`)
- Put tests in `src/test/java`, mirroring production package structure
- Name test classes `*Test` (example: `RepositoryStateServiceTest`)
- Cover new logic and regressions in the same PR; there is no enforced coverage gate yet
- For command/gameplay changes, also run a manual smoke test via `runServer`

## Commit & Pull Request Guidelines
Current history is minimal (`Initial commit`, `vibe`), so use clearer conventions going forward.

- Commit messages: short, imperative, and scoped (example: `command: validate diff cooldown per sender`)
- Keep one logical change per commit when possible
- PRs should include: summary, test evidence (`.\gradlew.bat test` or manual steps), and linked issue (if any)
- Include screenshots or log excerpts for UI/menu/command-output changes
- Call out config or locale key changes explicitly (`config.yml`, `lang/*.yml`)

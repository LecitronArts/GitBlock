# GitBlock

[中文](#中文) | [English](#english)

GitBlock is a Paper plugin that brings a Git-like workflow to region block edits:
`commit`, `log`, `checkout`, `branch`, `merge`, `diff`, `revert`, and checkpoints.

## 中文

### 项目简介

GitBlock 面向创意服/建筑服，目标是把“方块编辑历史管理”做成类似 Git 的工作流。
你可以在选区内追踪变更、提交历史、分支切换、合并冲突排查与回滚恢复。

### 核心功能

- 选区与仓库初始化：`/gitblock pos1`、`/gitblock pos2`、`/gitblock init`
- 多仓库管理（按玩家）：`/gitblock repo list|create|use`
- 提交与历史：`commit`、`log`、`checkout`、`diff`、`revert`
- 分支工作流：`branch`、`branches`、`switch`、`merge`
- 运维命令：`jobs`、`cancel`、`checkpoint now`、`bench`
- GUI 面板：`/gitblock menu`
- 本地化语言：`en_us`、`zh_cn`
- 持久化：每仓库一个 SQLite 数据库

### 环境要求

- Java 21
- Paper `1.21.x`（`api-version: 1.21`）
- 建议权限节点：
  - 基础使用：`gitblock.use`
  - 管理命令：`gitblock.admin`

### 构建与运行

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat runServer
```

构建产物默认在 `build/libs/` 下。

### 安装步骤

1. 执行 `.\gradlew.bat build` 生成插件 jar。
2. 将 jar 放入服务器 `plugins/` 目录。
3. 启动服务器，确认生成 `plugins/GitBlock/` 数据目录。
4. 按需修改 `plugins/GitBlock/config.yml`，重启后生效。

### 快速开始

```text
/gitblock pos1
/gitblock pos2
/gitblock init
/gitblock status
/gitblock commit 首次快照
/gitblock log 10
```

### 提交消息模板（commitmsg）

当你执行 `/gitblock commit` 不带消息时，会自动使用模板生成提交说明。

- 查看：`/gitblock commitmsg show`
- 设置：`/gitblock commitmsg set <template>`
- 重置：`/gitblock commitmsg reset`

支持变量：
- `{player}` `{repo}` `{branch}` `{time}` `{dirty}`

默认模板在 `config.yml`：

```yaml
commit-message:
  default-template: "manual commit by {player} in {repo} at {time}"
```

### 关键配置项

- `repo-name`
- `repositories.default-max-per-player`
- `repositories.max-by-permission`
- `commit-message.default-template`
- `permissions.use` / `permissions.admin` / `permissions.jobs-view`
- `i18n.default-locale` / `i18n.fallback-locale` / `i18n.force-locale`
- `apply.max-blocks-per-tick` / `apply.tick-budget-ms` / `apply.max-queued-jobs`
- `apply.queue-overflow-policy`（`reject-new` / `drop-oldest-pending`）
- `checkpoints.every-commits`
- `operations.serialize-mutations`
- `operations.diff-cooldown-ms`
- `history.merge-base-mode`

### 数据目录结构

- 仓库根目录：`plugins/GitBlock/repos/<repo-name>/`
- 仓库数据库：`.../gitblock.db`
- 合并冲突文件：`.../conflicts/*.conflicts`
- 检查点快照：`.../snapshots/*.pgs`
- 玩家仓库映射：`plugins/GitBlock/player-repositories.yml`

### 备份与升级建议

升级前至少备份以下内容：

- `plugins/GitBlock/repos/`
- `plugins/GitBlock/player-repositories.yml`
- `plugins/GitBlock/config.yml`
- `plugins/GitBlock/lang/`

若从旧版本升级，首次启动会自动尝试迁移旧格式仓库状态/提交索引/检查点索引到 SQLite。

### Benchmark

```text
/gitblock bench baseline
/gitblock bench run 1 minecraft:stone minecraft:andesite
pwsh .\scripts\bench_500x500.ps1
```

`bench_500x500.ps1` 会从 `logs/latest.log` 中读取 `BENCH_RESULT` 做基线检查。

## English

### Overview

GitBlock introduces a Git-style workflow for block edits inside selected regions.
It is designed for creative/build servers that need history, branch workflow, and rollback safety.

### Core Features

- Region bootstrap: `pos1`, `pos2`, `init`
- Per-player repositories: `repo list|create|use`
- History flow: `commit`, `log`, `checkout`, `diff`, `revert`
- Branch flow: `branch`, `branches`, `switch`, `merge`
- Ops commands: `jobs`, `cancel`, `checkpoint now`, `bench`
- GUI entry: `/gitblock menu`
- Localized messages: `en_us`, `zh_cn`
- SQLite-backed persistence per repository

### Requirements

- Java 21
- Paper `1.21.x`
- Recommended permissions:
  - Base usage: `gitblock.use`
  - Admin operations: `gitblock.admin`

### Build / Test / Dev Server

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat runServer
```

### Install

1. Build plugin jar with Gradle.
2. Copy jar to server `plugins/`.
3. Start server once to generate `plugins/GitBlock/`.
4. Tune `config.yml`, then restart.

### Quick Start

```text
/gitblock pos1
/gitblock pos2
/gitblock init
/gitblock commit initial snapshot
/gitblock log 10
```

### Commit Message Template

If `/gitblock commit` is executed without an explicit message, GitBlock uses the commit template.

- `commitmsg show`
- `commitmsg set <template>`
- `commitmsg reset`

Supported tokens:
- `{player}` `{repo}` `{branch}` `{time}` `{dirty}`

### Important Config Keys

- `repo-name`
- `repositories.*`
- `commit-message.default-template`
- `permissions.*`
- `i18n.*`
- `apply.*`
- `checkpoints.every-commits`
- `operations.*`
- `history.merge-base-mode`

### Storage Layout

- Repo root: `plugins/GitBlock/repos/<repo-name>/`
- SQLite DB: `.../gitblock.db`
- Conflict files: `.../conflicts/*.conflicts`
- Checkpoint snapshots: `.../snapshots/*.pgs`
- Player store: `plugins/GitBlock/player-repositories.yml`

### Backup Before Upgrade

Always back up:

- `plugins/GitBlock/repos/`
- `plugins/GitBlock/player-repositories.yml`
- `plugins/GitBlock/config.yml`
- `plugins/GitBlock/lang/`

### Benchmark Workflow

```text
/gitblock bench baseline
/gitblock bench run 1 minecraft:stone minecraft:andesite
pwsh .\scripts\bench_500x500.ps1
```

# GitBlock

[中文](#中文) | [English](#english)

GitBlock is a Paper plugin that brings a Git-like workflow to block edits in a selected region: commit, log, checkout, branch, merge, diff, and revert.

## 中文

### 项目简介
GitBlock 面向创意服务器，提供“像 Git 一样管理方块变更”的工作流。  
你可以在选区内追踪改动并进行提交、历史查看、分支切换、合并和回滚。

### 主要能力
- 选区仓库初始化：`/gitblock pos1`, `/gitblock pos2`, `/gitblock init`
- 历史与切换：`commit`, `log`, `checkout`, `diff`, `revert`
- 分支管理：`branch`, `branches`, `switch`, `merge`
- 运维能力：`jobs`, `cancel`, `checkpoint now`, `bench`
- 菜单入口：`/gitblock menu`
- 多语言消息：`en_us` / `zh_cn`
- 持久化存储：SQLite（自动随插件加载）

### 环境要求
- Java 21
- Paper `1.21.x`（`api-version: 1.21`）
- 权限节点：
  - 基础：`gitblock.use`
  - 管理：`gitblock.admin`（如 `bench`、`cancel`、`checkpoint`）

### 构建与本地调试
```powershell
.\gradlew.bat clean build
.\gradlew.bat runServer
.\gradlew.bat test
```

构建产物默认位于 `build/libs/`，例如：`build/libs/untitled8-0.1.0-Preview.jar`。

### 安装步骤
1. 执行 `.\gradlew.bat build` 生成插件 Jar。
2. 将 Jar 放入服务端 `plugins/` 目录。
3. 启动服务端，确认生成 `plugins/GitBlock/` 数据目录。
4. 按需修改 `plugins/GitBlock/config.yml` 后重启或重载插件。

### 快速开始
```text
/gitblock pos1
/gitblock pos2
/gitblock init
/gitblock status
/gitblock commit first snapshot
/gitblock log 10
```

### 关键配置（`config.yml`）
- `repo-name`: 仓库名（会被清洗为安全字符）
- `i18n.default-locale` / `i18n.fallback-locale` / `i18n.force-locale`
- `apply.max-blocks-per-tick` / `apply.tick-budget-ms` / `apply.max-queued-jobs`
- `apply.queue-overflow-policy`: `reject-new` 或 `drop-oldest-pending`
- `checkpoints.every-commits`: 自动检查点频率
- `operations.serialize-mutations`: 是否串行化变更操作（推荐 `true`）
- `operations.diff-cooldown-ms`: diff 冷却时间
- `history.merge-base-mode`: `first-parent` 或 `all-parents`

### 数据目录说明
- 仓库根目录：`plugins/GitBlock/repos/<repo-name>/`
- 主数据库：`plugins/GitBlock/repos/<repo-name>/gitblock.db`
- 合并冲突文件：`plugins/GitBlock/repos/<repo-name>/conflicts/*.conflicts`
- 检查点快照：`plugins/GitBlock/repos/<repo-name>/snapshots/*.pgs`

### 基准测试
```text
/gitblock bench baseline
/gitblock bench run 1 minecraft:stone minecraft:andesite
pwsh .\scripts\bench_500x500.ps1
```

`bench_500x500.ps1` 会从服务端日志中读取 `BENCH_RESULT` 并校验 TPS/吞吐基线。

## English

### Overview
GitBlock is built for creative servers and introduces a Git-style workflow for block changes inside a selected region.

### Core Features
- Region repository bootstrap: `pos1`, `pos2`, `init`
- History operations: `commit`, `log`, `checkout`, `diff`, `revert`
- Branch workflow: `branch`, `branches`, `switch`, `merge`
- Operational controls: `jobs`, `cancel`, `checkpoint now`, `bench`
- GUI entry: `/gitblock menu`
- Localized messaging: `en_us` and `zh_cn`
- SQLite-backed persistence

### Requirements
- Java 21
- Paper `1.21.x`
- Permission nodes:
  - Base commands: `gitblock.use`
  - Admin commands: `gitblock.admin`

### Build, Test, Run
```powershell
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat runServer
```

Jar output is generated under `build/libs/`.

### Installation
1. Build the plugin jar with Gradle.
2. Copy the jar into your server `plugins/` folder.
3. Start server once to create `plugins/GitBlock/`.
4. Tune `plugins/GitBlock/config.yml` and restart/reload.

### Quick Start
```text
/gitblock pos1
/gitblock pos2
/gitblock init
/gitblock commit initial snapshot
/gitblock checkout <commit|branch>
```

### Important Config Keys
- `repo-name`
- `i18n.*`
- `apply.*` and `apply.queue-overflow-policy`
- `checkpoints.every-commits`
- `operations.serialize-mutations`
- `operations.diff-cooldown-ms`
- `history.merge-base-mode`

### Storage Layout
- Repo root: `plugins/GitBlock/repos/<repo-name>/`
- SQLite DB: `.../gitblock.db`
- Merge conflicts: `.../conflicts/*.conflicts`
- Checkpoint snapshots: `.../snapshots/*.pgs`

### Benchmark Workflow
Use `/gitblock bench baseline` or `bench run`, then parse logs with:

```powershell
pwsh .\scripts\bench_500x500.ps1
```

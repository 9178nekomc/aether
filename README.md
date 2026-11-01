# GitHub AI Agent Pro v2.1 🚀

**一条命令启动 • 内置纯 Java Git • 富色彩 TUI • Claude Code / Codex 级自主编程与 GitHub 智能体**

一个基于 **Java 21 + 内置 Eclipse JGit + 官方 GitHub REST API + 多步 ReAct 引擎** 打造的终端自主编程与 GitHub 协作智能体。装上之后，在任意项目目录敲一个 `github-agent` 就能开跑——**首次启动会像 Claude Code 一样询问你是否允许它访问当前工作区，并让你选择权限模式**。无需安装 Git 客户端。

```console
$ cd ~/code/my-project
$ github-agent
╭── 🔐 工作区访问授权 (WORKSPACE TRUST) ──────────────────────────────────────╮
│ 📁 工作区目录 : /home/me/code/my-project                                   │
│                                                                            │
│ 允许访问后，本智能体在此目录内可以：                                        │
│   • 读取、创建、修改、删除该目录下的文件（写入前受审批门控保护）            │
│   • 执行内置纯 Java Git 命令（status / diff / commit / push 等）            │
│   • 使用你配置的 Token 调用 GitHub REST API（建仓 / 提交 / Issue / PR）     │
│                                                                            │
│ 该目录之外的路径默认只读，写入会被拒绝（可用 --allow-outside 显式放行）。   │
│ [1] 是，信任此目录并记住（推荐）   [2] 仅本次会话   [3] 否，退出             │
╰────────────────────────────────────────────────────────────────────────────╯
  ❓ 是否允许访问此工作区? [1/2/3] (默认 1):
```

## 📦 安装（任选一种）

**Windows（PowerShell）**
```powershell
irm https://raw.githubusercontent.com/9178nekomc/github-Agent/main/install.ps1 | iex
```

**Windows（cmd.exe / 传统命令提示符）** —— `irm` 是 PowerShell 的别名，cmd 里不存在，必须显式调用 PowerShell：
```bat
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/9178nekomc/github-Agent/main/install.ps1 | iex"
```

**macOS / Linux**
```bash
curl -fsSL https://raw.githubusercontent.com/9178nekomc/github-Agent/main/install.sh | bash
```

**从源码安装**（自动 Maven 构建）
```bash
git clone https://github.com/9178nekomc/github-Agent.git
cd github-Agent
./install.sh                 # macOS / Linux
powershell -ExecutionPolicy Bypass -File .\install.ps1   # Windows
```

安装脚本会：检查 Java 21 → 构建或下载 `github-agent.jar` → 生成 `github-agent` / `gha` 启动器 → 把启动器目录加入 PATH → 自检并打印下一步。修改 PATH 前会把原始值备份到 `~/.github-agent/path-backup.txt`（Windows 下按注册表原始类型写回，不会把 `%USERPROFILE%` 这类变量固化成绝对路径）。安装后**重开一个终端**即可全局使用（已经在运行的窗口不会刷新 PATH，必须新开；Windows 下脚本写完注册表会广播 `WM_SETTINGCHANGE`，从开始菜单新开的窗口即可生效，无需注销）。

卸载：`./install.sh --uninstall [--purge]` 或 `.\install.ps1 -Uninstall [-Purge]`。

## ⚡ 用法

```bash
github-agent                       # 在当前目录启动（首次询问工作区授权 + 权限模式）
github-agent ~/code/my-project     # 在指定目录启动
github-agent -p "把这个模块重构为策略模式"   # 一次性任务模式，执行完退出（脚本/CI 友好）
gha                                # 短别名，等价于 github-agent
```

| 参数 | 说明 |
| :--- | :--- |
| `-p, --print <任务>` | 一次性执行任务后退出，不做交互循环 |
| `--mode <safe\|strict\|yolo>` | 权限模式，见下文 |
| `--workspace, -C <目录>` | 指定工作区（等同位置参数） |
| `--theme <name>` | 主题：`claude` / `cyberpunk` / `codex` / `dracula` / `nord` |
| `--model <name>` | 覆盖本次会话使用的模型 |
| `--trust, -y` | 跳过信任询问并直接记住当前目录（CI 必需） |
| `--forget` | 撤销当前目录的信任记录 |
| `--allow-outside` | 允许读写工作区之外的路径（默认阻止） |
| `--doctor` | 非交互式环境自检 |
| `--config` | 打印当前生效配置（密钥打码） |
| `--no-color` | 关闭 ANSI 颜色（也响应 `NO_COLOR` 环境变量） |
| `-h, --help` / `-v, --version` | 帮助 / 版本 |

### 🛡️ 三种权限模式

| 模式 | 行为 |
| :--- | :--- |
| `safe`（默认） | 只读工具自动放行；写文件、改代码、Git 变更、GitHub 远端操作前弹出彩色 Diff 并询问 `[Y/n/a/!]` |
| `strict` | **所有**工具调用（含只读）都逐次确认，最谨慎 |
| `yolo` | 全自动执行，不再询问（高危，仅在可信仓库使用） |

会话中随时用 `/mode safe|strict|yolo` 或 `/yolo` 切换；模式会记进该目录的信任记录。

## 🗂️ 配置与数据存放位置

全部集中在用户目录，不再污染项目：

```text
~/.github-agent/
├── config.properties      # API 端点 / Key / 模型 / GitHub Token / 主题 / 权限
├── trusted-dirs.json      # 已授权的工作区目录 + 各自权限模式
└── lib/github-agent.jar   # 程序本体（由安装脚本放置）
```

- 凭据文件在 POSIX 系统上尽量收紧为 `600`（仅本人可读写）。
- 用环境变量 `GITHUB_AGENT_HOME` 可把整个目录搬到别处（便携模式 / 多环境隔离）。
- 支持 `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL` / `GITHUB_TOKEN` 环境变量预填充。
- 从 v2.0 升级时会自动把项目目录下的旧 `config.properties` 迁移过来（旧文件保留不删）。

## ✨ v2.1 新增：安装即用的 CLI 启动器

| 维度 | v2.0 Pro | v2.1（本版） |
| :--- | :--- | :--- |
| **启动方式** | 必须 `java -jar target/github-agent.jar`，且只能在 jar 所在目录附近使用 | **全局命令 `github-agent` / `gha`**：`cd` 到任意项目直接启动，自动以当前目录为工作区 |
| **工作区授权** | 无概念，配置里的 `workspace` 目录是硬编码 | **首次进入某目录弹授权面板**（对标 Claude Code 的 trust dialog），结果记录到 `trusted-dirs.json`，同一目录不再重复询问 |
| **权限粒度** | SAFE / YOLO 两档 | **safe / strict / yolo 三档**，可按目录分别记住，随时 `/mode` 切换 |
| **沙箱边界** | 可随意读写磁盘任意路径 | **工作区外写入默认拒绝**，需 `--allow-outside` 显式放行（读仍允许） |
| **配置位置** | 项目目录下的 `config.properties` | **用户级 `~/.github-agent/`**，一处配置到处生效，权限 600 |
| **自动化支持** | 无 | `-p` 一次性任务模式 + `--trust` 静默授权 + `--config` / `--doctor` 可编程调用 |
| **分发方式** | 自己打包 | **跨平台安装脚本 + GitHub Release + Actions 自动发版** |

## 🛠️ 内置 30 项核心工具全景

1. **本地文件与代码工具 (6 项)**：`list_dir`、`read_file`、`search_files`、`write_file`、`edit_file`、`delete_file`
2. **内置纯 Java Git 引擎工具 (1 项全能命令入口，覆盖 17 个子命令)**：`git_command`（原生支持 `init`, `clone`, `status`, `diff`, `add`, `commit`, `log`, `show`, `branch`, `checkout`, `switch`, `reset`, `stash`, `tag`, `remote`, `push`, `pull`, `fetch`, `rm`）
3. **GitHub 官方 REST API 工具 (22 项)**：
   - 项目与仓库：`gh_create_project_and_publish`、`gh_create_repo`、`gh_delete_repo`、`gh_fork_repo`、`gh_list_user_repos`、`gh_search_repos`、`gh_browse_trending`
   - 远端文件与代码：`gh_get_readme`、`gh_get_remote_file`、`gh_put_remote_file`、`gh_search_code`
   - Issue / PR / Release：`gh_list_issues`、`gh_create_issue`、`gh_comment_issue`、`gh_list_prs`、`gh_create_pr`、`gh_create_release`
   - 社交与画像：`gh_star_repo`、`gh_unstar_repo`、`gh_follow_user`、`gh_unfollow_user`、`gh_user_profile`
4. **本地构建与验证工具 (1 项)**：`run_shell`（若传入 `git` 命令会自动无缝路由至内置 JGit 引擎）

## ⌨️ TUI 快捷斜杠命令

- `/help` ：指令与能力全景面板
- `/mode [safe|strict|yolo]` ：切换权限模式
- `/yolo [on|off]` ：一键开关 YOLO 全自动模式
- `/trust` ：查看本目录信任状态与已授权目录；`/trust outside on|off` 切换越界写入；`/trust revoke` 撤销本目录授权
- `/theme <name>` ：实时切换配色主题
- `/git <cmd>` ：直接运行内置纯 Java Git 命令
- `/tree [dir]` ：工作区树形目录与文件大小
- `/model <name>` ：实时切换 AI 模型
- `/config` / `/config set <k> <v>` ：查看或热更新配置并持久化
- `/memory [init]` ：查看或生成工作区 `AGENT.md` 项目规范提示词（对标 `CLAUDE.md`）
- `/prompt` ：查看当前完整 System Prompt
- `/status` ：模型、Git 分支、权限模式、Token 统计
- `/about` ：版本号、启动命令与配置文件位置
- `/compact` ：压缩对话上下文
- `/clear` ：清屏并重置会话
- `/doctor` ：环境与 JGit / API 健康自检
- `exit` / `退出` ：安全退出

## 🔧 从源码构建（开发者）

```bash
mvn clean package -DskipTests
java -jar target/github-agent.jar --version
```

- **要求**：JDK 21+、Maven 3.8+（运行时不强制要求安装 Git）
- 发版：`git tag v2.1.3 && git push origin v2.1.3`，GitHub Actions 会自动构建并发布 Release（含 jar 与安装脚本）。

## 📂 项目源码结构

```text
com.ai.github.agent/
├── Main.java                 # 启动入口：CLI 分发、一次性任务模式、TUI 交互循环
├── CliOptions.java           # 命令行参数解析（目录 / -p / --mode / --trust / --forget ...）
├── AppInfo.java              # 版本号与用户级路径（~/.github-agent）解析
├── TrustGate.java            # 工作区授权面板：是否允许访问 + 权限模式选择
├── TrustStore.java           # 信任记录（trusted-dirs.json）读写与权限模式定义
├── Doctor.java               # 环境自检报告（--doctor 与 /doctor 共用）
├── TuiTheme.java             # 5 套 ANSI TrueColor 渐变色彩主题系统
├── TuiRenderer.java          # TUI 渲染引擎（圆角面板、状态栏、彩色 Diff、Markdown、Braille 动画）
├── PermissionGate.java       # 三级风险审批门控（SAFE 确认面板 + 会话白名单 + YOLO）
├── SystemPromptBuilder.java  # Claude Code / Codex 级动态系统提示词构建器
├── AgentLoop.java            # 多步 ReAct 推理主循环、30 项工具路由与斜杠命令
├── FileSystemTools.java      # 文件读写、行级精准编辑、目录树、正则搜索 + 工作区沙箱边界
├── GitOperations.java        # 内置纯 Java Git 引擎（JGit，17 种子命令，零外部依赖）
├── GitHubApiClient.java      # GitHub REST API 封装（Repo/File/Code/Issue/PR/Release/Star/Follow）
├── GitHubActions.java        # 高阶 GitHub 业务编排与格式化输出
├── ProjectGenerator.java     # AI 全栈项目脚手架生成器
├── AIClient.java             # 多轮对话 AI 客户端（思维链剥离、JSON 自愈、Token 统计）
├── ConfigManager.java        # 用户级配置管理（迁移、环境变量、热更新、权限模式）
└── JsonUtils.java            # JSON 与 <think> 标签清洗工具
```

---

## 🔐 安全说明

- 首次在某目录启动必须显式授权；非交互环境（管道 / CI）必须传 `--trust`，**绝不静默授权**，拒绝授权时退出码为 `2`。
- 工作区之外的写入默认被拒绝；`--allow-outside` 或 `/trust outside on` 才会放行，且界面上会持续标注。
- 高危操作（删仓库、`reset --hard`、`--force`、`rm` 等）单独归入 `DESTRUCTIVE` 风险级别，即便 YOLO 模式也会二次拦截（可用配置放开）。
- API Key 与 GitHub Token 以明文存放在 `~/.github-agent/config.properties`，依赖文件权限保护；共享机器上建议用环境变量注入而非落盘。

## 📄 License

见 [LICENSE](LICENSE)。

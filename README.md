# Aether ✦ Minimalist TUI Workbench

**一条命令启动 • 内置纯 Java Git • 极简 TUI • 13 家模型供应商 • Skills & MCP 扩展**

一个基于 **Java 21 + 内置 Eclipse JGit + 多协议大模型网关 + 多步 ReAct 引擎** 打造的终端自主编程智能体。装上之后，在任意项目目录敲一个 `aether` 就能开跑——**首次启动会询问你是否允许它访问当前工作区，并让你选择权限模式**。无需安装 Git 客户端。

```console
$ cd ~/code/my-project
$ aether
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

## ⚖️ 命名与商标

**Aether 是一个独立开源项目，与 GitHub, Inc.、Microsoft Corporation 及其关联公司没有任何隶属、赞助或背书关系。**

本项目通过 GitHub 官方公开的 REST API 提供仓库管理能力；文档中出现的 "GitHub" 仅用于客观描述所对接的平台与该平台提供的服务，属于描述性使用。"GitHub" 是其所有者的商标，本项目不主张任何相关权利，也不在名称、图标或发行物中把该商标用作自身标识。

## ✨ 核心特性

1. **极简淡蓝 + 纯白主题（3 大色系 × 亮色/暗色 共 6 套）**：`sky-dark`（默认）、`sky-light`、`amber-dark`、`amber-light`、`emerald-dark`、`emerald-light`。
2. **分步清屏式引导向导**：首次配置与 `/provider`、`/theme` 的每一步都自动清屏并显示进度条，不再堆叠历史输出。
3. **内置 13 家供应商与 4 大协议**：
   - 供应商：DeepSeek、OpenAI、Anthropic、Google Gemini、阿里云百炼 DashScope、SiliconFlow、智谱 GLM、Moonshot Kimi、火山方舟、OpenRouter、xAI Grok、Ollama 本地模型、Custom 自定义网关。
   - 协议：`openai-chat`、`openai-responses`、`anthropic-messages`（自动注入 `cache_control` 断点）、`gemini-native`。
4. **树型结构展示每一步**：`╭─ 🌳` / `├─ 🧠 Step` / `│ ├─ 💭 Thought` / `│ ├─ 🔧 Tool` / `╰─ 🏁 Turn Complete`，实时显示耗时、输入/输出 Token、Prompt Cache 命中数与命中率。
5. **会话自动保存与恢复**：每步进度写入 `.agent-sessions/<sessionId>.json`，可用 `/sessions`、`/resume` 或 `--resume` 恢复。
6. **原生支持 Skills 与 MCP**：`skills/` 下 Markdown + YAML Frontmatter 技能包（首次运行自动生成 4 个工程技能）；`mcp.json` 支持 Stdio 子进程与 HTTP JSON-RPC 2.0 两种传输。
7. **零外部 Git 依赖**：内置 JGit 原生执行 `init / clone / status / diff / add / commit / log / show / branch / checkout / switch / reset / stash / tag / remote / push / pull / fetch / rm`。
8. **三级权限模式**：`safe`（默认，写前弹彩色 Diff 确认）/ `strict`（含只读全部确认）/ `yolo`（全自动，高危命令仍二次拦截）。

## 📦 安装（任选一种）

仓库：<https://github.com/9178nekomc/aether>

**Windows（PowerShell）**
```powershell
irm https://raw.githubusercontent.com/9178nekomc/aether/main/install.ps1 | iex
```

**Windows（cmd.exe / 传统命令提示符）** —— `irm` 是 PowerShell 的别名，cmd 里不存在，必须显式调用 PowerShell：
```bat
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/9178nekomc/aether/main/install.ps1 | iex"
```

**macOS / Linux**
```bash
curl -fsSL https://raw.githubusercontent.com/9178nekomc/aether/main/install.sh | bash
```

**从源码安装**（自动 Maven 构建）
```bash
git clone https://github.com/9178nekomc/aether.git
cd aether
./install.sh                                              # macOS / Linux
powershell -ExecutionPolicy Bypass -File .\install.ps1    # Windows
```

安装脚本会：检查 Java 21 → 构建或下载 `aether.jar` → 生成 `aether` / `ae` 启动器 → 把启动器目录加入 PATH → 自检并打印下一步。修改 PATH 前会把原始值备份到 `~/.aether/path-backup.txt`（Windows 下按注册表原始类型写回，不会把 `%USERPROFILE%` 这类变量固化成绝对路径）。安装后**重开一个终端**即可全局使用（已经在运行的窗口不会刷新 PATH，必须新开；Windows 下脚本写完注册表会广播 `WM_SETTINGCHANGE`，从开始菜单新开的窗口即可生效，无需注销）。

卸载：`./install.sh --uninstall [--purge]` 或 `.\install.ps1 -Uninstall [-Purge]`。

> 从旧版 `github-agent`（v3.0.x 及更早，目录 `~/.github-agent`）升级：首次启动会自动迁移配置与信任记录到 `~/.aether`，旧目录保留不删，可手动清理。

## ⚡ 用法

```bash
aether                             # 在当前目录启动（首次询问工作区授权 + 权限模式）
aether ~/code/my-project           # 在指定目录启动
aether -p "把这个模块重构为策略模式"   # 一次性任务模式，执行完退出（脚本/CI 友好）
aether --provider anthropic --model claude-sonnet-4-5
aether --resume                    # 恢复最近一次会话
ae                                 # 短别名，等价于 aether
```

| 参数 | 说明 |
| :--- | :--- |
| `-p, --print <任务>` | 一次性执行任务后退出，不做交互循环 |
| `--mode <safe\|strict\|yolo>` | 权限模式，见下文 |
| `--workspace, -C <目录>` | 指定工作区（等同位置参数） |
| `--provider <ID>` | 供应商：`deepseek` / `openai` / `anthropic` / `gemini` / `dashscope` / `siliconflow` / `zhipu` / `moonshot` / `ark` / `openrouter` / `xai` / `ollama` / `custom` |
| `--protocol <name>` | `openai-chat` / `openai-responses` / `anthropic-messages` / `gemini-native` |
| `--theme <name>` | 主题：`sky-dark`(默认) / `sky-light` / `amber-dark` / `amber-light` / `emerald-dark` / `emerald-light` |
| `--model <name>` | 覆盖本次会话使用的模型（任意模型名，不做白名单限制） |
| `--resume [会话ID]` | 恢复历史会话，缺省为 `latest` |
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
| `safe`（默认） | 只读工具自动放行；写文件、改代码、Git 变更、远端操作前弹出彩色 Diff 并询问 `[Y/n/a/!]` |
| `strict` | **所有**工具调用（含只读）都逐次确认，最谨慎 |
| `yolo` | 全自动执行，不再询问（高危，仅在可信仓库使用） |

会话中随时用 `/mode safe|strict|yolo` 或 `/yolo` 切换；模式会记进该目录的信任记录。

### 🔑 关于 GitHub Token（可选）

配置向导里的 GitHub Token 是**可选项**，用途仅限于方便进行 GitHub 管理（建仓、Push、PR、Issue、Release）。留空直接回车即跳过，本地代码编辑、内置 Git 提交、Skills 与 MCP 全部照常可用，只是不能操作远端仓库。

## 🗂️ 配置与数据存放位置

全局配置集中在用户目录，项目目录只保留会话与技能等运行产物：

```text
~/.aether/
├── config.properties      # 供应商 / 协议 / 密钥 / Token / 主题 / 权限
├── trusted-dirs.json      # 已授权的工作区目录 + 各自权限模式
├── path-backup.txt        # 安装脚本修改 PATH 前的原始值备份
└── lib/aether.jar         # 程序本体（由安装脚本放置）

<工作区>/
├── skills/                # Skills 技能包（首次运行自动生成 4 个）
├── mcp.json               # MCP 服务配置
└── .agent-sessions/       # 会话进度快照（/resume 恢复用）
```

- 凭据文件在 POSIX 系统上尽量收紧为 `600`（仅本人可读写）。
- 用环境变量 `AETHER_HOME` 可把整个配置目录搬到别处（便携模式 / 多环境隔离）。
- 各供应商支持各自的环境变量预填充（如 `DEEPSEEK_API_KEY`、`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`），另有 `GITHUB_TOKEN`。

## ⌨️ TUI 快捷斜杠命令

- `/help` ：交互式命令中心（输入序号即可执行）
- `/provider` ：切换供应商 / 协议 / 端点（清屏向导）
- `/model <name>` ：实时切换模型（自由输入）
- `/theme <name>` ：实时切换主题（6 套）
- `/mode [safe|strict|yolo]` ：切换权限模式
- `/yolo [on|off]` ：一键开关 YOLO 全自动模式
- `/trust` ：查看信任状态；`/trust outside on|off` 切换越界写入；`/trust revoke` 撤销授权
- `/sessions` / `/resume` ：浏览历史会话并一键恢复
- `/skills` ：查看、启用/禁用或新建技能包
- `/mcp` ：管理与测试 MCP 服务
- `/git <cmd>` ：直接运行内置纯 Java Git 命令
- `/tree [dir]` ：工作区树形目录与文件大小
- `/config` / `/config set <k> <v>` ：查看或热更新配置并持久化
- `/memory [init]` ：查看或生成工作区 `AGENT.md` 项目规范提示词
- `/prompt` ：查看当前完整 System Prompt
- `/status` ：模型、Git 分支、权限模式、Token 与缓存命中率
- `/about` ：版本号、安装目录、配置文件位置
- `/compact` ：压缩对话上下文
- `/clear` ：清屏并开启新会话
- `/doctor` ：环境与 JGit / API 健康自检
- `exit` / `退出` ：保存会话并退出

## 🛠️ 内置 34 项核心工具

1. **本地文件与代码 (6)**：`list_dir`、`read_file`、`search_files`、`write_file`、`edit_file`、`delete_file`
2. **内置纯 Java Git 引擎 (1 个入口，覆盖 19 个子命令)**：`git_command`（`init`, `clone`, `status`, `diff`, `add`, `commit`, `log`, `show`, `branch`, `checkout`, `switch`, `reset`, `stash`, `tag`, `remote`, `push`, `pull`, `fetch`, `rm`）
3. **GitHub REST API (22)**：
   - 仓库与项目：`gh_create_project_and_publish`、`gh_create_repo`、`gh_delete_repo`、`gh_fork_repo`、`gh_list_user_repos`、`gh_search_repos`、`gh_browse_trending`
   - 远端文件与代码：`gh_get_readme`、`gh_get_remote_file`、`gh_put_remote_file`、`gh_search_code`
   - Issue / PR / Release：`gh_list_issues`、`gh_create_issue`、`gh_comment_issue`、`gh_list_prs`、`gh_create_pr`、`gh_create_release`
   - 社交与画像：`gh_star_repo`、`gh_unstar_repo`、`gh_follow_user`、`gh_unfollow_user`、`gh_user_profile`
4. **扩展与执行 (5)**：`list_skills`、`load_skill`、`mcp_list_tools`、`mcp_call_tool`、`run_shell`（若传入 `git` 命令会自动路由至内置 JGit）

## 🔧 从源码构建（开发者）

```bash
mvn clean package -DskipTests
java -jar target/aether.jar --version
```

- **要求**：JDK 21+、Maven 3.8+（运行时不强制要求安装 Git）
- 发版：`git tag v3.1.2 && git push origin v3.1.2`，GitHub Actions 会自动构建并发布 Release（含 jar 与安装脚本）。

## 📂 项目源码结构

```text
com.aether.agent/
├── Main.java                 # 启动入口：CLI 分发、信任授权、一次性任务、TUI 循环
├── CliOptions.java           # 命令行参数解析（目录 / -p / --mode / --provider / --resume ...）
├── AppInfo.java              # 版本号、用户级路径（~/.aether）与旧目录迁移
├── TrustGate.java            # 工作区授权面板：是否允许访问 + 权限模式选择
├── TrustStore.java           # 信任记录（trusted-dirs.json）读写与权限模式定义
├── Doctor.java               # 环境自检报告（--doctor 与 /doctor 共用）
├── TuiTheme.java             # 6 套极简 TrueColor 主题（3 色系 × 亮暗）
├── TuiRenderer.java          # TUI 渲染引擎（树型步骤、状态栏、彩色 Diff、Markdown、动画）
├── PermissionGate.java       # 三级风险审批门控（SAFE / STRICT / YOLO + 会话白名单）
├── SystemPromptBuilder.java  # 动态系统提示词构建器
├── AgentLoop.java            # ReAct 主循环、34 项工具路由与斜杠命令
├── FileSystemTools.java      # 文件读写、行级编辑、目录树、正则搜索 + 工作区沙箱
├── GitOperations.java        # 内置纯 Java Git 引擎（JGit，零外部依赖）
├── GitHubApiClient.java      # GitHub REST API 封装（平台对接层）
├── GitHubActions.java        # 高阶仓库操作编排与格式化输出
├── ProjectGenerator.java     # AI 全栈项目脚手架生成器
├── AIClient.java             # 多协议大模型客户端（4 种协议、思维链剥离、Token 与缓存统计）
├── ProviderRegistry.java     # 13 家供应商与 4 大协议注册表
├── SessionManager.java       # 会话快照保存与恢复
├── SkillManager.java         # Skills 技能包加载与启用状态管理
├── McpClientManager.java     # MCP 客户端（Stdio / HTTP JSON-RPC）
├── ConfigManager.java        # 用户级配置管理（迁移、环境变量、热更新、权限模式）
└── JsonUtils.java            # JSON 与 <think> 标签清洗工具
```

---

## 🔐 安全说明

- 首次在某目录启动必须显式授权；非交互环境（管道 / CI）必须传 `--trust`，**绝不静默授权**，拒绝授权时退出码为 `2`。
- 工作区之外的写入默认被拒绝；`--allow-outside` 或 `/trust outside on` 才会放行，且界面上会持续标注。
- 高危操作（删仓库、`reset --hard`、`--force`、`rm` 等）单独归入 `DESTRUCTIVE` 风险级别，即便 YOLO 模式也会二次拦截（可用配置放开）。
- API Key 与 Token 以明文存放在 `~/.aether/config.properties`，依赖文件权限保护；共享机器上建议用环境变量注入而非落盘。
- MCP 服务与 Skills 会在工作区内创建配置文件，请勿在不可信目录中启用未知来源的 MCP 服务。

## 📄 License

见 [LICENSE](LICENSE)。

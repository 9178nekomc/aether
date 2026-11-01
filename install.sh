#!/usr/bin/env bash
# ==============================================================================
#  Aether —— macOS / Linux 安装脚本
#
#  安装后即可在任意目录用一条命令启动：
#     aether            # 在当前目录启动（首次会询问是否允许访问该工作区）
#     aether ~/proj     # 在指定目录启动
#     ae                # 短别名（等价于 aether）
#
#  用法:
#     bash install.sh                 # 自动构建 / 下载并安装
#     bash install.sh --jar a.jar     # 用已构建好的 jar 安装
#     bash install.sh --uninstall     # 卸载
#     curl -fsSL .../install.sh | bash
#
#  目录布局:
#     ~/.aether/lib/aether.jar        程序本体
#     ~/.aether/config.properties     供应商 / 密钥 / Token / 主题 / 权限
#     ~/.aether/trusted-dirs.json     已授权的工作区目录与权限模式
#     ~/.local/bin/aether             启动器（在 PATH 中）
#     ~/.local/bin/ae                 短别名
# ==============================================================================
set -euo pipefail

VERSION="3.1.1"
REPO="9178nekomc/aether"
ROOT="${AETHER_HOME:-$HOME/.aether}"
BIN_DIR="${AETHER_BIN:-$HOME/.local/bin}"
JAR_SRC=""
NO_PATH=0
UNINSTALL=0
PURGE=0

BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; CYAN=""; RESET=""
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD="$(printf '\033[1m')"; DIM="$(printf '\033[2m')"
  GREEN="$(printf '\033[32m')"; YELLOW="$(printf '\033[33m')"
  RED="$(printf '\033[31m')"; CYAN="$(printf '\033[36m')"; RESET="$(printf '\033[0m')"
fi

info() { printf '%s\n' "  ${CYAN}ℹ${RESET} $*"; }
ok()   { printf '%s\n' "  ${GREEN}✔${RESET} $*"; }
warn() { printf '%s\n' "  ${YELLOW}⚠${RESET} $*"; }
die()  { printf '%s\n' "  ${RED}✖${RESET} $*" >&2; exit 1; }

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --jar)        JAR_SRC="${2:-}"; shift 2 ;;
    --root)       ROOT="${2:-}"; shift 2 ;;
    --bin)        BIN_DIR="${2:-}"; shift 2 ;;
    --no-path)    NO_PATH=1; shift ;;
    --uninstall)  UNINSTALL=1; shift ;;
    --purge)      PURGE=1; shift ;;
    -h|--help)    usage ;;
    *)            die "未知参数: $1（用 --help 查看用法）" ;;
  esac
done

# ---------------------------------------------------------------- 卸载
if [ "$UNINSTALL" = "1" ]; then
  printf '\n%s\n' "${BOLD}卸载 Aether${RESET}"
  for f in "$BIN_DIR/aether" "$BIN_DIR/ae"; do
    if [ -e "$f" ] || [ -L "$f" ]; then rm -f "$f"; ok "已移除 $f"; fi
  done
  if [ "$PURGE" = "1" ]; then
    rm -rf "$ROOT"; ok "已删除配置目录 $ROOT"
  else
    rm -f "$ROOT/lib/aether.jar"
    [ -d "$ROOT/lib" ] && rmdir "$ROOT/lib" 2>/dev/null || true
    warn "已保留配置与信任记录: $ROOT（加 --purge 可一并删除）"
  fi
  # 顺手清理 shell rc 里的 PATH 注入
  for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
    if [ -f "$rc" ] && grep -q '>>> aether >>>' "$rc" 2>/dev/null; then
      tmp="$(mktemp)"
      sed '/# >>> aether >>>/,/# <<< aether <<</d' "$rc" > "$tmp" && mv "$tmp" "$rc"
      ok "已从 $rc 移除 PATH 配置"
    fi
  done
  printf '\n%s\n\n' "${GREEN}${BOLD}卸载完成。${RESET}"
  exit 0
fi

# ---------------------------------------------------------------- 环境检查
printf '\n%s\n' "${BOLD}${CYAN}================================================================${RESET}"
printf '%s\n'  "${BOLD}${CYAN}   Aether  ·  安装程序  v${VERSION}${RESET}"
printf '%s\n'  "${BOLD}${CYAN}================================================================${RESET}"

if ! command -v java >/dev/null 2>&1; then
  die "未找到 java 命令。请先安装 JDK/JRE 21+：https://adoptium.net/"
fi
RAW_VER="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s' "$RAW_VER" | sed -E 's/.*version "([0-9]+)(\.([0-9]+))?.*/\1/')"
if [ "$JAVA_MAJOR" = "1" ]; then
  JAVA_MAJOR="$(printf '%s' "$RAW_VER" | sed -E 's/.*version "1\.([0-9]+).*/\1/')"
fi
if [ "${JAVA_MAJOR:-0}" -lt 21 ] 2>/dev/null; then
  die "检测到 Java ${JAVA_MAJOR}，本程序需要 Java 21 或更高版本：https://adoptium.net/"
fi
ok "Java 环境正常（$(printf '%s' "$RAW_VER" | tr -d '\n')）"

# ---------------------------------------------------------------- 获取 jar
mkdir -p "$ROOT/lib"
TARGET_JAR="$ROOT/lib/aether.jar"
TMP_JAR="$(mktemp -t aether-XXXXXX.jar)"
trap 'rm -f "$TMP_JAR"' EXIT

fetch_jar() {
  if [ -n "$JAR_SRC" ]; then
    [ -f "$JAR_SRC" ] || die "指定的 jar 不存在: $JAR_SRC"
    cp "$JAR_SRC" "$TMP_JAR"
    info "使用本地 jar: $JAR_SRC"
    return 0
  fi
  if [ -f "target/aether.jar" ]; then
    cp "target/aether.jar" "$TMP_JAR"
    info "使用已构建产物: target/aether.jar"
    return 0
  fi
  if [ -f "pom.xml" ] && command -v mvn >/dev/null 2>&1; then
    info "检测到源码工程，正在用 Maven 构建（首次会下载依赖，请稍候）..."
    mvn -q -B clean package -DskipTests
    cp "target/aether.jar" "$TMP_JAR"
    ok "构建完成"
    return 0
  fi
  URL="https://github.com/$REPO/releases/latest/download/aether.jar"
  info "正在下载发行版: $URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --progress-bar "$URL" -o "$TMP_JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress "$URL" -O "$TMP_JAR"
  else
    die "需要 curl 或 wget 来下载发行版；也可以克隆仓库后在仓库目录内运行本脚本。"
  fi
  [ -s "$TMP_JAR" ] || die "下载失败，请检查网络后重试。"
}

fetch_jar
install -m 644 "$TMP_JAR" "$TARGET_JAR"
JAR_SIZE="$(wc -c < "$TARGET_JAR" | tr -d ' ')"
ok "程序本体已安装: $TARGET_JAR ($((JAR_SIZE / 1024 / 1024)) MB)"

# ---------------------------------------------------------------- 生成启动器
mkdir -p "$BIN_DIR"
LAUNCHER="$BIN_DIR/aether"
cat > "$LAUNCHER" <<LAUNCHER_EOF
#!/bin/sh
# aether launcher (generated by installer v${VERSION})
JAR="$TARGET_JAR"
if [ -z "\${AETHER_HOME:-}" ]; then AETHER_HOME="$ROOT"; export AETHER_HOME; fi
if [ -n "\${JAVA_HOME:-}" ] && [ -x "\$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="\$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi
if [ ! -f "\$JAR" ]; then
  echo "✖ 找不到 aether.jar：\$JAR" >&2
  echo "  请重新运行安装脚本：bash install.sh" >&2
  exit 127
fi
exec "\$JAVA_BIN" -jar "\$JAR" "\$@"
LAUNCHER_EOF
chmod 755 "$LAUNCHER"
ln -sf "$LAUNCHER" "$BIN_DIR/ae" 2>/dev/null || cp "$LAUNCHER" "$BIN_DIR/ae"
chmod 755 "$BIN_DIR/ae"
ok "启动器已创建: $LAUNCHER（别名: ae）"

# ---------------------------------------------------------------- PATH
add_to_path() {
  case ":$PATH:" in
    *":$BIN_DIR:"*) ok "PATH 中已包含 $BIN_DIR"; return 0 ;;
  esac
  if [ "$NO_PATH" = "1" ]; then
    warn "已跳过 PATH 配置（--no-path）"
    return 0
  fi
  SHELL_NAME="$(basename "${SHELL:-/bin/sh}")"
  case "$SHELL_NAME" in
    zsh)  RC="$HOME/.zshrc" ;;
    bash) RC="$HOME/.bashrc" ;;
    *)    RC="$HOME/.profile" ;;
  esac
  [ -f "$RC" ] || : > "$RC"
  if ! grep -q '>>> aether >>>' "$RC" 2>/dev/null; then
    {
      printf '\n# >>> aether >>>\n'
      printf 'export PATH="%s:$PATH"\n' "$BIN_DIR"
      printf '# <<< aether <<<\n'
    } >> "$RC"
    ok "已把 $BIN_DIR 写入 $RC"
  fi
  info "当前终端生效请执行: export PATH=\"$BIN_DIR:\$PATH\""
}
add_to_path

# ---------------------------------------------------------------- 自检
printf '\n'
if "$LAUNCHER" --version >/dev/null 2>&1; then
  ok "自检通过：$("$LAUNCHER" --version | head -n 1)"
else
  warn "自检未能运行，请手动执行 \"$LAUNCHER\" --version 查看错误"
fi

# 旧版（github-agent）残留提示：不自动删除，避免误伤用户的旧配置
LEGACY_BIN="$HOME/.github-agent/bin"
if [ -d "$LEGACY_BIN" ]; then
  warn "检测到旧版仍在 $LEGACY_BIN（命令 github-agent / gha）。确认新版可用后可手动删除该目录，避免两个命令并存。"
fi

cat <<DONE

${GREEN}${BOLD}安装完成！${RESET}

  ${BOLD}下一步：${RESET}
    ${CYAN}cd${RESET} 你的项目目录
    ${CYAN}aether${RESET}        # 启动（首次会询问是否允许访问该工作区，并选择权限模式）

  ${DIM}常用参数:${RESET}
    aether ~/code/proj          # 指定工作区
    aether -p "重构这个模块"     # 一次性任务
    aether --doctor             # 环境自检
    aether --help               # 全部参数
    aether --forget             # 撤销当前目录授权

  ${DIM}配置文件: $ROOT/config.properties${RESET}
  ${DIM}信任记录: $ROOT/trusted-dirs.json${RESET}
  ${DIM}卸载: bash install.sh --uninstall [--purge]${RESET}

DONE

package com.ai.github.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地文件系统与代码精修工具集（对标 Autonomous Agent / Codex 的 Read, Write, Edit, Glob, Grep 工具）。
 *
 * 包含安全沙箱边界检查与：
 * 1. readFile    - 带行号读取文件（支持指定起止行 startLine ~ endLine）
 * 2. writeFile   - 创建或覆写文件（自动创建父目录）
 * 3. editFile    - 精确代码块替换（Surgical Search & Replace，避免整文件重写破坏上下文）
 * 4. listDir     - 树形结构展示目录内容（自动过滤 .git / target / node_modules 噪音）
 * 5. searchFiles - 正则表达式/关键字全文检索（Grep）与文件名匹配
 * 6. deleteFile  - 删除指定文件或空目录
 */
public class FileSystemTools {

    private Path workspaceRoot;
    /**
     * 是否把文件操作限制在工作区内（启动器默认开启，配合 TrustGate 的授权结果使用）。
     * 开启后：相对路径一律基于工作区解析，工作区之外的写入/删除会被拒绝。
     */
    private boolean enforceWorkspace = false;
    /** 允许越界访问的显式开关（--allow-outside 或 /trust outside on）。 */
    private boolean allowOutside = false;

    public FileSystemTools(String workspaceDir) {
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    public void setWorkspaceRoot(String workspaceDir) {
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setEnforceWorkspace(boolean enforce) {
        this.enforceWorkspace = enforce;
    }

    public boolean isEnforceWorkspace() {
        return enforceWorkspace;
    }

    public void setAllowOutside(boolean allow) {
        this.allowOutside = allow;
    }

    public boolean isAllowOutside() {
        return allowOutside;
    }

    /**
     * 判断路径是否位于工作区内部。
     */
    public boolean isInsideWorkspace(Path p) {
        return p.toAbsolutePath().normalize().startsWith(workspaceRoot);
    }

    /**
     * 写入/删除前的沙箱边界校验。
     */
    private void assertWritable(Path target) {
        if (!enforceWorkspace || allowOutside) {
            return;
        }
        if (isInsideWorkspace(target)) {
            return;
        }
        throw new SecurityException("拒绝访问工作区之外的路径: " + target
                + "\n当前工作区: " + workspaceRoot
                + "\n如确需访问该路径，请用 `--allow-outside` 重新启动，或在会话中输入 `/trust outside on` 显式放行。");
    }

    /**
     * 解析路径（相对路径基于工作区；启用沙箱时不再回退到当前目录）。
     */
    public Path resolvePath(String inputPath) {
        if (inputPath == null || inputPath.isBlank() || inputPath.equals(".")) {
            return workspaceRoot;
        }
        Path p = Path.of(inputPath);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        // 启用工作区沙箱时：相对路径一律落在工作区内，避免歧义
        if (enforceWorkspace) {
            return workspaceRoot.resolve(p).normalize();
        }
        // 兼容旧行为：工作区下存在则优先，其次尝试当前进程目录
        Path candidateInWs = workspaceRoot.resolve(p).normalize();
        if (Files.exists(candidateInWs)) {
            return candidateInWs;
        }
        Path candidateInCwd = Path.of("").toAbsolutePath().resolve(p).normalize();
        if (Files.exists(candidateInCwd)) {
            return candidateInCwd;
        }
        return candidateInWs;
    }

    /**
     * 读取文件内容并附带 1-indexed 行号（仿 `cat -n` / Autonomous Agent Read 格式）。
     */
    public String readFile(String pathStr, int startLine, int endLine) throws IOException {
        Path path = resolvePath(pathStr);
        if (!Files.exists(path)) {
            return "错误: 文件不存在 -> " + path;
        }
        if (Files.isDirectory(path)) {
            return "错误: 目标路径是一个目录而非文件 -> " + path + " (请改用 list_dir 工具)";
        }
        long size = Files.size(path);
        if (size > 2 * 1024 * 1024) {
            return "错误: 文件过大 (" + (size / 1024) + " KB)，请指定行范围读取或使用 search_files 搜索。";
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int total = lines.size();
        int from = Math.max(1, startLine <= 0 ? 1 : startLine);
        int to = (endLine <= 0 || endLine > total) ? Math.min(total, from + 499) : endLine;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("File: %s (Lines %d-%d of %d)%n", path, from, Math.min(to, total), total));
        sb.append("─".repeat(64)).append("\n");
        for (int i = from; i <= Math.min(to, total); i++) {
            sb.append(String.format("%4d │ %s%n", i, lines.get(i - 1)));
        }
        if (to < total) {
            sb.append(String.format("... [还有 %d 行未显示，可通过 start_line=%d 继续读取] ...%n", total - to, to + 1));
        }
        return sb.toString();
    }

    /**
     * 读取原始纯文本（供 Diff 对比与 Edit 替换使用）。
     */
    public String readRawContentIfExists(String pathStr) {
        try {
            Path path = resolvePath(pathStr);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 写入或覆写文件。
     */
    public String writeFile(String pathStr, String content) throws IOException {
        Path path = resolvePath(pathStr);
        assertWritable(path);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        int lineCount = (content == null || content.isEmpty()) ? 0 : content.split("\r?\n", -1).length;
        return String.format("成功写入文件 %s (%d 行, %d 字节)", path, lineCount,
                content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 精确替换文件中的指定代码块（Surgical Edit）。
     */
    public String editFile(String pathStr, String oldText, String newText, boolean replaceAll) throws IOException {
        Path path = resolvePath(pathStr);
        assertWritable(path);
        if (!Files.exists(path)) {
            return "错误: 目标文件不存在 -> " + path;
        }
        String current = Files.readString(path, StandardCharsets.UTF_8);
        if (oldText == null || oldText.isEmpty()) {
            return "错误: edit_file 的 old_text 不能为空。";
        }

        // 兼容 CRLF 与 LF 换行符差异
        String normalizedCurrent = current.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");
        String normalizedNew = (newText == null ? "" : newText).replace("\r\n", "\n");

        if (!normalizedCurrent.contains(normalizedOld)) {
            return "错误: 在文件 " + path + " 中未找到完全匹配的 `old_text` 片段。请先调用 `read_file` 确认最新内容与缩进后再重试。";
        }

        // 检查唯一性
        int firstIdx = normalizedCurrent.indexOf(normalizedOld);
        int secondIdx = normalizedCurrent.indexOf(normalizedOld, firstIdx + normalizedOld.length());
        if (secondIdx != -1 && !replaceAll) {
            return "错误: `old_text` 在文件中出现了多次，存在歧义。请包含更多上下文行使其唯一，或将 `replace_all` 设为 true。";
        }

        String updated = replaceAll
                ? normalizedCurrent.replace(normalizedOld, normalizedNew)
                : normalizedCurrent.substring(0, firstIdx) + normalizedNew + normalizedCurrent.substring(firstIdx + normalizedOld.length());

        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return "成功精确更新文件: " + path;
    }

    /**
     * 预览 editFile 之后的完整新文本（用于审批前渲染彩色 Diff）。
     */
    public String previewEditedContent(String pathStr, String oldText, String newText, boolean replaceAll) {
        String current = readRawContentIfExists(pathStr).replace("\r\n", "\n");
        String normOld = (oldText == null ? "" : oldText).replace("\r\n", "\n");
        String normNew = (newText == null ? "" : newText).replace("\r\n", "\n");
        if (current.isEmpty() || normOld.isEmpty() || !current.contains(normOld)) {
            return current;
        }
        if (replaceAll) {
            return current.replace(normOld, normNew);
        }
        int idx = current.indexOf(normOld);
        return current.substring(0, idx) + normNew + current.substring(idx + normOld.length());
    }

    /**
     * 生成目录树结构视图。
     */
    public String listDirectoryTree(String pathStr, int maxDepth) throws IOException {
        Path start = resolvePath(pathStr);
        if (!Files.exists(start)) {
            return "目录不存在: " + start;
        }
        int depthLimit = maxDepth <= 0 ? 3 : Math.min(maxDepth, 6);
        StringBuilder sb = new StringBuilder();
        sb.append("📂 ").append(start.toAbsolutePath()).append("\n");
        buildTreeRecursive(start, "", 0, depthLimit, sb);
        return sb.toString().trim();
    }

    private void buildTreeRecursive(Path dir, String prefix, int depth, int maxDepth, StringBuilder sb) throws IOException {
        if (depth >= maxDepth) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals(".git") && !name.equals("node_modules") && !name.equals("target") && !name.equals(".idea");
                    })
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        }

        for (int i = 0; i < entries.size(); i++) {
            Path entry = entries.get(i);
            boolean last = (i == entries.size() - 1);
            String connector = last ? "└── " : "├── ";
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                sb.append(prefix).append(connector).append("📁 ").append(name).append("/\n");
                buildTreeRecursive(entry, prefix + (last ? "    " : "│   "), depth + 1, maxDepth, sb);
            } else {
                long bytes = Files.size(entry);
                sb.append(prefix).append(connector).append("📄 ").append(name)
                        .append(" (").append(formatSize(bytes)).append(")\n");
            }
        }
    }

    /**
     * 在目录中执行正则或关键字全文检索（Grep）及文件名过滤。
     */
    public String searchFiles(String dirStr, String queryRegex, String fileNameGlob) throws IOException {
        Path root = resolvePath(dirStr);
        if (!Files.exists(root)) {
            return "搜索目录不存在: " + root;
        }
        Pattern contentPattern = (queryRegex == null || queryRegex.isBlank())
                ? null
                : Pattern.compile(queryRegex, Pattern.CASE_INSENSITIVE);
        String globRegex = (fileNameGlob == null || fileNameGlob.isBlank())
                ? ".*"
                : fileNameGlob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        Pattern filePattern = Pattern.compile(globRegex, Pattern.CASE_INSENSITIVE);

        List<String> matches = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matches.size() >= 80) {
                    return FileVisitResult.TERMINATE;
                }
                String fileName = file.getFileName().toString();
                if (!filePattern.matcher(fileName).matches()) {
                    return FileVisitResult.CONTINUE;
                }
                if (contentPattern == null) {
                    matches.add("FILE: " + root.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > 1024 * 1024) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        Matcher m = contentPattern.matcher(lines.get(i));
                        if (m.find()) {
                            matches.add(String.format("%s:%d: %s", root.relativize(file), i + 1, lines.get(i).trim()));
                            if (matches.size() >= 80) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (matches.isEmpty()) {
            return "未找到匹配项 (query=" + queryRegex + ", glob=" + fileNameGlob + ")";
        }
        return "找到 " + matches.size() + " 处匹配:\n" + String.join("\n", matches);
    }

    public String deleteFile(String pathStr) throws IOException {
        Path path = resolvePath(pathStr);
        assertWritable(path);
        if (!Files.exists(path)) {
            return "目标文件不存在: " + path;
        }
        Files.delete(path);
        return "已删除: " + path;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}

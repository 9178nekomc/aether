package com.aether.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 模块化技能包管理器（SkillManager）。
 *
 * 功能特性：
 * 1. 自动扫描并加载本地 `skills/` 目录下的技能文件（支持 `.md` Markdown 技能包与 YAML Frontmatter）。
 * 2. 首次启动自动初始化 4 个实用内置工程技能包：
 *    - `code-review`       : 深度代码审查、并发安全与性能瓶颈诊断
 *    - `git-commit-craft`  : 语义化版本提交与变更日志（Changelog）生成规范
 *    - `java-architect`    : Java / Spring Boot 工程脚手架与重构指南
 *    - `security-audit`    : 密钥泄露、注入风险与依赖漏洞安全扫描规范
 * 3. 支持通过 `/skills` 查看、启用/禁用、新建自定义技能，同时向 Agent 暴露 `list_skills` 与 `load_skill` 工具。
 */
public class SkillManager {

    public static class Skill {
        public String name;
        public String description;
        public String content;
        public Path filePath;
        public boolean enabled = true;

        public Skill(String name, String description, String content, Path filePath) {
            this.name = name;
            this.description = description;
            this.content = content;
            this.filePath = filePath;
        }
    }

    private final Path skillsDir;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillManager(String skillsDirPath) {
        this.skillsDir = Paths.get(skillsDirPath == null || skillsDirPath.isBlank() ? "skills" : skillsDirPath)
                .toAbsolutePath().normalize();
        initDefaultSkillsIfEmpty();
        reloadSkills();
    }

    private void initDefaultSkillsIfEmpty() {
        try {
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }
            try (Stream<Path> s = Files.list(skillsDir)) {
                if (s.findAny().isPresent()) {
                    return;
                }
            }
            writeDefaultSkill("code-review", "Systematic code review for correctness, edge cases, concurrency, and maintainability.",
                    """
                    # Code Review Skill
                    1. Inspect modified or target files with `read_file` before commenting.
                    2. Check null safety, resource cleanup (try-with-resources), error handling, and boundary conditions.
                    3. Provide concrete file:line references and minimal surgical fixes via `edit_file`.
                    """);

            writeDefaultSkill("git-commit-craft", "Craft clean Conventional Commits and structured release changelogs using built-in JGit.",
                    """
                    # Git Commit & Changelog Skill
                    1. Run `git_command` with `status -s` and `diff` to inspect all staged and unstaged changes.
                    2. Group changes logically and format commit messages as `<type>(<scope>): <concise summary>` (`feat`, `fix`, `refactor`, `perf`, `docs`, `test`).
                    3. Never include secrets, `.env`, or build artifacts in commits.
                    """);

            writeDefaultSkill("java-architect", "Best practices for scaffolding and refactoring modern Java 21 and Maven applications.",
                    """
                    # Java 21 Architecture Skill
                    1. Use modern Java 21 features (pattern matching switch, records, text blocks, virtual threads where appropriate).
                    2. Keep `pom.xml` dependencies minimal and pinned to stable versions.
                    3. Ensure every public class has clear Javadoc and deterministic error handling.
                    """);

            writeDefaultSkill("security-audit", "Audit source code for hardcoded credentials, path traversal, and injection vulnerabilities.",
                    """
                    # Security Audit Skill
                    1. Use `search_files` to scan for hardcoded tokens, API keys, SQL concatenation, or unsafe shell execution.
                    2. Verify path normalization (`Path.normalize()` + prefix checks) on all file operations.
                    3. Remediate vulnerabilities with minimal breaking changes.
                    """);
        } catch (IOException ignored) {
        }
    }

    private void writeDefaultSkill(String name, String description, String body) throws IOException {
        Path file = skillsDir.resolve(name + ".md");
        String content = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body.trim() + "\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    public final void reloadSkills() {
        skills.clear();
        if (!Files.exists(skillsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(skillsDir)) {
            List<Path> files = stream
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted()
                    .toList();
            for (Path f : files) {
                parseSkillFile(f);
            }
        } catch (IOException ignored) {
        }
    }

    private void parseSkillFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String filename = file.getFileName().toString();
            String name = filename.replaceFirst("\\.(md|txt)$", "");
            String description = "Custom skill loaded from " + filename;
            String body = raw;

            if (raw.startsWith("---")) {
                int endFront = raw.indexOf("---", 3);
                if (endFront != -1) {
                    String front = raw.substring(3, endFront).trim();
                    body = raw.substring(endFront + 3).trim();
                    for (String line : front.split("\r?\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String k = line.substring(0, colon).trim().toLowerCase();
                            String v = line.substring(colon + 1).trim();
                            if (k.equals("name") && !v.isEmpty()) name = v;
                            if (k.equals("description") && !v.isEmpty()) description = v;
                        }
                    }
                }
            }
            skills.put(name.toLowerCase(), new Skill(name, description, body, file));
        } catch (IOException ignored) {
        }
    }

    public Map<String, Skill> getAllSkills() {
        return skills;
    }

    public Skill getSkill(String name) {
        if (name == null) return null;
        return skills.get(name.trim().toLowerCase());
    }

    public boolean toggleSkill(String name) {
        Skill s = getSkill(name);
        if (s == null) return false;
        s.enabled = !s.enabled;
        return true;
    }

    public Skill createSkill(String name, String description, String instructions) throws IOException {
        String cleanName = name.trim().toLowerCase().replaceAll("[^a-z0-9\\-_]", "-");
        writeDefaultSkill(cleanName, description, instructions);
        reloadSkills();
        return getSkill(cleanName);
    }

    /**
     * 生成紧凑的英文技能索引（注入 System Prompt，仅占极少 Token）。
     */
    public String buildCompactSkillIndexForPrompt() {
        List<Skill> active = skills.values().stream().filter(s -> s.enabled).toList();
        if (active.isEmpty()) {
            return "No active skills.";
        }
        StringBuilder sb = new StringBuilder();
        for (Skill s : active) {
            sb.append("- `").append(s.name).append("`: ").append(s.description).append("\n");
        }
        return sb.toString().trim();
    }

    public String loadSkillContentForTool(String name) {
        Skill s = getSkill(name);
        if (s == null) {
            return "Skill not found: `" + name + "`. Available skills: " + String.join(", ", skills.keySet());
        }
        if (!s.enabled) {
            return "Skill `" + name + "` is currently disabled.";
        }
        return "=== SKILL: " + s.name + " ===\nDescription: " + s.description + "\n\n" + s.content;
    }

    public Path getSkillsDir() {
        return skillsDir;
    }
}

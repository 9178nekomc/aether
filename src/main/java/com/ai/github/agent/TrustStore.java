package com.ai.github.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工作区信任记录存储（TrustStore）。
 *
 * 对标 Claude Code 的 "Do you trust the files in this folder?" 机制：
 * 每个被授权的目录会连同「权限模式」一起记录在 ~/.github-agent/trusted-dirs.json 中，
 * 之后在该目录再次启动时不再重复询问；换到新目录则会重新询问一次。
 *
 * 文件格式（JSON）：
 * {
 *   "version": 1,
 *   "dirs": {
 *     "/home/me/code/proj": { "trustedAt": "2026-11-01T12:00:00Z", "mode": "safe", "appVersion": "2.1.0" }
 *   }
 * }
 */
public class TrustStore {

    /** 权限模式：SAFE=写操作前确认；STRICT=所有工具都确认；YOLO=全自动。 */
    public enum PermissionMode {
        SAFE("safe", "只读自动放行，写入/变更前弹 Diff 询问确认（推荐）"),
        STRICT("strict", "所有工具调用（含只读）都需逐次确认（最谨慎）"),
        YOLO("yolo", "全自动执行，不再询问（高危，请仅在可信仓库使用）");

        public final String id;
        public final String description;

        PermissionMode(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public static PermissionMode fromId(String raw) {
            if (raw == null) {
                return SAFE;
            }
            String s = raw.trim().toLowerCase(Locale.ROOT);
            for (PermissionMode m : values()) {
                if (m.id.equals(s)) {
                    return m;
                }
            }
            return SAFE;
        }
    }

    public static class Entry {
        public String dir;
        public String trustedAt;
        public String mode;
        public String appVersion;
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode root;

    public TrustStore() {
        this(AppInfo.trustFile());
    }

    public TrustStore(Path file) {
        this.file = file;
        this.root = load();
    }

    private ObjectNode load() {
        ObjectNode empty = mapper.createObjectNode();
        empty.put("version", 1);
        empty.set("dirs", mapper.createObjectNode());
        try {
            if (!Files.exists(file)) {
                return empty;
            }
            JsonNode parsed = mapper.readTree(Files.readString(file));
            if (parsed == null || !parsed.isObject()) {
                return empty;
            }
            ObjectNode obj = (ObjectNode) parsed;
            if (!obj.path("dirs").isObject()) {
                obj.set("dirs", mapper.createObjectNode());
            }
            if (!obj.has("version")) {
                obj.put("version", 1);
            }
            return obj;
        } catch (Exception ignored) {
            return empty;
        }
    }

    /**
     * 目录键：统一用绝对规范化路径；Windows 下大小写不敏感，统一转小写。
     */
    private static String key(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        String s = abs.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    public boolean isTrusted(Path dir) {
        return root.path("dirs").has(key(dir));
    }

    public PermissionMode modeFor(Path dir) {
        JsonNode node = root.path("dirs").get(key(dir));
        if (node == null) {
            return null;
        }
        return PermissionMode.fromId(node.path("mode").asText("safe"));
    }

    public Entry entryFor(Path dir) {
        JsonNode node = root.path("dirs").get(key(dir));
        if (node == null) {
            return null;
        }
        Entry e = new Entry();
        e.dir = dir.toAbsolutePath().normalize().toString();
        e.trustedAt = node.path("trustedAt").asText("");
        e.mode = node.path("mode").asText("safe");
        e.appVersion = node.path("appVersion").asText("");
        return e;
    }

    public void trust(Path dir, PermissionMode mode) {
        Path abs = dir.toAbsolutePath().normalize();
        ObjectNode dirs = (ObjectNode) root.path("dirs");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("path", abs.toString());
        entry.put("trustedAt", java.time.Instant.now().toString());
        entry.put("mode", (mode == null ? PermissionMode.SAFE : mode).id);
        entry.put("appVersion", AppInfo.version());
        dirs.set(key(abs), entry);
        save();
    }

    public boolean revoke(Path dir) {
        ObjectNode dirs = (ObjectNode) root.path("dirs");
        if (dirs.remove(key(dir)) != null) {
            save();
            return true;
        }
        return false;
    }

    public List<Entry> listTrusted() {
        List<Entry> list = new ArrayList<>();
        JsonNode dirs = root.path("dirs");
        Iterator<String> names = dirs.fieldNames();
        while (names.hasNext()) {
            String k = names.next();
            JsonNode node = dirs.get(k);
            Entry e = new Entry();
            e.dir = node.path("path").asText(k);
            e.trustedAt = node.path("trustedAt").asText("");
            e.mode = node.path("mode").asText("safe");
            e.appVersion = node.path("appVersion").asText("");
            list.add(e);
        }
        return list;
    }

    public Path getFile() {
        return file;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            restrictPermissions(file);
        } catch (IOException ignored) {
        }
    }

    /**
     * 在 POSIX 系统上把含 Token / 信任记录的配置文件权限收紧到 600，避免同机其他用户读取。
     */
    public static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            // Windows 或不支持 POSIX 权限的文件系统：忽略
        }
    }
}

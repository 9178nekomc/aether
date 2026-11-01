package com.aether.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP (Model Context Protocol) 客户端管理器（McpClientManager）。
 *
 * 支持标准 `mcp.json` 格式：
 * 1. Stdio 传输协议：启动本地 MCP Server 子进程，通过 JSON-RPC 2.0 (`initialize` -> `tools/list` -> `tools/call`) 交互。
 * 2. HTTP JSON-RPC 传输协议：向远程 MCP Server 发送标准 JSON-RPC 2.0 POST 请求。
 * 3. 支持通过 `/mcp` 命令查看、测试、添加或移除 MCP Server，并向 Agent 提供 `mcp_list_tools` 与 `mcp_call_tool` 工具。
 */
public class McpClientManager {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static class McpServerConfig {
        public String name;
        public String transport = "stdio"; // "stdio" or "http"
        public String command = "";
        public List<String> args = new ArrayList<>();
        public String url = "";
        public boolean enabled = true;
    }

    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();
    private final Map<String, McpServerConfig> servers = new LinkedHashMap<>();

    public McpClientManager(String mcpConfigFilePath) {
        this.configPath = Paths.get(mcpConfigFilePath == null || mcpConfigFilePath.isBlank() ? "mcp.json" : mcpConfigFilePath)
                .toAbsolutePath().normalize();
        loadOrInitConfig();
    }

    public final void loadOrInitConfig() {
        servers.clear();
        try {
            if (!Files.exists(configPath)) {
                ObjectNode root = mapper.createObjectNode();
                ObjectNode mcpServers = root.putObject("mcpServers");

                // 预置一个示例配置（默认禁用，方便用户参考或一键启用）
                ObjectNode fsDemo = mcpServers.putObject("filesystem-demo");
                fsDemo.put("transport", "stdio");
                fsDemo.put("command", "npx");
                ArrayNode args = fsDemo.putArray("args");
                args.add("-y");
                args.add("@modelcontextprotocol/server-filesystem");
                args.add(".");
                fsDemo.put("enabled", false);

                Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            }

            JsonNode root = mapper.readTree(Files.readString(configPath, StandardCharsets.UTF_8));
            JsonNode mcpServers = root.path("mcpServers");
            if (mcpServers.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = mcpServers.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String name = entry.getKey();
                    JsonNode node = entry.getValue();
                    McpServerConfig cfg = new McpServerConfig();
                    cfg.name = name;
                    cfg.url = node.path("url").asText("");
                    cfg.command = node.path("command").asText("");
                    cfg.transport = !cfg.url.isBlank() ? "http" : node.path("transport").asText("stdio");
                    cfg.enabled = node.path("enabled").asBoolean(true);
                    JsonNode argsNode = node.get("args");
                    if (argsNode != null && argsNode.isArray()) {
                        for (JsonNode a : argsNode) {
                            cfg.args.add(a.asText());
                        }
                    }
                    servers.put(name, cfg);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void saveConfig() throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode mcpServers = root.putObject("mcpServers");
        for (McpServerConfig s : servers.values()) {
            ObjectNode node = mcpServers.putObject(s.name);
            node.put("transport", s.transport);
            if (!s.command.isBlank()) node.put("command", s.command);
            if (!s.args.isEmpty()) {
                ArrayNode arr = node.putArray("args");
                s.args.forEach(arr::add);
            }
            if (!s.url.isBlank()) node.put("url", s.url);
            node.put("enabled", s.enabled);
        }
        Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    public Map<String, McpServerConfig> getServers() {
        return servers;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public void addHttpServer(String name, String url) throws IOException {
        McpServerConfig cfg = new McpServerConfig();
        cfg.name = name;
        cfg.transport = "http";
        cfg.url = url;
        cfg.enabled = true;
        servers.put(name, cfg);
        saveConfig();
    }

    public void addStdioServer(String name, String command, List<String> args) throws IOException {
        McpServerConfig cfg = new McpServerConfig();
        cfg.name = name;
        cfg.transport = "stdio";
        cfg.command = command;
        cfg.args = new ArrayList<>(args);
        cfg.enabled = true;
        servers.put(name, cfg);
        saveConfig();
    }

    public boolean toggleServer(String name) throws IOException {
        McpServerConfig cfg = servers.get(name);
        if (cfg == null) return false;
        cfg.enabled = !cfg.enabled;
        saveConfig();
        return true;
    }

    public boolean removeServer(String name) throws IOException {
        if (servers.remove(name) != null) {
            saveConfig();
            return true;
        }
        return false;
    }

    /**
     * 生成紧凑的英文 MCP 状态摘要供 System Prompt 使用。
     */
    public String buildCompactMcpSummaryForPrompt() {
        List<McpServerConfig> active = servers.values().stream().filter(s -> s.enabled).toList();
        if (active.isEmpty()) {
            return "No active MCP servers (configurable via /mcp or mcp.json).";
        }
        StringBuilder sb = new StringBuilder();
        for (McpServerConfig s : active) {
            sb.append("- `").append(s.name).append("` (").append(s.transport).append(")\n");
        }
        return sb.toString().trim();
    }

    /**
     * 查询指定（或全部已启用）MCP Server 暴露的工具列表（JSON-RPC 2.0 `tools/list`）。
     */
    public String listToolsFromServer(String serverName) {
        if (serverName == null || serverName.isBlank() || serverName.equalsIgnoreCase("all")) {
            List<String> out = new ArrayList<>();
            for (McpServerConfig s : servers.values()) {
                if (s.enabled) {
                    out.add("=== MCP Server: " + s.name + " ===\n" + querySingleServerTools(s));
                }
            }
            return out.isEmpty() ? "当前没有已启用的 MCP Server。可通过 `/mcp` 命令启用或添加。" : String.join("\n\n", out);
        }
        McpServerConfig s = servers.get(serverName);
        if (s == null) {
            return "未找到 MCP Server: " + serverName;
        }
        return querySingleServerTools(s);
    }

    private String querySingleServerTools(McpServerConfig s) {
        try {
            ObjectNode params = mapper.createObjectNode();
            JsonNode res = sendJsonRpc(s, "tools/list", params);
            return res.toPrettyString();
        } catch (Exception e) {
            return "MCP Server `" + s.name + "` 连接失败: " + e.getMessage();
        }
    }

    /**
     * 调用指定 MCP Server 上的工具（JSON-RPC 2.0 `tools/call`）。
     */
    public String callMcpTool(String serverName, String toolName, JsonNode arguments) throws Exception {
        McpServerConfig s = servers.get(serverName);
        if (s == null) {
            throw new IOException("MCP Server not found: " + serverName);
        }
        if (!s.enabled) {
            throw new IOException("MCP Server `" + serverName + "` is disabled.");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments != null && arguments.isObject() ? arguments : mapper.createObjectNode());

        JsonNode response = sendJsonRpc(s, "tools/call", params);
        return response.toPrettyString();
    }

    private JsonNode sendJsonRpc(McpServerConfig server, String method, ObjectNode params) throws Exception {
        if ("http".equalsIgnoreCase(server.transport) || !server.url.isBlank()) {
            ObjectNode rpc = mapper.createObjectNode();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", System.currentTimeMillis());
            rpc.put("method", method);
            rpc.set("params", params);

            Request req = new Request.Builder()
                    .url(server.url)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(rpc), JSON))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                return mapper.readTree(body);
            }
        }

        // Stdio JSON-RPC 2.0 握手与请求
        List<String> cmdList = new ArrayList<>();
        cmdList.add(server.command);
        cmdList.addAll(server.args);
        ProcessBuilder pb = new ProcessBuilder(cmdList);
        Process proc = pb.start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {

            // 1. 发送 initialize
            ObjectNode initReq = mapper.createObjectNode();
            initReq.put("jsonrpc", "2.0");
            initReq.put("id", 1);
            initReq.put("method", "initialize");
            ObjectNode initParams = initReq.putObject("params");
            initParams.put("protocolVersion", "2024-11-05");
            initParams.putObject("capabilities");
            ObjectNode clientInfo = initParams.putObject("clientInfo");
            clientInfo.put("name", "aether");
            clientInfo.put("version", "3.0.0");

            writer.write(mapper.writeValueAsString(initReq) + "\n");
            writer.flush();
            reader.readLine(); // 读取 initialize 响应

            // 2. 发送 notifications/initialized
            ObjectNode notif = mapper.createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/initialized");
            writer.write(mapper.writeValueAsString(notif) + "\n");
            writer.flush();

            // 3. 发送目标请求 (tools/list 或 tools/call)
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", 2);
            req.put("method", method);
            req.set("params", params);
            writer.write(mapper.writeValueAsString(req) + "\n");
            writer.flush();

            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                throw new IOException("No JSON-RPC response from stdio MCP server.");
            }
            return mapper.readTree(line);
        } finally {
            proc.destroyForcibly();
        }
    }
}

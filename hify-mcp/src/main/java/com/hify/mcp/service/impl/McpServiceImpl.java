package com.hify.mcp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.common.exception.BizException;
import com.hify.common.exception.ErrorCode;
import com.hify.mcp.client.DirectMcpClient;
import com.hify.mcp.dto.*;
import com.hify.mcp.entity.McpServer;
import com.hify.mcp.mapper.McpServerMapper;
import com.hify.mcp.service.McpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServiceImpl implements McpService {

    private final McpServerMapper mcpServerMapper;
    private final ObjectMapper objectMapper;

    // ── CRUD ────────────────────────────────────────────────────

    @Override
    public McpServerVO create(McpServerCreateRequest request) {
        checkNameUnique(request.getName(), null);
        McpServer server = new McpServer();
        server.setName(request.getName());
        server.setEndpoint(request.getEndpoint());
        server.setDescription(request.getDescription() != null ? request.getDescription() : "");
        server.setEnabled(1);
        mcpServerMapper.insert(server);
        return McpServerVO.from(server);
    }

    @Override
    public Result<PageResult<McpServerVO>> list(McpQueryRequest request) {
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<McpServer>()
                .eq(request.getEnabled() != null, McpServer::getEnabled, request.getEnabled())
                .orderByDesc(McpServer::getCreatedAt);
        int size = Math.min(request.getPageSize(), 100);
        var p = mcpServerMapper.selectPage(new Page<>(request.getPage(), size), wrapper);
        List<McpServerVO> items = p.getRecords().stream().map(McpServerVO::from).collect(Collectors.toList());
        return PageResult.of(items, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    @Override
    public McpServerVO getDetail(Long id) {
        return McpServerVO.from(getOrThrow(id));
    }

    @Override
    public McpServerVO update(Long id, McpServerUpdateRequest request) {
        McpServer server = getOrThrow(id);
        checkNameUnique(request.getName(), id);
        server.setName(request.getName());
        server.setEndpoint(request.getEndpoint());
        if (request.getDescription() != null) server.setDescription(request.getDescription());
        if (request.getEnabled() != null) server.setEnabled(request.getEnabled());
        mcpServerMapper.updateById(server);
        return McpServerVO.from(server);
    }

    @Override
    public void delete(Long id) {
        getOrThrow(id);
        mcpServerMapper.deleteById(id);
    }

    // ── 连通性测试 ────────────────────────────────────────────────

    @Override
    public McpTestResult testConnection(Long id) {
        McpServer server = getOrThrow(id);
        long start = System.currentTimeMillis();
        try {
            log.info("MCP 连通测试开始: server={}, endpoint={}", server.getName(), server.getEndpoint());
            List<String> tools = listToolsFromEndpoint(server.getEndpoint());
            int latencyMs = (int) (System.currentTimeMillis() - start);
            log.info("MCP 连通测试成功: server={}, tools={}, latency={}ms", server.getName(), tools.size(), latencyMs);
            return McpTestResult.ok(latencyMs, tools);
        } catch (Exception e) {
            int latencyMs = (int) (System.currentTimeMillis() - start);
            log.warn("MCP 连通测试失败: server={}, endpoint={}, error={}, latency={}ms",
                    server.getName(), server.getEndpoint(), e.getMessage(), latencyMs);
            return McpTestResult.fail(e.getMessage());
        }
    }

    // ── 工具调用 ─────────────────────────────────────────────────

    @Override
    public String callTool(Long mcpServerId, String toolName, Map<String, Object> arguments) {
        McpServer server = getOrThrow(mcpServerId);
        log.info("MCP callTool server={} tool={} args={}", server.getName(), toolName, arguments);
        try (DirectMcpClient client = new DirectMcpClient(server.getEndpoint())) {
            client.initialize();
            return client.callTool(toolName, arguments);
        } catch (Exception e) {
            log.error("MCP callTool failed server={} tool={}: {}", server.getName(), toolName, e.getMessage());
            throw new BizException(ErrorCode.MCP_TOOL_CALL_FAILED, "工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public List<String> listTools(Long mcpServerId) {
        McpServer server = getOrThrow(mcpServerId);
        return listToolsFromEndpoint(server.getEndpoint());
    }

    // ── 工具详情 ─────────────────────────────────────────────────

    @Override
    public List<McpToolDetail> listToolsDetail(Long mcpServerId) {
        McpServer server = getOrThrow(mcpServerId);
        try (DirectMcpClient client = new DirectMcpClient(server.getEndpoint())) {
            client.initialize();
            List<Map<String, Object>> details = client.listToolDetails();
            return details.stream().map(d -> {
                McpToolDetail td = new McpToolDetail();
                td.setName((String) d.get("name"));
                td.setDescription((String) d.getOrDefault("description", ""));
                if (d.containsKey("inputSchema")) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> schema = objectMapper.convertValue(d.get("inputSchema"), Map.class);
                        td.setInputSchema(schema);
                        Object required = schema.get("required");
                        if (required instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> req = (List<String>) required;
                            td.setRequiredParams(req);
                        }
                    } catch (Exception ignored) {}
                }
                return td;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("MCP listToolsDetail failed server={}: {}", server.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 调试调用 ─────────────────────────────────────────────────

    @Override
    public McpDebugResult debugTool(Long mcpServerId, McpDebugRequest request) {
        long start = System.currentTimeMillis();
        try {
            String result = callTool(mcpServerId, request.getToolName(),
                    request.getArguments() != null ? request.getArguments() : Collections.emptyMap());
            int elapsedMs = (int) (System.currentTimeMillis() - start);
            return McpDebugResult.ok(result, elapsedMs);
        } catch (BizException e) {
            int elapsedMs = (int) (System.currentTimeMillis() - start);
            return McpDebugResult.fail(e.getMessage(), elapsedMs);
        } catch (Exception e) {
            int elapsedMs = (int) (System.currentTimeMillis() - start);
            return McpDebugResult.fail(e.getMessage(), elapsedMs);
        }
    }

    // ── 私有工具 ─────────────────────────────────────────────────

    private List<String> listToolsFromEndpoint(String endpoint) {
        try (DirectMcpClient client = new DirectMcpClient(endpoint)) {
            client.initialize();
            return client.listToolNames();
        } catch (Exception e) {
            throw new RuntimeException("无法连接 MCP Server: " + e.getMessage(), e);
        }
    }

    private McpServer getOrThrow(Long id) {
        McpServer server = mcpServerMapper.selectById(id);
        if (server == null) throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND);
        return server;
    }

    private void checkNameUnique(String name, Long excludeId) {
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getName, name)
                .ne(excludeId != null, McpServer::getId, excludeId);
        if (mcpServerMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.MCP_SERVER_NOT_FOUND, "MCP Server 名称已存在");
        }
    }
}

package io.github.icexxl.etax.agent.mcp

import io.github.icexxl.etax.R
import io.github.icexxl.etax.core.AndroidAgentLogger
import io.github.icexxl.etax.core.AppStrings
import io.github.icexxl.etax.data.model.McpServerSetting
import io.github.icexxl.etax.data.repository.McpServerRepository

internal object McpServerManager {
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        discovery.truncation?.let { reason ->
            AndroidAgentLogger.warn(
                "MCP tool discovery truncated: serverId=${server.id}, " +
                    "imported=${discovery.tools.size}, reason=${reason.wireValue}",
            )
        }
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
            toolsTruncationReason = discovery.truncation,
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            AppStrings.get(R.string.mcp_err_server_not_found, "MCP 服务器不存在")
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }
}

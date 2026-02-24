const { McpClient } = require('./mcp-client.cjs');

class McpHub {
  constructor() {
    this.clients = new Map();
    this.tools = [];
  }

  async connectMany(servers = []) {
    const normalized = Array.isArray(servers)
      ? servers.filter((server) => server && typeof server.serverId === 'string' && typeof server.url === 'string')
      : [];

    this.clients.clear();
    this.tools = [];

    const connectedServers = [];
    for (const server of normalized) {
      const serverId = server.serverId.trim();
      const url = server.url.trim();
      if (!serverId || !url) continue;
      try {
        const client = new McpClient(url);
        const result = await client.connect();
        const rawTools = Array.isArray(result?.tools) ? result.tools : [];
        this.clients.set(serverId, { client, url });
        connectedServers.push({ serverId, url, ok: true });
        for (const tool of rawTools) {
          if (!tool || typeof tool.name !== 'string') continue;
          const originName = tool.name;
          const qualifiedName = `${serverId}__${originName}`;
          this.tools.push({
            ...tool,
            name: originName,
            originName,
            serverId,
            qualifiedName,
          });
        }
      } catch (error) {
        connectedServers.push({
          serverId,
          url,
          ok: false,
          error: error instanceof Error ? error.message : 'MCP 연결 실패',
        });
      }
    }

    return {
      ok: connectedServers.some((server) => server.ok),
      tools: this.tools,
      connectedServers,
    };
  }

  listTools() {
    return { tools: this.tools };
  }

  async callTool(name, args = {}, serverId = null) {
    const target = this._resolveToolTarget(name, serverId);
    if (!target) {
      throw new Error(`도구를 찾을 수 없습니다: ${name}`);
    }
    const clientEntry = this.clients.get(target.serverId);
    if (!clientEntry) {
      throw new Error(`MCP 서버 미연결: ${target.serverId}`);
    }
    return clientEntry.client.callTool(target.originName, args);
  }

  status() {
    const servers = Array.from(this.clients.entries()).map(([serverId, entry]) => ({
      serverId,
      url: entry.url,
      connected: entry.client?.connected ?? false,
    }));
    return {
      connected: servers.some((server) => server.connected),
      servers,
    };
  }

  _resolveToolTarget(name, explicitServerId) {
    if (explicitServerId) {
      const tool = this.tools.find(
        (candidate) => candidate.serverId === explicitServerId && (candidate.originName === name || candidate.qualifiedName === name)
      );
      if (tool) {
        return {
          serverId: tool.serverId,
          originName: tool.originName,
        };
      }
      return null;
    }

    const byQualified = this.tools.find((candidate) => candidate.qualifiedName === name);
    if (byQualified) {
      return {
        serverId: byQualified.serverId,
        originName: byQualified.originName,
      };
    }

    const byOrigin = this.tools.filter((candidate) => candidate.originName === name);
    if (byOrigin.length === 1) {
      return {
        serverId: byOrigin[0].serverId,
        originName: byOrigin[0].originName,
      };
    }

    if (byOrigin.length > 1) {
      throw new Error(`동일 도구명이 여러 서버에 있습니다. serverId 또는 qualifiedName을 사용하세요: ${name}`);
    }

    return null;
  }
}

module.exports = { McpHub };

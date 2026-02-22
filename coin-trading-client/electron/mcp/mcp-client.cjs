/**
 * 경량 MCP 클라이언트 - Spring AI Stateless MCP Server 연동
 *
 * JSON-RPC 2.0 over HTTP POST.
 * SSE(text/event-stream) 및 일반 JSON 응답 모두 처리.
 */
const http = require('node:http');
const https = require('node:https');

class McpClient {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.requestId = 0;
    this.connected = false;
  }

  /**
   * JSON-RPC 요청을 MCP 서버에 전송한다.
   * Stateless 모드이므로 세션 없이 매 요청이 독립적으로 처리된다.
   */
  request(method, params = {}) {
    const id = ++this.requestId;
    const body = JSON.stringify({ jsonrpc: '2.0', id, method, params });

    return new Promise((resolve, reject) => {
      let url;
      try {
        url = new URL(this.baseUrl);
      } catch (e) {
        reject(new Error(`잘못된 MCP URL: ${this.baseUrl}`));
        return;
      }

      const client = url.protocol === 'https:' ? https : http;
      const options = {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json, text/event-stream',
        },
        timeout: 30000,
      };

      const req = client.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => {
          try {
            const contentType = res.headers['content-type'] || '';

            // SSE 응답 처리
            if (contentType.includes('text/event-stream')) {
              const result = this._parseSseResponse(data, id);
              if (result !== null) {
                resolve(result);
              } else {
                reject(new Error('SSE 스트림에서 응답을 찾을 수 없습니다.'));
              }
              return;
            }

            // 일반 JSON 응답
            const parsed = JSON.parse(data);
            if (parsed.error) {
              reject(new Error(parsed.error.message || `MCP 오류: ${parsed.error.code}`));
            } else {
              resolve(parsed.result);
            }
          } catch (e) {
            reject(new Error(`MCP 응답 파싱 실패: ${e.message}`));
          }
        });
      });

      req.on('error', (e) => reject(new Error(`MCP 연결 실패: ${e.message}`)));
      req.on('timeout', () => { req.destroy(); reject(new Error('MCP 요청 타임아웃')); });
      req.write(body);
      req.end();
    });
  }

  /**
   * SSE 형식의 응답에서 JSON-RPC result를 추출한다.
   */
  _parseSseResponse(data, expectedId) {
    const lines = data.split('\n');
    for (const line of lines) {
      if (!line.startsWith('data:')) continue;
      const jsonStr = line.slice(5).trim();
      if (!jsonStr || jsonStr === '[DONE]') continue;
      try {
        const parsed = JSON.parse(jsonStr);
        if (parsed.result !== undefined) {
          return parsed.result;
        }
        if (parsed.id === expectedId && parsed.result !== undefined) {
          return parsed.result;
        }
      } catch {
        // 파싱 불가한 라인은 건너뜀
      }
    }
    return null;
  }

  /**
   * MCP 서버에 연결하고 도구 목록을 반환한다.
   */
  async connect() {
    const result = await this.listTools();
    this.connected = true;
    return result;
  }

  /**
   * 등록된 도구 목록을 조회한다.
   */
  async listTools() {
    return this.request('tools/list', {});
  }

  /**
   * 도구를 실행한다.
   * @param {string} name - 도구 이름
   * @param {object} args - 도구 인자
   */
  async callTool(name, args = {}) {
    return this.request('tools/call', { name, arguments: args });
  }
}

module.exports = { McpClient };

import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { RegisteredRoute } from '../standalone/context';
import { boundedRequest, MAX_REQUEST_BYTES, MAX_SETTINGS_BYTES } from '../standalone/request-body';
import { rejectRequest, type RequestSecurity } from '../standalone/request-security';
import { settingsHandler, testHandler, type StandaloneServerOptions } from '../standalone/server';
import { settingsScript, settingsStyle } from '../standalone/settings-page';
import { controlScript, controlStyle } from './control-page';
import type { AppService } from './app-service';
import { appAiPage, appAiScript, appAiStyle } from './ai-page';

interface RouterDeps {
  security: RequestSecurity;
  ai?: StandaloneServerOptions['ai'];
  log(message: string): void;
  status(port: number): unknown;
  landingPage(port: number): string;
  onConfigChange?(): void;
  appService?: AppService;
}

/** App HTTP boundary, isolated from Electron so malformed and untrusted requests can be tested. */
export function startRouter(
  routes: RegisteredRoute[],
  deps: RouterDeps,
): Promise<{ port: number; close: () => Promise<void> }> {
  const settings = settingsHandler(deps.ai);
  const tester = testHandler(deps.ai);
  let boundPort = 0;

  const server = createServer((req, res) => {
    let url: URL;
    try {
      url = new URL(req.url ?? '/', 'http://127.0.0.1');
    } catch {
      rejectRequest(res, 400, '请求地址无效');
      return;
    }
    const route = routes.find((candidate) =>
      candidate.kind === 'exact'
        ? url.pathname === candidate.path
        : url.pathname === candidate.path || url.pathname.startsWith(`${candidate.path}/`),
    );
    // Only plugin calls may come from a file renderer, and even those require its capability.
    if (!deps.security.authorize(req, res, !!route)) return;
    const method = req.method ?? 'GET';
    if (['PUT', 'POST'].includes(method) && /^\/dsh-pet-7340\/(config|pet-display|reload)$/.test(url.pathname)) {
      res.once('finish', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) deps.onConfigChange?.();
      });
    }
    if (method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    const dispatch = async (request: IncomingMessage): Promise<void> => {
      if (url.pathname.startsWith('/app/')) {
        if (!deps.appService) {
          rejectRequest(res, 404, '应用接口不可用');
          return;
        }
        try {
          let data: unknown;
          if (method === 'GET' && url.pathname === '/app/info') data = deps.appService.info();
          else if (method === 'GET' && url.pathname === '/app/preferences') data = deps.appService.preferences();
          else if (
            (method === 'PUT' && url.pathname === '/app/preferences') ||
            (method === 'POST' && url.pathname === '/app/actions')
          ) {
            let body = '';
            for await (const chunk of request) body += String(chunk);
            const input = JSON.parse(body);
            data =
              url.pathname === '/app/preferences'
                ? deps.appService.savePreferences(input)
                : await deps.appService.action(input);
          } else {
            rejectRequest(res, 405, '不支持的应用请求');
            return;
          }
          sendText(res, 200, 'application/json', JSON.stringify(data));
        } catch {
          rejectRequest(res, 400, '操作未完成，请检查配置文件、输入或系统权限后重试');
        }
        return;
      }
      const assets: Record<string, readonly [string, string]> = {
        '/standalone-ai/desktop.js': [controlScript, 'text/javascript'],
        '/standalone-ai/desktop.css': [controlStyle, 'text/css'],
        '/standalone-ai/': [appAiPage, 'text/html'],
        '/standalone-ai/ai.js': [appAiScript, 'text/javascript'],
        '/standalone-ai/ai.css': [controlStyle + appAiStyle, 'text/css'],
        '/standalone-ai/settings.js': [settingsScript, 'text/javascript'],
        '/standalone-ai/settings.css': [settingsStyle, 'text/css'],
      };
      const asset = assets[url.pathname];
      if (asset || url.pathname === '/health' || url.pathname === '/') {
        if (method !== 'GET' && method !== 'HEAD') {
          rejectRequest(res, 405, '仅支持 GET');
          return;
        }
        if (asset) sendText(res, 200, asset[1], asset[0]);
        else if (url.pathname === '/health')
          sendText(res, 200, 'application/json', JSON.stringify(deps.status(boundPort)));
        else sendText(res, 200, 'text/html', deps.landingPage(boundPort));
        return;
      }
      if (url.pathname === '/standalone-ai/settings' || url.pathname === '/standalone-ai/test') {
        await (url.pathname === '/standalone-ai/test' ? tester : settings)(request, res);
        return;
      }
      if (!route) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('not found');
        return;
      }
      // CORS is useful to the authenticated file renderer; it grants no capability by itself.
      res.setHeader('access-control-allow-origin', '*');
      await route.handler(request, res);
    };

    const hasBody = req.headers['transfer-encoding'] !== undefined || Number(req.headers['content-length'] ?? 0) > 0;
    const bodyMethod = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);
    void (async () => {
      if (hasBody || bodyMethod) {
        const limit =
          url.pathname === '/standalone-ai/settings' || url.pathname.startsWith('/app/')
            ? MAX_SETTINGS_BYTES
            : MAX_REQUEST_BYTES;
        const bounded = await boundedRequest(req, res, limit);
        if (bounded) await dispatch(bounded);
      } else await dispatch(req);
    })().catch(() => {
      // Request bodies, URLs and thrown values may contain credentials; keep logs generic.
      deps.log('本机接口处理失败');
      if (!res.headersSent) rejectRequest(res, 500, '接口暂时无法处理请求');
      else res.end();
    });
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  server.on('clientError', (_error, socket) => socket.destroy());

  return new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      boundPort = (server.address() as AddressInfo).port;
      resolvePromise({
        port: boundPort,
        close: async () => {
          server.closeAllConnections();
          await new Promise<void>((done) => server.close(() => done()));
        },
      });
    });
  });
}

function sendText(res: ServerResponse, status: number, type: string, body: string): void {
  res.writeHead(status, {
    'content-type': `${type}; charset=utf-8`,
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  });
  res.end(body);
}

import { settingsPage, settingsScript } from '../standalone/settings-page';
import { appSidebar } from './control-page';

const sidebar = appSidebar
  .replace(
    /<button class="nav-item[^"]*" data-panel="([^"]+)"[^>]*>([\s\S]*?)<\/button>/g,
    (_match, panel, body) => '<a class="nav-item" href="/?panel=' + panel + '">' + body + '</a>',
  )
  .replace('class="nav-item" id="ai-link"', 'class="nav-item active" id="ai-link"');

export const appAiPage = settingsPage
  .replace('/standalone-ai/settings.css', '/standalone-ai/ai.css')
  .replace('/standalone-ai/settings.js', '/standalone-ai/ai.js')
  .replace('<body>', '<body>' + sidebar)
  .replace(
    '<main><header>',
    '<main><div class="topbar"><a href="/">← 返回我的桌宠</a><span class="status-chip"><i></i>仅保存在本机</span></div><div class="ai-content"><header>',
  )
  .replace('</main>', '</div></main>');

export const appAiScript =
  settingsScript +
  `
(() => {
  const theme=localStorage.getItem('dsh-pet-theme')||'system';
  if(theme!=='system')document.documentElement.dataset.theme=theme;
  const button=document.getElementById('quit');
  button.addEventListener('click',async()=>{if(!window.confirm('退出桌宠和控制中心？'))return;await fetch('/app/actions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'quit'})});});
})();`;

export const appAiStyle = `
main .ai-content{max-width:760px;margin:0 auto;padding-top:32px}main header .fish{display:none}main header .local{font-size:9px;letter-spacing:2px;color:var(--muted);margin:0 0 12px}main header h1{font-size:27px;margin-bottom:13px}main header .intro{font-size:12px;line-height:1.9;color:var(--muted);margin:0 0 25px}#settings{background:var(--surface);border:1px solid var(--line);padding:26px 30px;border-radius:15px}#settings fieldset{border-bottom:1px solid var(--line);padding-bottom:24px;margin-bottom:24px}#settings legend{font-size:14px;font-weight:600;margin-bottom:12px}#settings label{font-size:11px;color:var(--ink);display:block;margin:16px 0 6px}#settings .check{display:flex;align-items:center;gap:8px;font-size:11px}#settings input:not([type=checkbox]),#settings select{font-size:12px;min-height:39px}#settings .hint{font-size:10px;line-height:1.8;color:var(--muted);margin:7px 0 0}#settings .key-heading{display:flex;align-items:center;justify-content:space-between}#settings .key-heading label{margin-bottom:6px}#settings .notice{font-size:10px;line-height:1.8;color:#aa9165;background:var(--subtle);border-left:3px solid #d9c49c;padding:12px 14px;border-radius:5px}#settings .pair{display:grid;grid-template-columns:1fr 1fr;gap:20px}#settings footer{margin-top:0;padding-top:0;display:flex;align-items:center;border:0;gap:18px}#settings footer p{font-size:10px;line-height:1.8;margin:0}#settings #save{background:#7972da;color:white;border-color:#7972da;font-size:12px}#settings #status{font-size:11px;color:var(--accent);line-height:1.8;margin:18px 0 0}#settings .test-row{display:flex;align-items:center;gap:12px;margin-top:18px}#settings .test-result{font-size:10px;line-height:1.8}#settings .test-error{color:#bf7777}#settings .test-success{color:#7aae96}#settings .reply-preview{display:block;color:var(--muted)}
`;

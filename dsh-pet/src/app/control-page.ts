/** Native control center: all writes use the existing validated plugin routes. */
export const controlPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'">
<title>桌宠之家</title><link rel="stylesheet" href="/standalone-ai/desktop.css"><script src="/standalone-ai/desktop.js" defer></script></head>
<body><aside><div class="brand">◕<span>桌宠之家<small>DSH PET · DESKTOP</small></span></div><nav><a class="active" href="/">我的桌宠 <span>↗</span></a><a id="ai-link" href="/standalone-ai/">AI 与对话 <span>↗</span></a></nav><p class="aside-note">一点陪伴，一点快乐。<br>让桌面也有自己的小日常。</p><div class="online"><i></i> 正在本机运行 <small id="version"></small></div></aside>
<main><header><div><p class="eyebrow">YOUR LITTLE COMPANIONS</p><h1>今天，也一起待着吧。</h1><p class="intro">给小伙伴一个名字，选个舒服的角落。</p></div><button id="add" type="button">＋ 添加桌宠</button></header>
<div class="banner"><span class="banner-icon">✦</span><div><strong>它已经住进你的桌面了</strong><p>拖一拖和它玩，右键解锁动作和对话。关闭这个窗口后，桌宠会继续陪着你。</p></div></div>
<form id="pets-form"><div class="section-title"><h2>我的小伙伴 <span id="count">—</span></h2><span>保存后立即生效</span></div><div id="pets"></div><div class="actions"><p id="message" role="status" aria-live="polite">正在读取桌宠…</p><button id="save" disabled>保存更改</button></div></form>
<section class="help"><h2>相处小贴士</h2><div><p><b>01 · 摸摸它</b>单击桌宠，看看它的回应。</p><p><b>02 · 自由漫游</b>关闭「乖乖待着」，它会自己走走。</p><p><b>03 · 随时回来</b>从系统托盘打开控制中心。</p></div></section><footer>所有设置保存在本机 · 无需 DeepSeek Harness 即可运行</footer></main></body></html>`;

export const controlStyle = `
:root{font-family:"Microsoft YaHei UI","Segoe UI",sans-serif;color:#26342d;background:#f7f8f3;font-synthesis:none}*{box-sizing:border-box}body{margin:0;display:flex;min-height:100vh}aside{width:224px;position:fixed;inset:0 auto 0 0;background:#eff2e8;border-right:1px solid #dce2d4;padding:36px 22px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:12px;font-size:37px;color:#58744e}.brand span{font-size:19px;font-weight:700;letter-spacing:1px}.brand small{display:block;font-size:9px;letter-spacing:1.5px;font-weight:400;margin-top:6px}nav{display:grid;gap:8px;margin-top:52px}nav a{padding:14px;border-radius:10px;color:#626c60;font-size:14px;text-decoration:none;display:flex;justify-content:space-between}nav a.active{background:#dce6d0;color:#304b2c;font-weight:700}nav a:hover{background:#e2e9d9}.aside-note{margin-top:auto;font-size:12px;line-height:1.9;color:#77816f;padding:36px 8px 20px}.online{border-top:1px solid #d9e0d1;padding:20px 6px 0;font-size:11px;color:#697461}.online i{display:inline-block;width:6px;height:6px;border-radius:50%;background:#718b5c;margin-right:6px}.online small{display:block;margin:8px 12px;color:#929b89}main{margin-left:224px;width:calc(100% - 224px);max-width:1280px;padding:46px 48px 24px}header{display:flex;align-items:center;justify-content:space-between;gap:24px}.eyebrow{font-size:10px;letter-spacing:2px;color:#87937d;margin:0 0 12px}h1{font-size:29px;letter-spacing:-1px;margin:0;font-weight:600}.intro{font-size:13px;color:#84907d;margin:12px 0 0}button{font:inherit;font-size:13px;cursor:pointer;border:1px solid #cbd6c0;background:#fff;color:#4b6240;padding:11px 18px;border-radius:9px;white-space:nowrap}button:hover{background:#edf2e7}button:disabled{opacity:.5;cursor:wait}.banner{display:flex;align-items:center;gap:20px;padding:23px 26px;margin:32px 0;background:#e7eddd;border:1px solid #dce5d1;border-radius:14px}.banner-icon{font-size:34px;color:#81956d}.banner strong{font-size:14px;font-weight:600}.banner p{font-size:12px;color:#7a886d;margin:8px 0 0;line-height:1.7}.section-title{display:flex;justify-content:space-between;align-items:center;margin:30px 0 18px}.section-title h2,.help h2{font-size:16px;font-weight:600;margin:0}.section-title h2 span{font-size:11px;background:#e7ecdf;border-radius:5px;padding:3px 7px;margin-left:6px}.section-title>span{font-size:11px;color:#8a9481}#pets{display:grid;gap:16px}.pet{background:white;border:1px solid #e0e5d8;border-radius:14px;padding:23px}.pet-head{display:flex;align-items:center;gap:12px;margin-bottom:20px}.avatar{height:40px;width:40px;border-radius:12px;background:#edf1e8;color:#79906b;text-align:center;font-size:29px}.pet-head strong{font-size:14px}.pet-head small{display:block;font:10px/1.7 monospace;color:#96a08e}.remove{margin-left:auto;border:0;background:none;color:#a17e71;padding:6px 10px;font-size:12px}.fields{display:grid;grid-template-columns:1fr 1fr;gap:16px 22px}label{font-size:12px;color:#65735d;display:block}input:not([type=checkbox]),select{display:block;width:100%;margin-top:8px;border:1px solid #dce2d4;border-radius:7px;background:#fcfdf9;padding:10px;color:#37482e;font:13px inherit;min-height:39px}input[type=checkbox]{accent-color:#6d8656}.checks{display:flex;gap:24px;margin-top:18px}.checks label{display:flex;align-items:center;gap:7px}.actions{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:22px 0}.actions button{background:#647e51;color:white;border-color:#647e51;min-width:118px}#message{font-size:12px;color:#6e8060;line-height:1.8}#message[data-error=true]{color:#ad5744}.help{border-top:1px solid #e0e5d8;margin-top:36px;padding-top:26px}.help>div{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}.help p{font-size:11px;color:#8a9581;line-height:1.9}.help b{display:block;font-weight:500;color:#68795a;margin-bottom:6px}footer{font-size:10px;color:#a1ab98;margin-top:32px}:focus-visible{outline:3px solid #a1b88a;outline-offset:3px}@media(max-width:850px){aside{width:184px;padding:30px 16px}main{margin-left:184px;width:calc(100% - 184px);padding:32px 26px}h1{font-size:23px}.help>div{grid-template-columns:1fr;gap:0}}@media(max-width:620px){aside{width:100%;position:static;padding:18px}body{display:block}nav{margin-top:16px;grid-template-columns:1fr 1fr}.aside-note,.online{display:none}main{margin:0;width:100%;padding:24px 18px}.fields{grid-template-columns:1fr}header{align-items:flex-start}h1{font-size:21px}}
`;

export const controlScript = `
(() => {
  'use strict';
  const get = id => document.getElementById(id);
  const token = new URLSearchParams(location.hash.slice(1)).get('token') || '';
  history.replaceState(null, '', '/');
  get('ai-link').href = '/standalone-ai/' + (token ? '#token=' + token : '');
  let pets = [];
  let busy = false;
  function message(text, error = false) { get('message').textContent = text; get('message').dataset.error = String(error); }
  async function request(path, method = 'GET', body) {
    const response = await fetch(path, {method, cache:'no-store', redirect:'error', headers:{'X-DSH-Pet-Token':token, ...(body ? {'Content-Type':'application/json'} : {})}, ...(body ? {body:JSON.stringify(body)} : {})});
    const data = await response.json();
    if (!response.ok) throw new Error(response.status === 403 ? '访问凭据已失效，请从托盘重新打开控制中心。' : response.status === 409 ? '配置文件无法解析，请先修复本机配置，再保存。' : '保存失败，请检查输入后重试。');
    return data;
  }
  function element(tag, text, className) { const el = document.createElement(tag); if(text) el.textContent=text; if(className) el.className=className; return el; }
  function field(parent, caption, pet, key, options) {
    const label = element('label', caption);
    const input = document.createElement(options ? 'select' : 'input');
    if(options) options.forEach(([value,text]) => {const option=element('option',text); option.value=value; input.append(option);});
    else {input.type = key === 'size' || key === 'marginX' || key === 'marginY' ? 'number' : 'text'; if(input.type === 'number') {input.min=key==='size'?'80':'0';input.max=key==='size'?'1000':'10000';} else input.maxLength=80;}
    input.value=String(pet[key] ?? ''); input.required=true;
    input.addEventListener('input', () => {pet[key]=input.type==='number'?Number(input.value):input.value; message('有未保存的更改');});
    label.append(input); parent.append(label);
  }
  function render() {
    get('pets').replaceChildren(); get('count').textContent=String(pets.length);
    pets.forEach(pet => {
      const card=element('section',null,'pet'); const head=element('div',null,'pet-head');
      head.append(element('span','◕','avatar')); const title=element('div');title.append(element('strong',pet.name),element('small',pet.id));head.append(title);
      const remove=element('button','移除','remove');remove.type='button';remove.disabled=pets.length===1;remove.addEventListener('click',()=>{pets=pets.filter(p=>p!==pet);render();message('已移除，保存后生效');});head.append(remove);card.append(head);
      const fields=element('div',null,'fields');
      field(fields,'名字',pet,'name');field(fields,'大小 · px',pet,'size');
      field(fields,'显示状态',pet,'display',[['desktop','显示在桌面'],['none','暂时隐藏'],['both','桌面与 DSH 网页'],['web','仅 DSH 网页']]);
      field(fields,'起始位置',pet.position,'corner',[['bottom-right','右下角'],['bottom-left','左下角'],['top-right','右上角'],['top-left','左上角']]);
      field(fields,'水平边距 · px',pet.position,'marginX');field(fields,'垂直边距 · px',pet.position,'marginY');card.append(fields);
      const checks=element('div',null,'checks');[['fixedEnabled','乖乖待着'],['whisperEnabled','允许自动碎碎念']].forEach(([key,text])=>{const label=element('label');const input=document.createElement('input');input.type='checkbox';input.checked=pet[key]===true;input.addEventListener('change',()=>{pet[key]=input.checked;message('有未保存的更改');});label.append(input,document.createTextNode(text));checks.append(label);});card.append(checks);get('pets').append(card);
    });
  }
  get('add').addEventListener('click',()=>{if(busy)return; if(pets.length>=12){message('最多添加 12 只小伙伴。');return;} const base=pets[0];if(!base)return;const pet=structuredClone(base);pet.id='pet-'+crypto.randomUUID().slice(0,12);pet.name='新来的小伙伴';pet.display='desktop';pet.size=360;pet.position={corner:'bottom-right',marginX:24+pets.length*40,marginY:24};pets.push(pet);render();message('新伙伴准备好了，保存后就会出现');});
  get('pets-form').addEventListener('submit',async event=>{event.preventDefault();if(busy)return;busy=true;get('save').disabled=true;get('add').disabled=true;try{const data=await request('/dsh-pet-7340/config','PUT',{pets});pets=data.main.pets;render();message('已保存，小伙伴们正在重新就位。');}catch(error){message(error.message,true);}finally{busy=false;get('save').disabled=false;get('add').disabled=false;}});
  Promise.all([request('/dsh-pet-7340/config'),request('/health')]).then(([config,health])=>{pets=config.main.pets;render();get('version').textContent='v'+health.version;get('save').disabled=false;message('拖动桌宠可以自由移动；这里管理内置宠物，额外 pet pack 保留原配置。');}).catch(error=>message(error.message,true));
})();
`;

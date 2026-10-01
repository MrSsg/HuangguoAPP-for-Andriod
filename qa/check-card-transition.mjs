// Run against the dedicated headless Chrome on port 9237, with real app assets.
import fs from 'node:fs';
const pages = await (await fetch('http://127.0.0.1:9237/json')).json();
const ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
await new Promise(resolve => ws.onopen = resolve);
let seq = 0;
const pending = new Map(), errors = [];
ws.onmessage = event => {
  const message = JSON.parse(event.data);
  if (message.id) { const entry = pending.get(message.id); pending.delete(message.id); message.error ? entry.reject(message.error) : entry.resolve(message.result); }
  if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
};
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, {resolve,reject}); ws.send(JSON.stringify({id,method,params})); });
}
async function evaluate(expression) {
  const result = await cdp('Runtime.evaluate', {expression,returnByValue:true,awaitPromise:true});
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
await cdp('Runtime.enable');
await cdp('Page.enable');
await cdp('Emulation.setDeviceMetricsOverride', {width:412,height:852,deviceScaleFactor:2,mobile:true});
await cdp('Emulation.setTouchEmulationEnabled', {enabled:true});
await cdp('Page.addScriptToEvaluateOnNewDocument', {source:`
localStorage.setItem('adult-confirmed','1');
const cover = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="600" height="850"><rect width="600" height="850" fill="#586455"/><circle cx="350" cy="250" r="180" fill="#dfba72"/><path d="M0 850 600 320V850" fill="#263d38"/><text x="45" y="670" fill="#fff4dc" font-size="65">CARD STUDY</text></svg>');
const items = Array.from({length:30},(_,i)=>({id:String(i+1),title:'卡片转场测试 '+(i+1),cover:'https://qa.invalid/'+i,score:'9.7分',episodeLabel:'共 24 集',description:'检查卡片打开、取消回弹和原位置收回。',category:'ai-duanju'}));
const home = {featured:items.slice(0,1),homepageRecommend:items.slice(0,12),homepageNewest:items.slice(12),recommend:{items:items.slice(0,12)},newest:{items:items.slice(12)}};
window.AndroidHost = {setBackEnabled(){},request(id,action,json){ const args=JSON.parse(json); let value = null;
  if(action==='home') value=home;
  if(action==='category') value={items,nextPage:null};
  if(action==='cover') value=cover;
  if(action==='screen-corners') value={topLeft:48,topRight:48,bottomRight:48,bottomLeft:48};
  if(action==='detail') value={...items.find(item=>item.id===args.id),episodes:Array.from({length:24},(_,i)=>({number:i+1}))};
  if(action==='account') value={loggedIn:false};
  if(action==='app-version') value='0.1.28';
  if(action==='cached-update'||action==='check-update') value={status:'current'};
  if(action==='bookmark-state') value=false;
  setTimeout(()=>NativeCallbacks.resolve(id,value),action==='detail'?240:10);
}};
`});
await cdp('Page.navigate', {url:'http://127.0.0.1:8937/app.html'});
await wait(700);
const checks = [], failed = [];
function check(name, value) { (value ? checks : failed).push(name); }
async function idle() { for(let i=0;i<70;i++){if(await evaluate("!detailCard || ['opened','disposed'].includes(detailCard.phase)"))return; await wait(25);}throw new Error('Transition did not settle'); }
async function screenshot(name) { const result=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(process.argv[2]+'/'+name+'.png',Buffer.from(result.data,'base64')); }
check('offline dependency loaded', await evaluate("typeof FlipToolkit.spring === 'function'"));
await evaluate("stopBannerAuto(); scroll.scrollTop=220; content.querySelector('.film-row').scrollLeft=80");
await wait(80);
await evaluate("window.savedScroll=scroll.scrollTop; window.savedRow=content.querySelector('.film-row').scrollLeft; window.testCard=content.querySelector('.film-card:nth-child(3)'); window.savedBounds=testCard.getBoundingClientRect().toJSON(); goDetail(testCard.dataset.openId,testCard); window.initialGeometry={...detailCard.current}");
check('starts at complete source card bounds', await evaluate("Math.abs(initialGeometry.w-savedBounds.width)<4 && Math.abs(initialGeometry.h-savedBounds.height)<4"));
await wait(170);
await screenshot('opening');
check('whole surface expands; image uniformly scales',await evaluate("detailCard.current.w>savedBounds.width && detailCard.current.h>savedBounds.height && getComputedStyle(detailCard.image).transform.split(',')[0].slice(7) === getComputedStyle(detailCard.image).transform.split(',')[3].trim()"));
await idle();
check('fullscreen detail, header and dock hidden',await evaluate("state.route==='detail' && !document.querySelector('.card-transition-surface') && getComputedStyle(document.querySelector('#app>.app-top')).display==='none' && getComputedStyle(document.getElementById('dock')).visibility==='hidden'"));
await screenshot('detail');
await evaluate("hgPredictiveBackStart(false); hgPredictiveBackProgress(.45)");
await wait(70);
check('interactive system back uniformly shrinks page',await evaluate("detailCard.current.w < 412 && detailCard.current.radius===48 && detailCard.current.contentScale < 1"));
await screenshot('drag-return');
await evaluate('hgPredictiveBackCancel()'); await idle();
check('cancel returns to usable detail',await evaluate("state.route==='detail' && detailCard.phase==='opened' && !document.querySelector('.card-transition-stage')"));
await evaluate('hgPredictiveBackStart(true); hgPredictiveBackProgress(.35)'); await wait(40);
await evaluate('hgPredictiveBackCommit()'); await idle();
check('commit restores both scroll axes',await evaluate("state.route==='home' && scroll.scrollTop===savedScroll && content.querySelector('.film-row').scrollLeft===savedRow && !document.querySelector('.card-transition-stage') && !testCard.style.visibility"));
await evaluate("goDetail(testCard.dataset.openId,testCard)"); await wait(80); await evaluate('hgBack()'); await idle();
check('return during opening has no leftover layers', await evaluate("state.route==='home' && !document.querySelector('.card-transition-surface') && !detailCard"));
await evaluate("goDetail(testCard.dataset.openId,testCard)"); await idle();
// Exercise real touch dispatch, not the controller's internal drag methods.
async function touch(type,y,time=30){await cdp('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'?[]:[{x:200,y}]});await wait(time);}
await touch('touchStart',270); await touch('touchMove',305); await touch('touchEnd',305); await idle();
check('short drag cancels',await evaluate("state.route==='detail' && detailCard.phase==='opened'"));
await evaluate('scroll.scrollTop=160'); await wait(40);
await touch('touchStart',360); await touch('touchMove',385); await touch('touchEnd',385);
check('scrolling detail does not dismiss',await evaluate("state.route==='detail' && detailCard.phase==='opened'"));
await evaluate('scroll.scrollTop=0'); await wait(50);
await touch('touchStart',270); await touch('touchMove',370); await touch('touchMove',455); await screenshot('pull-down'); await touch('touchEnd',455); await idle();
check('top pull-down returns to original card',await evaluate("state.route==='home' && scroll.scrollTop===savedScroll && !detailCard"));

await evaluate("scroll.scrollTop=0; const heroButton=content.querySelector('.hero-action'); goDetail(heroButton.dataset.openId,heroButton)"); await idle();
check('banner shares its whole card',await evaluate("detailCard.source.bounds.h===250 && detailCard.source.titleSize===26"));
await evaluate('hgBack()'); await idle();
await evaluate("goCategory('adult')"); await wait(650);
await evaluate("scroll.scrollTop=220"); await wait(80);
await evaluate("var qaCard=content.querySelector('.film-card:nth-child(2)'); goDetail(qaCard.dataset.openId,qaCard)"); await idle();
await evaluate('hgPredictiveBackStart(false); hgPredictiveBackProgress(.3)'); await wait(50);
check('category return preserves compact header',await evaluate("!!document.querySelector('.card-background-compact.visible') && detailCard.snapshot.pageNode.classList.contains('is-collapsed')"));
await evaluate('hgPredictiveBackCommit()'); await idle();
check('category return destination and scroll retained',await evaluate("state.route==='category' && scroll.scrollTop===220 && compact.classList.contains('visible')"));
await evaluate("document.documentElement.dataset.theme='dark'; var qaCard=content.querySelector('.film-card:nth-child(2)'); goDetail(qaCard.dataset.openId,qaCard)"); await idle();
await evaluate('hgPredictiveBackStart(false); hgPredictiveBackProgress(.3)'); await wait(60); await screenshot('dark-return');
await evaluate('hgPredictiveBackCancel()'); await idle();
await evaluate("renderError(new Error('网络连接失败'),'detail'); const previousNode=state.previous.pageNode; document.querySelector('[data-retry=detail]').click(); window.retryPrevious=previousNode"); await wait(350);
check('retry keeps original return source',await evaluate("state.previous.pageNode===retryPrevious && !!content.querySelector('.detail-art')"));
await evaluate('hgBack()'); await idle();
await evaluate("goHome()"); await wait(600); await evaluate("window.testCard=content.querySelector('.film-card:nth-child(3)')");
await cdp('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
await evaluate("goDetail(testCard.dataset.openId,testCard)"); await wait(350); await evaluate('hgBack()'); await idle();
check('reduced motion navigation',await evaluate("state.route==='home' && !document.querySelector('.card-transition-surface')"));
check('no JavaScript exceptions',errors.length===0);
console.log(JSON.stringify({passed:checks.length,checks,failed,errors},null,2));
ws.close();
if (failed.length) process.exitCode=1;

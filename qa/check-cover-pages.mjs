// Verify real native cover responses in the app WebView without changing the visible page.
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const adb = path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const appPid = execFileSync(adb, ['shell', 'pidof', 'com.huangguo.mobile'], { encoding: 'utf8' }).trim();
if (!appPid) throw new Error('HuangGuo app is not running');
execFileSync(adb, ['forward', 'tcp:9228', `localabstract:webview_devtools_remote_${appPid}`]);
const [page] = await (await fetch('http://127.0.0.1:9228/json', { signal: AbortSignal.timeout(5000) })).json();
const socket = new WebSocket(page.webSocketDebuggerUrl);
const timeout = (ms, message) => new Promise((_, reject) => {
  const timer = setTimeout(() => reject(new Error(message)), ms);
  timer.unref();
});
await Promise.race([
  new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; }),
  timeout(5000, 'WebView connection timed out')
]);

const expression = `(async()=>{
  const report=[];
  const targets=[
    {action:'recommend',page:2},{action:'recommend',page:3},
    {action:'newest',page:2},{action:'newest',page:3},
    {action:'category',id:'ai-duanju',page:2}
  ];
  for(const target of targets){
    const listing=await api(target.action,target);
    let decoded=0;
    const failed=[];
    for(const item of listing.items){
      let image;
      try{
        const data=await api('cover',{url:item.cover});
        image=document.createElement('img');
        image.style.cssText='position:fixed;left:-10000px;top:-10000px;width:1px;height:1px';
        document.body.appendChild(image);
        await new Promise((resolve,reject)=>{
          image.onload=()=>resolve();
          image.onerror=()=>reject(new Error('decode'));
          image.src=data;
          if(image.complete&&image.naturalWidth>0)resolve();
        });
        if(image.naturalWidth>0)decoded++;else failed.push(item.id);
      }catch(error){failed.push(item.id)}
      finally{image?.remove()}
    }
    report.push({kind:target.action,page:target.page,total:listing.items.length,decoded,failed});
  }
  return report;
})()`;

const result = await Promise.race([
  new Promise(resolve => {
    socket.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.id === 1) resolve(message.result);
    };
    socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  }),
  timeout(180000, 'Cover page check timed out')
]);
socket.close();
const report = result.result?.value;
console.log(JSON.stringify(report));
if (!report || report.some(page => page.decoded !== page.total)) {
  throw new Error('Page cover check failed: ' + JSON.stringify(result));
}

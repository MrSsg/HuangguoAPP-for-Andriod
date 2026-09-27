// Run after forwarding the app WebView to localhost:9228 with adb forward.
const [page] = await (await fetch('http://127.0.0.1:9228/json', { signal: AbortSignal.timeout(5000) })).json();
const socket = new WebSocket(page.webSocketDebuggerUrl);
await Promise.race([
  new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; }),
  new Promise((_, reject) => setTimeout(() => reject(new Error('WebView connection timed out')), 5000))
]);

const expression = `(async()=>{
  document.querySelector('[data-nav=home]').click();
  await new Promise(resolve=>setTimeout(resolve,1200));
  state.banner=0;renderHome();
  await new Promise(resolve=>setTimeout(resolve,1200));
  const count=homeSlides().length;
  let blankFrames=0;
  const monitor=setInterval(()=>{
    const images=[...document.querySelectorAll('.hero-slide img')];
    if(images.length&&!images.some(image=>image.naturalWidth>0))blankFrames++;
  },16);
  const seen=[];
  for(const direction of [1,1,1,1,1,-1]){
    const hero=document.querySelector('.hero');
    const start=new Touch({identifier:1,target:hero,clientX:direction>0?300:70,clientY:150});
    const end=new Touch({identifier:1,target:hero,clientX:direction>0?70:300,clientY:150});
    hero.dispatchEvent(new TouchEvent('touchstart',{touches:[start],changedTouches:[start],bubbles:true}));
    hero.dispatchEvent(new TouchEvent('touchend',{touches:[],changedTouches:[end],bubbles:true}));
    const deadline=Date.now()+5000;
    while(bannerChanging&&Date.now()<deadline) await new Promise(resolve=>setTimeout(resolve,40));
    seen.push(state.banner);
  }
  clearInterval(monitor);
  return {count,seen,blankFrames};
})()`;

const result = await Promise.race([
  new Promise(resolve => {
    socket.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.id === 1) resolve(message.result);
    };
    socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  }),
  new Promise((_, reject) => setTimeout(() => reject(new Error('Banner check timed out')), 45000))
]);
socket.close();
const verdict = result.result?.value;
const expected = verdict && [1, 2, 3, 4, 5, 4].map(index => (index % verdict.count + verdict.count) % verdict.count);
if (!verdict || verdict.count < 2 || verdict.seen.join(',') !== expected.join(',') || verdict.blankFrames !== 0) {
  throw new Error('Banner check failed: ' + JSON.stringify(result));
}
console.log('Banner loop and image continuity passed:', JSON.stringify(verdict));

const [page] = await (await fetch('http://127.0.0.1:9228/json')).json();
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
const expression = process.argv.slice(2).join(' ') || 'document.title';
const result = await new Promise(resolve => {
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id === 1) resolve(message.result);
  };
  socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
});
console.log(JSON.stringify(result, null, 2));
socket.close();

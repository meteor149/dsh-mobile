(() => {
  if (window.__dshExportDownload || !window.__dshDownload) return;
  const bridge = window.__dshDownload;
  let active = false;
  let waiting = null;
  bridge.onmessage = event => {
    const response = JSON.parse(event.data);
    if (!waiting || response.id !== waiting.id) return;
    const pending = waiting;
    waiting = null;
    response.error ? pending.reject(new Error(response.error)) : pending.resolve();
  };
  const send = message => new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (waiting?.id === message.id) waiting = null;
      reject(new Error('Download transfer timed out'));
    }, 30000);
    waiting = {
      id: message.id,
      resolve: () => { clearTimeout(timer); resolve(); },
      reject: error => { clearTimeout(timer); reject(error); },
    };
    bridge.postMessage(JSON.stringify(message));
  });
  const supported = href => {
    try {
      const url = new URL(href, location.href);
      return url.protocol === 'data:' ||
        (['http:', 'blob:'].includes(url.protocol) && url.origin === location.origin);
    } catch { return false; }
  };
  const download = async (href, filename) => {
    if (active) { bridge.postMessage(JSON.stringify({type: 'busy'})); return; }
    active = true;
    const id = crypto.randomUUID?.() || Array.from(crypto.getRandomValues(new Uint8Array(16)),
      byte => byte.toString(16).padStart(2, '0')).join('');
    try {
      // Start reading before the caller can immediately revoke its object URL.
      const payload = href.startsWith('blob:') || href.startsWith('data:')
        ? fetch(href).then(response => response.blob()) : null;
      if (payload === null) {
        await send({type: 'http', id, url: href, filename});
      } else {
        const blob = await payload;
        await send({type: 'begin', id, filename, mime: blob.type, size: blob.size});
        let sequence = 0;
        for (let offset = 0; offset < blob.size; offset += 65536) {
          const bytes = new Uint8Array(await blob.slice(offset, offset + 65536).arrayBuffer());
          let binary = '';
          for (const byte of bytes) binary += String.fromCharCode(byte);
          await send({type: 'chunk', id, sequence: sequence++, data: btoa(binary)});
        }
        await send({type: 'end', id});
      }
    } catch (error) {
      bridge.postMessage(JSON.stringify({type: 'error', id, error: String(error.message)}));
    } finally { active = false; }
  };
  window.__dshExportDownload = (href, filename) => {
    if (supported(href)) download(href, filename || 'download');
  };
  const nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(...args) {
    if (this.hasAttribute('download') && supported(this.href)) {
      download(this.href, this.download);
      return;
    }
    return nativeClick.apply(this, args);
  };
  document.addEventListener('click', event => {
    const anchor = event.target?.closest?.('a[download]');
    if (!anchor || !supported(anchor.href)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    download(anchor.href, anchor.download);
  }, true);
})();

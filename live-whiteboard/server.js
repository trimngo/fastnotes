#!/usr/bin/env node
// USB-C whiteboard receiver. Offline, local-only, disk-backed and self-healing.
'use strict';
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFile} = require('node:child_process');

const WEB_PORT = 17171, INK_PORT = 17172, HOST = '127.0.0.1';
const clients = new Set();
const cacheDir = path.join(os.homedir(), '.fastnotes-usb');
const cachePath = path.join(cacheDir, 'whiteboard-latest.json');
let snapshot = null, events = [], tabletConnections = 0;
let bridgeBusy = false, bridgeStatus = '';
const MAX_EVENTS = 100_000, MAX_LINE_BYTES = 32 * 1024 * 1024;
try {snapshot = JSON.parse(fs.readFileSync(cachePath,'utf8'));}
catch (_) {snapshot = null;}

function send(res, data) {
  try {res.write('data: '+JSON.stringify(data)+'\n\n');}
  catch (_) {clients.delete(res);}
}
function publish(data) {
  if (data.type === 'snapshot') {
    snapshot = data;
    events = [];
    persistSnapshot(data);
  }
  if (data.type === 'sample' && !['hover','leave'].includes(data.action)) {
    events.push(data);
    if (events.length > MAX_EVENTS) events = events.slice(-MAX_EVENTS);
  }
  for(const client of clients) send(client,data);
}
let pendingSave = Promise.resolve();
function persistSnapshot(data) {
  pendingSave = pendingSave.then(async () => {
    await fs.promises.mkdir(cacheDir,{recursive:true,mode:0o700});
    const tmp = cachePath+'.tmp';
    await fs.promises.writeFile(tmp,JSON.stringify(data),{mode:0o600});
    await fs.promises.rename(tmp,cachePath);
  }).catch(err=>console.warn('Could not cache whiteboard:',err.message));
}

const web = http.createServer((req,res) => {
  if (req.method==='GET' && req.url === '/') {
    res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store'});
    fs.createReadStream(path.join(__dirname,'index.html')).pipe(res);
  } else if (req.method==='GET' && req.url==='/events') {
    res.writeHead(200,{'content-type':'text/event-stream; charset=utf-8',
      'cache-control':'no-cache, no-transform','connection':'keep-alive'});
    clients.add(res);
    if (snapshot) send(res,snapshot);
    send(res,{type:'history',events});
    send(res,{type:'device',connected:tabletConnections>0});
    req.on('close',()=>clients.delete(res));
  } else if (req.method==='GET' && req.url==='/health') {
    res.writeHead(200,{'content-type':'application/json'});
    res.end(JSON.stringify({ok:true,connected:tabletConnections>0,cached:!!snapshot,events:events.length,bridgeStatus}));
  } else {res.writeHead(404);res.end('Not found');}
});
web.listen(WEB_PORT,HOST,()=>console.log('Mac whiteboard: http://127.0.0.1:'+WEB_PORT));

const ink=net.createServer(socket=>{
  socket.setNoDelay(true);
  socket.setEncoding('utf8');
  let buffer='';
  tabletConnections++;
  publish({type:'device',connected:true});
  console.log('AiPaper connected via USB-C');
  socket.on('data',chunk=>{
    buffer+=chunk;
    if(buffer.length>MAX_LINE_BYTES){socket.destroy();return;}
    let nl;
    while((nl=buffer.indexOf('\n'))!==-1){
      const line=buffer.slice(0,nl);
      buffer=buffer.slice(nl+1);
      try {
        const e=JSON.parse(line);
        if(e.type==='ping') {socket.write('{"type":"pong"}\n');continue;}
        if(e.type==='snapshot' && typeof e.docId==='string' &&
           Number.isFinite(e.pageWidth) && e.pageWidth>0 &&
           Number.isFinite(e.scrollY) &&
           Number.isFinite(e.viewWidth) && e.viewWidth>0 &&
           Number.isFinite(e.viewHeight) && e.viewHeight>0 &&
           Array.isArray(e.strokes)) {
          publish(e);
        }
        if(e.type==='sample' &&
           ['down','move','up','cancel','hover','leave'].includes(e.action) &&
           [e.x,e.y,e.w,e.h].every(Number.isFinite) &&
           e.w>0 && e.h>0 && e.w<10000 && e.h<10000) {
          publish({type:'sample',action:e.action,x:e.x,y:e.y,
            w:e.w,h:e.h,eraser:e.eraser===true,
            pressure:Number.isFinite(e.pressure)?e.pressure:0});
        }
      }catch(_){/* Never let a bad packet erase a stored document. */}
    }
  });
  socket.on('error',err=>console.warn('USB socket:',err.code||err.message));
  socket.on('close',()=>{
    tabletConnections=Math.max(0,tabletConnections-1);
    publish({type:'device',connected:tabletConnections>0});
    console.log('AiPaper disconnected; saved sheet retained.');
  });
});
ink.listen(INK_PORT,HOST,()=>console.log('USB ink receiver listening on 127.0.0.1:'+INK_PORT));

// ADB reverse is ephemeral: macOS/Android can forget it on each USB-C unplug.
// Restore only our exact mapping; never remove forwarding rules for other apps.
function adb(args,cb) {
  execFile('adb',['-d',...args],{timeout:3500},(err,stdout)=>cb(err,stdout||''));
}
function maintainUsbBridge(){
  if(bridgeBusy)return;
  bridgeBusy=true;
  adb(['reverse','--list'],(err,stdout)=>{
    if(!err && /tcp:17172\s+tcp:17172/.test(stdout)){
      if(bridgeStatus!=='ready')console.log('ADB USB-C forwarding ready');
      bridgeStatus='ready';bridgeBusy=false;return;
    }
    adb(['reverse','tcp:17172','tcp:17172'],(restoreErr)=>{
      const state=restoreErr?'waiting':'ready';
      if(state!==bridgeStatus)console.log(state==='ready'?
        'Restored ADB USB-C forwarding':'Waiting for USB debugging / adb / AiPaper cable');
      bridgeStatus=state;
      bridgeBusy=false;
    });
  });
}
maintainUsbBridge();
setInterval(maintainUsbBridge,1800).unref();

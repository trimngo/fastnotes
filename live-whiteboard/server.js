#!/usr/bin/env node
// Local-only Mac browser whiteboard receiver. No dependencies and no cloud services.
'use strict';
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');

const WEB_PORT = 17171;
const INK_PORT = 17172;
const HOST = '127.0.0.1'; // Only exposed through adb reverse; never listen on a public interface.
const clients = new Set();
let tabletConnections = 0;
let history = [];
const MAX_EVENTS = 100_000;

function send(res, event) {
  try { res.write('data: ' + JSON.stringify(event) + '\n\n'); }
  catch (_) { clients.delete(res); }
}
function publish(event) {
  if (event.type === 'reset') history = [];
  if (event.type === 'sample' && event.action !== 'hover' && event.action !== 'leave') {
    history.push(event);
    if (history.length > MAX_EVENTS) history = []; // Avoid unbounded growth during long lectures.
  }
  for (const client of clients) send(client, event);
}
const web = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, {'content-type':'text/html; charset=utf-8', 'cache-control':'no-store'});
    fs.createReadStream(path.join(__dirname, 'index.html')).pipe(res);
  } else if (req.method === 'GET' && req.url === '/events') {
    res.writeHead(200, {
      'content-type':'text/event-stream; charset=utf-8',
      'cache-control':'no-cache, no-transform', 'connection':'keep-alive',
      'x-content-type-options':'nosniff'
    });
    clients.add(res);
    send(res, {type:'history', events:history});
    send(res, {type:'device', connected:tabletConnections > 0});
    req.on('close', () => clients.delete(res));
  } else if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, {'content-type':'application/json'});
    res.end(JSON.stringify({ok:true, events:history.length}));
  } else {
    res.writeHead(404); res.end('Not found');
  }
});
web.listen(WEB_PORT, HOST, () => console.log('Mac whiteboard: http://127.0.0.1:' + WEB_PORT));

const ink = net.createServer(socket => {
  socket.setNoDelay(true);
  socket.setEncoding('utf8');
  let buffer = '';
  console.log('AiPaper connected');
  tabletConnections++;
  publish({type:'device', connected:true});
  socket.on('data', chunk => {
    buffer += chunk;
    if (buffer.length > 1024 * 1024) { socket.destroy(); return; }
    let newline;
    while ((newline = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      try {
        const event = JSON.parse(line);
        if (event.type === 'reset') publish({type:'reset'});
        if (event.type === 'sample' &&
            ['down','move','up','cancel','hover','leave'].includes(event.action) &&
            [event.x,event.y,event.w,event.h].every(Number.isFinite) &&
            event.w > 0 && event.h > 0 && event.w < 10000 && event.h < 10000) {
          publish({type:'sample',action:event.action,x:event.x,y:event.y,
            w:event.w,h:event.h,eraser:event.eraser === true,
            pressure:Number.isFinite(event.pressure)?event.pressure:0});
        }
      } catch (_) { /* Ignore malformed input without killing the receiver. */ }
    }
  });
  socket.on('error', err => console.warn('AiPaper connection:', err.code || err.message));
  socket.on('close', () => {
    tabletConnections = Math.max(0, tabletConnections - 1);
    publish({type:'device', connected:tabletConnections > 0});
    console.log('AiPaper disconnected');
  });
});
ink.listen(INK_PORT, HOST, () => console.log('USB ink receiver listening on 127.0.0.1:' + INK_PORT));

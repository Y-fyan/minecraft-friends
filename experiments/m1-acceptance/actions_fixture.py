"""Loopback-only M2 protocol simulation; never a real model or source of world authority."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import json,threading,time,math
class ActionsFixture:
 def __init__(self,path):
  self.path=Path(path);self.lock=threading.Lock();self.counts={};self.failures=0;fixture=self
  class Handler(BaseHTTPRequestHandler):
   def log_message(self,*_):pass
   def do_POST(self):
    try:
     n=int(self.headers.get('Content-Length','0'))
     if self.path!='/v1/chat/completions' or not 0<n<=131072 or self.headers.get('Authorization'):raise ValueError()
     data=json.loads(self.rfile.read(n));msg=data['messages'];last=msg[-1]
     if last['role']=='system' and last['content'].startswith('已确认的游戏事件：主人第一次加入'):
      label='greeting';reply='协议模拟，非真实模型。你好，我是 AI 伙伴。'
     else:
      if len(msg)!=2 or msg[0]['role']!='system' or last['role']!='user':raise ValueError()
      instruction,raw=last['content'].split('\n服务端观测快照：',1);snapshot=json.loads(raw)
      if not fixture.valid(snapshot):raise ValueError()
      label={'显式任务请求：请跟着我':'follow','显式任务请求：非法动作测试':'bad','显式任务请求：延迟后跟随':'late'}[instruction]
      reply=json.dumps({'action':'op' if label=='bad' else 'follow'})
     fixture.record(label)
     if label=='late':time.sleep(3);fixture.record('late_write_attempt')
     body=json.dumps({'choices':[{'message':{'role':'assistant','content':reply}}]},ensure_ascii=False).encode()
     self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    except (BrokenPipeError,ConnectionResetError):pass
    except (ValueError,KeyError,TypeError,IndexError):
     with fixture.lock:fixture.failures+=1
     fixture.save();self.send_error(400)
  self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler);self.server.daemon_threads=True
 def valid(self,s):
  if not isinstance(s,dict) or s.get('ownerId')==s.get('companionId'):return False
  if len(s.get('blocks',[]))>64 or len(s.get('inventory',[]))>41:return False
  pos=s['companionPosition'];x,y,z=(math.floor(pos[k]) for k in ('x','y','z'))
  return all(abs(b['x']-x)<=3 and abs(b['z']-z)<=3 and -2<=b['y']-y<=2 for b in s['blocks'])
 def save(self):
  with self.lock:j={'kind':'M2 protocol simulation; not real model','counts':dict(self.counts),'failures':self.failures,'bodies_stored':False}
  self.path.write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n')
 def record(self,label):
  with self.lock:self.counts[label]=self.counts.get(label,0)+1
  self.save()
 def start(self):
  self.save();threading.Thread(target=self.server.serve_forever,daemon=True).start();return self
 def close(self):self.server.shutdown();self.server.server_close()
 @property
 def configuration(self):return ('enabled=true\ndiagnostics.enabled=true\nmodel.provider=chat-completions\nmodel.endpoint=http://127.0.0.1:'+str(self.server.server_port)+'/v1/chat/completions\nmodel.name=m2-protocol-simulation\nmodel.api-key-env=\nmodel.allow-unauthenticated-local=true\nmodel.retries=0\nmodel.timeout-seconds=10\n')

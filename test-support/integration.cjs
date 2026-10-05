'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process');
const mineflayer=require('mineflayer');
const root='C:\\Users\\artyo\\Documents\\Codex\\nordqueuenotice-test-20261004';
const previous='C:\\Users\\artyo\\Documents\\Codex\\nordcommands-paper-test-20261004';
const source=path.resolve(__dirname,'..'),java='C:\\Program Files\\Java\\jdk-25\\bin\\java.exe';
const baseline=process.argv.includes('--baseline');
const preparedCommands=process.argv.includes('--prepared-commands');
assert(!(baseline&&preparedCommands));
const config=path.join(root,'proxy/plugins/nordqueuenotice/config.properties');
const queueConfig=path.join(root,'proxy/plugins/nordqueue/config.properties');
const results=[],children=[],clients=new Set();
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let paper,proxy,queue,seq=0,initialConfig;
function pass(name){results.push(name);console.log('PASS: '+name);}
async function until(test,label,timeout=15000){const start=Date.now();while(!await test()){if(Date.now()-start>timeout)throw Error('Timeout: '+label);await sleep(50);}}
async function closed(port){await new Promise((resolve,reject)=>{const socket=net.connect({host:'127.0.0.1',port});socket.setTimeout(1500);socket.once('connect',()=>{socket.destroy();reject(Error('Fixture port in use '+port));});socket.once('error',e=>{socket.destroy();if(e.code==='ECONNREFUSED')resolve();else reject(e);});socket.once('timeout',()=>{socket.destroy();reject(Error('Port check timeout '+port));});});}
function noticeConfig(interval=3,delay=750,message='QN_NOTICE_A'){
  return '#retain this LOCAL comment\ninterval-seconds='+interval+'\ninitial-delay-millis='+delay+'\nclear-lines=40\nmessage=<green>'+message+' <click:open_url:\'https://nord-fjell.com/\'>nord-fjell.com</click></green>\n';
}
function writeQueue(minimum=3600){fs.writeFileSync(queueConfig,'main-capacity=20\nminimum-wait-seconds='+minimum+'\ntransfer-interval-seconds=1\n');}
function fixture(){
  assert(root==='C:\\Users\\artyo\\Documents\\Codex\\nordqueuenotice-test-20261004');
  for(const sub of ['paper/config','paper/plugins/NordChat','paper/plugins/NordFilter','paper/plugins/NordCommands','proxy/plugins/nordqueue','proxy/plugins/nordqueuenotice','queue'])fs.mkdirSync(path.join(root,sub),{recursive:true});
  for(const version of ['1.0.0','1.1.0']){const old=path.join(root,'proxy/plugins/NordQueueNotice-'+version+'.jar');if(fs.existsSync(old))fs.renameSync(old,path.join(root,'retired-notice-'+version+'-'+Date.now()+'.jar'));}
  fs.copyFileSync(path.join(previous,'paper/server.jar'),path.join(root,'paper/server.jar'));
  fs.copyFileSync(path.join(previous,'proxy/velocity.jar'),path.join(root,'proxy/velocity.jar'));
  fs.copyFileSync(path.join(previous,'queue/NanoLimbo.jar'),path.join(root,'queue/NanoLimbo.jar'));
  for(const label of ['NordChat','NordFilter']){const version=label==='NordChat'?'0.1.3':'1.1.0';fs.copyFileSync('Z:\\Minecraft Plagins\\'+label+'\\releases\\'+version+'\\'+label+'-'+version+'.jar',path.join(root,'paper/plugins/'+label+'-'+version+'.jar'));}
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordCommandsPaper\\releases\\1.1.0\\NordCommands-Paper-1.1.0.jar',path.join(root,'paper/plugins/NordCommands-Paper-1.1.0.jar'));
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordQueue\\releases\\1.1.2\\NordQueue-1.1.2.jar',path.join(root,'proxy/plugins/NordQueue-1.1.2.jar'));
  fs.copyFileSync('Z:\\Minecraft Proxy\\plugins\\NordCommands-Velocity-1.0.0.jar',path.join(root,'proxy/plugins/NordCommands-Velocity-1.0.0.jar'));
  const preparedJar=path.join(root,'proxy/plugins/NordCommands-Velocity-1.1.0.jar');
  if(fs.existsSync(preparedJar))fs.renameSync(preparedJar,path.join(root,'retired-command-filter-1.1.0-'+Date.now()+'.jar'));
  if(preparedCommands){
    fs.renameSync(path.join(root,'proxy/plugins/NordCommands-Velocity-1.0.0.jar'),path.join(root,'retired-command-filter-1.0.0-'+Date.now()+'.jar'));
    fs.copyFileSync('Z:\\Minecraft Plagins\\NordCommandsVelocity\\releases\\1.1.0\\NordCommands-Velocity-1.1.0.jar',preparedJar);
  }
  fs.copyFileSync(baseline?'Z:\\Minecraft Proxy\\plugins\\NordQueueNotice-1.0.0.jar':path.join(source,'build/NordQueueNotice-1.1.0.jar'),path.join(root,'proxy/plugins/NordQueueNotice-'+(baseline?'1.0.0':'1.1.0')+'.jar'));
  fs.copyFileSync(path.join(__dirname,'build/QueueNoticeTestProbe.jar'),path.join(root,'proxy/plugins/QueueNoticeTestProbe.jar'));
  fs.writeFileSync(path.join(root,'paper/eula.txt'),'eula=true\n');
  fs.writeFileSync(path.join(root,'paper/server.properties'),[
    'server-ip=127.0.0.1','server-port=25706','online-mode=false','enforce-secure-profile=false',
    'max-players=20','view-distance=2','simulation-distance=2','enable-rcon=false','enable-query=false',
    'level-name=nordnotice-synthetic-world','level-type=minecraft:flat','generate-structures=false',
    'spawn-protection=0','pause-when-empty-seconds=-1','gamemode=creative','force-gamemode=true','difficulty=peaceful',''].join('\n'));
  const secret=require('node:crypto').randomBytes(32).toString('hex');
  const global=fs.readFileSync(path.join(previous,'paper/config/paper-global.yml'),'utf8');
  const section=/  velocity:\r?\n    enabled: (?:true|false)\r?\n    online-mode: (?:true|false)\r?\n    secret: [^\r\n]*/;assert(section.test(global));
  fs.writeFileSync(path.join(root,'paper/config/paper-global.yml'),global.replace(section,'  velocity:\n    enabled: true\n    online-mode: false\n    secret: "'+secret+'"'));
  let velocity=fs.readFileSync(path.join(previous,'proxy/velocity.toml'),'utf8').replaceAll('25685','25705').replaceAll('25686','25706').replaceAll('25687','25707')
    .replace(/read-timeout = \d+/,'read-timeout = 60000');
  assert(velocity.includes('bind = "127.0.0.1:25705"')&&velocity.includes('try = ["queue"]'));
  fs.writeFileSync(path.join(root,'proxy/velocity.toml'),velocity);fs.writeFileSync(path.join(root,'proxy/local-test-forwarding.secret'),secret);
  let limbo=fs.readFileSync(path.join(previous,'queue/settings.yml'),'utf8').replaceAll('25687','25707').replace(/  secret: "[^"]+"/,'  secret: "'+secret+'"');
  assert(limbo.includes('type: MODERN'));fs.writeFileSync(path.join(root,'queue/settings.yml'),limbo);
  fs.writeFileSync(path.join(root,'paper/plugins/NordChat/config.yml'),'temporary-ignore-days: 7\nprivate-message-cooldown-millis: 500\nclickable-chat-names: true\n');
  fs.writeFileSync(path.join(root,'paper/plugins/NordChat/players.yml'),'{}\n');
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordFilter\\src\\main\\resources\\config.yml',path.join(root,'paper/plugins/NordFilter/config.yml'));
  fs.writeFileSync(path.join(root,'paper/plugins/NordFilter/banwords.yml'),'words: [syntheticforbidden]\n');fs.writeFileSync(path.join(root,'paper/plugins/NordFilter/data.yml'),'{}\n');
  fs.writeFileSync(path.join(root,'paper/plugins/NordCommands/config.yml'),'denied-message: No such command.\nallowed-commands: [msg, login, register]\n');
  initialConfig=noticeConfig(3,7000);fs.writeFileSync(config,initialConfig);writeQueue();
}
function start(name,jar,heap){const child=spawn(java,['-Xms64M','-Xmx'+heap,'-jar',jar,...(name==='paper'?['nogui']:[])],{cwd:path.join(root,name),windowsHide:true,stdio:['pipe','pipe','pipe']});
  const handle={name,child,output:'',exited:false,index:children.filter(h=>h.name===name).length};children.push(handle);
  for(const stream of [child.stdout,child.stderr])stream.on('data',b=>handle.output+=b.toString().replace(/\x1b\[[0-9;]*m/g,''));
  child.on('exit',()=>handle.exited=true);child.on('error',e=>{handle.output+=String(e);handle.exited=true;});return handle;
}
async function startProxy(){proxy=start('proxy','velocity.jar','512M');await until(()=>proxy.exited||/Done \(/.test(proxy.output),'proxy startup',45000);assert(!proxy.exited,proxy.output.slice(-3500));assert(proxy.output.includes('QNPROBE_READY'));if(!baseline)assert(proxy.output.includes('NordQueueNotice 1.1.0:'));}
async function control(text){const mark=proxy.output.length;proxy.child.stdin.write('qntest '+text+'\n');await until(()=>proxy.output.slice(mark).includes('QNTEST_OK '+text)||proxy.output.slice(mark).includes('QNTEST_FAILED'),'control '+text,20000);assert(!proxy.output.slice(mark).includes('QNTEST_FAILED'),proxy.output.slice(mark));}
async function state(){const id='s'+(++seq);await control('state '+id);const match=proxy.output.match(new RegExp('QNSTATE '+id+' (\\{[^\\r\\n]+\\})'));assert(match,proxy.output.slice(-1500));return JSON.parse(match[1]);}
async function inServer(name,server){await until(async()=>{const s=await state();return s[server].includes(name);},name+' on '+server,30000);}
function connect(name){const bot=mineflayer.createBot({host:'127.0.0.1',port:25705,username:name,auth:'offline',version:'26.2',hideErrors:true,checkTimeoutInterval:30000});
  const c={bot,name,messages:[],ended:false,errors:[]};clients.add(c);bot.on('message',m=>c.messages.push({text:m.unsigned?m.unsigned.toString():m.toString(),time:Date.now()}));
  bot.on('end',()=>c.ended=true);bot.on('error',e=>c.errors.push(String(e)));bot.on('kicked',r=>c.kicked=JSON.stringify(r));return c;
}
function notices(c,marker='QN_NOTICE_A',after=0){return c.messages.filter(m=>m.time>=after&&m.text.includes(marker));}
async function joined(c){await inServer(c.name,'queue');assert(!c.ended,JSON.stringify({name:c.name,errors:c.errors,kicked:c.kicked}));c.joined=Date.now();}
async function expect(c,regex,from=0){await until(()=>c.messages.slice(from).some(m=>regex.test(m.text))||c.ended,'message '+regex,20000);assert(c.messages.slice(from).some(m=>regex.test(m.text)),JSON.stringify({messages:c.messages,errors:c.errors,kicked:c.kicked}));}
async function disconnect(c){if(!c.ended)c.bot.quit();await until(()=>c.ended,'disconnect '+c.name);await sleep(400);}
async function cmd(c,text,regex){await sleep(1100);const mark=c.messages.length;c.bot.chat(text);await expect(c,regex,mark);}
async function admin(text,regex){const mark=proxy.output.length;proxy.child.stdin.write('nordqueuenotice '+text+'\n');await until(()=>regex.test(proxy.output.slice(mark)),'admin '+text);return mark;}
async function reload(valid=true){const mark=await admin('reload',/reload queued/);await until(()=>proxy.output.slice(mark).includes(valid?'configuration reloaded.':'reload rejected;'),'reload result',15000);}
async function stopProxy(){for(const c of clients)if(!c.ended)c.bot.quit();if(proxy&&!proxy.exited){proxy.child.stdin.write('shutdown\n');await until(()=>proxy.exited,'proxy stop',30000);}await sleep(300);}
async function cleanup(){await stopProxy();if(paper&&!paper.exited){paper.child.stdin.write('stop\n');await until(()=>paper.exited,'Paper stop',45000);}if(queue&&!queue.exited){queue.child.kill();await until(()=>queue.exited,'owned isolated limbo stop',10000);}for(const h of children)fs.writeFileSync(path.join(root,h.name+'-'+h.index+'-runtime.log'),h.output);}
async function run(){
  for(const port of [25705,25706,25707])await closed(port);fixture();
  paper=start('paper','server.jar','2G');await until(()=>paper.exited||/Done \(/.test(paper.output),'Paper startup',120000);assert(!paper.exited,paper.output.slice(-3000));
  queue=start('queue','NanoLimbo.jar','256M');await until(()=>queue.exited||/NanoLimbo started|Listening|Server started/.test(queue.output),'limbo startup',30000);assert(!queue.exited,queue.output);
  await startProxy();const a=connect('QNAlpha'),b=connect('QNBeta');await joined(a);await joined(b);
  if(baseline){
    assert.notEqual(fs.readFileSync(config,'utf8'),initialConfig);pass('OLD: startup rewrites existing comments/config formatting');
    await until(()=>notices(a).length>0,'old premature initial notice',5500);assert(notices(a)[0].time-a.joined<6000);pass('OLD: periodic loop ignores 7000ms initial delay when interval is 3 seconds');
    fs.writeFileSync(config,noticeConfig(3,750));const bytes=fs.readFileSync(config),mark=a.messages.length;
    await control('direct QNAlpha');await expect(a,/NordQueueNotice reloaded/,mark);assert(!fs.readFileSync(config).equals(bytes));pass('OLD: trusted direct executor reloads without checking player permission and rewrites config');
    const tasks=(await state()).tasks,count=notices(a).length;await control('repeat QNAlpha 6');const after=await state();assert(after.tasks>=tasks+6,JSON.stringify(after));
    await until(()=>notices(a).length>=count+6,'old duplicate delayed notices',4000);pass('OLD: six duplicate connect callbacks create six pending tasks and force repeated frames');return;
  }
  assert.equal(fs.readFileSync(config,'utf8'),initialConfig);pass('Existing valid production-format Properties retained byte-for-byte at startup');
  await control('repeat QNAlpha 20');let s=await state();assert.equal(s.tasks,1);assert.equal(s.tracked,2);
  await sleep(Math.max(0,6300-(Date.now()-a.joined)));assert.equal(notices(a).length,0);
  await until(()=>notices(a).length===1,'first notice honors delay',2500);await until(()=>notices(b).length===1,'second first notice',2500);
  pass('Initial delay greater than interval is honored; repeated callbacks retain one ticker and one deadline per session');
  const first=notices(a)[0];assert(first.text.startsWith('\n'.repeat(40)));assert(first.text.includes('nord-fjell.com'));
  await until(()=>notices(a).length>=2,'repeat notice',4500);assert(notices(a)[1].time-first.time>=2800);
  pass('Clear lines and clickable-website notice use one cached frame; repeats are interval-separated');
  fs.writeFileSync(config,noticeConfig(3,750));const validBytes=fs.readFileSync(config),directMark=a.messages.length;
  await control('direct QNAlpha');await expect(a,/do not have permission/,directMark);assert(fs.readFileSync(config).equals(validBytes));assert.equal((await state()).tasks,1);
  pass('Direct reload executor rejects unauthorized player without touching config or tasks');
  await control('permission QNAlpha nordcommands.bypass true');await cmd(a,'/nordqueuenotice reload',/unknown|permission|command/i);assert(fs.readFileSync(config).equals(validBytes));
  pass('Normal proxy command route still requires notice admin permission even with broad command-filter bypass');
  await control('permission QNAlpha nordqueuenotice.admin true');await cmd(a,'/nordqueuenotice health',/NordQueueNotice: ready/);
  let publication=proxy.output.length;await cmd(a,'/nordqueuenotice',/reload queued/);await until(()=>proxy.output.slice(publication).includes('config published'),'authorized reload');
  assert(fs.readFileSync(config).equals(validBytes));assert.equal((await state()).tasks,1);
  await control('permission QNAlpha nordqueuenotice.admin false');await cmd(a,'/nordqueuenotice reload',/unknown|permission|command/i);await control('permission QNAlpha nordcommands.bypass false');
  pass('Authorized legacy no-argument reload works without rewriting config; permission revocation takes effect');
  await control('slow 2000');publication=await admin('reload',/reload queued/);const began=Date.now();await admin('health',/reload pending=true/);assert(Date.now()-began<1500);await admin('reload',/already pending/);
  await until(()=>proxy.output.slice(publication).includes('configuration reloaded.'),'slow config reader');await control('slow 0');s=await state();assert.equal(s.tasks,1);assert.equal(s.workers,1);
  pass('Slow read leaves health responsive; one pending reload, one reader, one unchanged ticker');
  const frame=s.frame;fs.writeFileSync(config,noticeConfig('9223372036854775807',0,'QN_BROKEN'));const invalidBytes=fs.readFileSync(config);await reload(false);s=await state();assert.equal(s.frame,frame);assert.equal(s.interval,3);assert(fs.readFileSync(config).equals(invalidBytes));
  pass('Invalid/overflow reload retains previous complete cached settings and leaves malformed file untouched');
  fs.writeFileSync(config,noticeConfig(3,0,'QN_NOTICE_B'));await reload();s=await state();assert.notEqual(s.frame,frame);const newer=Date.now();await until(()=>notices(b,'QN_NOTICE_B',newer).length>0,'new notice frame',4500);assert.equal((await state()).tasks,1);
  pass('Valid reload atomically publishes new cached frame without restarting ticker');
  await control('unqueue QNBeta');await sleep(600);const suspended=Date.now();await sleep(3400);assert.equal(notices(b,'QN_NOTICE_B',suspended).length,0);await control('enqueue QNBeta');await until(()=>notices(b,'QN_NOTICE_B',Date.now()-500).length>0,'resumed queue notices',5000);
  pass('Limbo player absent from queue membership gets no notice; eligible re-enqueue resumes notices');
  await control('remember QNAlpha');await disconnect(a);await until(async()=>((await state()).tracked===1),'disconnect cleanup');const again=connect('QNAlpha');await joined(again);await control('stalequit');assert.equal((await state()).tracked,2);await until(()=>notices(again,'QN_NOTICE_B').length>0,'fresh session notice',4500);
  pass('Disconnect clears state; stale old-session event cannot remove reconnected session');
  // Move real players via NordQueue's existing admission path, not a bypass.
  fs.writeFileSync(config,noticeConfig(3,60000,'QN_DELAYED_MAIN'));await reload();writeQueue(0);proxy.child.stdin.write('nordqueue reload\n');await inServer('QNAlpha','main');await inServer('QNBeta','main');
  const mainAt=Date.now();await sleep(3500);assert.equal(notices(again,'QN_DELAYED_MAIN',mainAt).length,0);assert.equal(notices(b,'QN_DELAYED_MAIN',mainAt).length,0);
  assert(!again.messages.some(m=>m.time>=mainAt&&m.text==='\n'.repeat(40)));assert.equal((await state()).tracked,0);
  pass('Real MODERN queue-to-Paper transfer removes pending initial notices and sends no repeated blank/advert frames on main');
  await control('slow 10000');await admin('reload',/reload queued/);const shutdownBegan=Date.now();await control('shutdown');assert(Date.now()-shutdownBegan<3000);s=await state();assert.equal(s.tasks,0);assert.equal(s.workers,0);assert.equal(s.tracked,0);await control('tick');
  pass('Plugin shutdown cancels ticker, interrupts reader and rejects late work');
  await stopProxy();writeQueue();fs.writeFileSync(config,noticeConfig(0,0,'QN_INVALID_START'));const brokenStartup=fs.readFileSync(config);await startProxy();const blocked=connect('QNBlocked');await joined(blocked);await sleep(3400);assert.equal(notices(blocked,'QN_INVALID_START').length,0);assert(fs.readFileSync(config).equals(brokenStartup));s=await state();assert.equal(s.ready,false);
  pass('Invalid initial config leaves notices disabled without overwriting file or starting per-player jobs');
  fs.writeFileSync(config,noticeConfig(3,0,'QN_RECOVERED'));const repaired=fs.readFileSync(config);await reload();await until(()=>notices(blocked,'QN_RECOVERED').length>0,'recover initial config');assert.equal((await state()).ready,true);assert(fs.readFileSync(config).equals(repaired));
  pass('Valid owner reload recovers disabled initial config while preserving repaired bytes');
}
(async()=>{let failure;try{await run();}catch(error){failure=error;console.error(error.stack);}finally{try{await cleanup();}catch(error){failure=failure||error;console.error(error.stack);}fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({passed:!failure,baseline,nordCommandsVelocity:preparedCommands?'1.1.0':'1.0.0',results,error:failure?String(failure):null,ordinaryMinecraftClientTested:false,thousandConcurrentConnectionsTested:false},null,2)+'\n');}if(failure)process.exitCode=1;})();

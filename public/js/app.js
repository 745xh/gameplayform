// ═══════════════════════════════════════════════════════════════════
// 小游戏平台 - 客户端逻辑
// 使用 EventSource (SSE) + Fetch API, 零外部依赖
// ═══════════════════════════════════════════════════════════════════

const DICE_FACES = ["⚀","⚁","⚂","⚃","⚄","⚅"];
const state = {
  playerId: null, playerName: "", roomCode: null,
  isHost: false, inRoom: false, gamePhase: "lobby",
  roundNumber: 0, currentPlayers: [], eventSource: null, gameData: {},
};
const $ = (id) => document.getElementById(id);
function P(pid) { const p = state.currentPlayers.find(pl => pl.id === pid); return p ? p.name : null; }
function showPage(id) { document.querySelectorAll(".page").forEach(p => p.classList.remove("active")); $(id)?.classList.add("active"); }
function df(n) { return DICE_FACES[Math.min(Math.max(n-1,0),5)]; }
function pn() { return state.playerName || `玩家${Math.floor(Math.random()*900)+100}`; }
function noti(t,ty="") { const e=$("notification-text"),c=$("game-notification"); if(e)e.textContent=t; if(c)c.className="game-notification"+(ty?" "+ty:""); }
function acts(html) { const a=$("action-area"); if(a)a.innerHTML=html; }
function toast(text,dur=2000) {
  const e=document.querySelector(".message-toast"); if(e)e.remove();
  const t=document.createElement("div"); t.className="message-toast"; t.textContent=text;
  const c=document.querySelector(".table-container"); if(c)c.appendChild(t);
  setTimeout(()=>t.remove(),dur);
}
function av(pid) { return document.querySelector(`.game-avatar[data-player-id="${pid}"]`); }
function avDice(pid,val) {
  const a=av(pid); if(!a)return; let b=a.querySelector(".dice-badge");
  if(!b){b=document.createElement("div");b.className="dice-badge";a.appendChild(b);}
  b.textContent=val; b.classList.remove("high-dice","low-dice");
  if(val>=5)b.classList.add("high-dice"); else if(val<=2)b.classList.add("low-dice");
}
function showModal(title, bodyHTML, buttons) {
  const o=$("modal-overlay"),c=$("modal-content"); if(!o||!c)return;
  let h=`<div class="modal-title">${title}</div><div class="modal-text">${bodyHTML}</div>`;
  if(buttons&&buttons.length){h+=`<div class="modal-actions">`;buttons.forEach((b,i)=>{h+=`<button id="mb-${i}" class="${b.class}">${b.text}</button>`;});h+=`</div>`;}
  c.innerHTML=h;o.classList.remove("hidden");
  if(buttons)buttons.forEach((b,i)=>{$(`mb-${i}`)?.addEventListener("click",b.action);});
}
function closeModal() { $("modal-overlay")?.classList.add("hidden"); }
function renderPlayers(players) {
  const l=$("player-list"),c=$("player-count"); if(!l)return;
  const co=players.filter(p=>p.connected!==false); if(c)c.textContent=`${co.length}/8`;
  l.innerHTML=players.map(p=>{
    let b=[]; if(p.isHost)b.push('<span class="badge badge-host">房主</span>');
    if(p.ready)b.push('<span class="badge badge-ready">已准备</span>'); else b.push('<span class="badge badge-not-ready">未准备</span>');
    if(p.connected===false)b.push('<span class="badge badge-disconnected">已断线</span>');
    return `<div class="player-item${p.id===state.playerId?" highlight":""}"><div class="avatar-circle">${p.avatarNumber}</div><div class="player-info"><div class="player-name">${p.name}${p.id===state.playerId?" (你)":""}</div><div class="player-badges">${b.join("")}</div></div></div>`;
  }).join("");
}
function upActions(players) {
  const r=$("ready-btn"),s=$("start-game-btn"); if(!r)return;
  const me=players.find(p=>p.id===state.playerId);
  if(me){r.textContent=me.ready?"取消准备":"准备";r.className=me.ready?"btn btn-secondary btn-large":"btn btn-primary btn-large";}
  if(state.isHost){const all=players.filter(p=>p.connected!==false).every(p=>p.ready);if(all&&players.filter(p=>p.connected!==false).length>=3)s?.classList.remove("hidden");else s?.classList.add("hidden");}else s?.classList.add("hidden");
}
function showResults(rolls,data) {
  $("dice-center")?.classList.add("hidden");
  Object.entries(rolls).forEach(([pid,v])=>{avDice(pid,v);});
  document.querySelectorAll(".game-avatar").forEach(e=>e.classList.remove("winner","loser"));
  if(data.winner){const e=av(data.winner);if(e)e.classList.add("winner");}
  if(data.loser){const e=av(data.loser);if(e)e.classList.add("loser");}
}
function handleRound(winnerId,loserId) {
  const wn=P(winnerId)||"未知",ln=P(loserId)||"未知";
  document.querySelectorAll(".game-avatar").forEach(e=>e.classList.remove("winner","loser"));
  const wE=av(winnerId);if(wE)wE.classList.add("winner");const lE=av(loserId);if(lE)lE.classList.add("loser");
  noti(`🏆 ${wn} 胜出！ 😅 ${ln} 是输家！`,"important");toast(`🏆 ${wn} 胜出！`,2000);
}
async function api(path,data) { try{const r=await fetch('/api'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});return await r.json();}catch(e){toast('网络错误',3000);return null;} }

function connectSSE(pid) {
  if(state.eventSource)state.eventSource.close();
  const es=new EventSource('/events?player_id='+pid);
  state.eventSource=es;

  // 房间事件
  es.addEventListener('room_state',e=>{const d=JSON.parse(e.data);
    state.currentPlayers=d.players||[];state.isHost=d.players?.some(p=>p.id===state.playerId&&p.isHost)||false;
    renderPlayers(d.players);upActions(d.players);
    if(d.phase==="playing"&&state.gamePhase==="room"){state.gamePhase="game";showPage("page-game");setTimeout(initTable,150);}
  });
  es.addEventListener('player_joined',e=>{const d=JSON.parse(e.data);noti(`${d.name} 加入了房间`);});
  es.addEventListener('player_disconnected',()=>noti("有玩家断开连接","warning"));
  es.addEventListener('all_ready',()=>{if(state.isHost)$("start-game-btn")?.classList.remove("hidden");});
  es.addEventListener('game_starting',()=>noti("游戏即将开始..."));

  // 游戏事件
  es.addEventListener('round_start',e=>{const d=JSON.parse(e.data);
    state.roundNumber=d.round;state.myDiceValue=null;state.rolled=false;
    $("round-display").textContent=`第 ${d.round} 轮`;noti(`第 ${d.round} 轮开始！点击掷骰子 🎲`);
    document.querySelectorAll(".dice-badge").forEach(e=>e.remove());
    document.querySelectorAll(".game-avatar.winner,.game-avatar.loser").forEach(e=>e.classList.remove("winner","loser"));
    $("dice-center")?.classList.add("hidden");
    acts(`<button id="roll-btn" class="btn btn-primary btn-large pulse-animation">🎲 掷骰子</button>`);
    $("roll-btn")?.addEventListener("click",()=>{api('/roll_dice',{playerId:state.playerId});$("dice-center")?.classList.remove("hidden");});
    initTable();
  });

  es.addEventListener('dice_rolled',e=>{const d=JSON.parse(e.data);
    state.myDiceValue=d.value;state.rolled=true;
    toast(`你掷出了 ${df(d.value)} ${d.value}点！`,1500);avDice(state.playerId,d.value);
    const btn=$("roll-btn");if(btn){btn.disabled=true;btn.textContent=`✅ 已掷 (${df(d.value)} ${d.value}点)`;btn.classList.remove("pulse-animation");}
    noti("等待其他玩家掷骰子...");
  });

  es.addEventListener('dice_result',e=>{const d=JSON.parse(e.data);showResults(d.rolls,d);
    if(d.tieBreak){noti("有玩家点数相同，需要重掷！","important");
      if(d.tiePlayers?.includes(state.playerId)){acts(`<button id="tie-btn" class="btn btn-primary btn-large pulse-animation">🎲 重掷骰子</button>`);$("tie-btn")?.addEventListener("click",()=>api('/roll_dice',{playerId:state.playerId}));}
      else acts(`<p class="action-hint">等待平局玩家重掷...</p>`);
    }else handleRound(d.winner,d.loser);
  });
  es.addEventListener('tie_break_result',e=>{const d=JSON.parse(e.data);handleRound(d.winner,d.loser);});
  es.addEventListener('tie_dice_rolled',e=>{const d=JSON.parse(e.data);state.myDiceValue=d.value;state.rolled=true;toast(`重掷结果: ${df(d.value)} ${d.value}点！`,1500);});

  es.addEventListener('choose_truth_or_dare',e=>{const d=JSON.parse(e.data);
    if(d.loserId===state.playerId){noti("你是输家！请选择：真心话还是大冒险？","important");
      showModal("选择挑战","你是本轮输家，请选择：",[
        {text:"💬 真心话",class:"btn btn-primary",action:()=>{api('/choose',{playerId:state.playerId,choice:"truth"});closeModal();}},
        {text:"🔥 大冒险",class:"btn btn-danger",action:()=>{api('/choose',{playerId:state.playerId,choice:"dare"});closeModal();}},
      ]);
    }else{noti("输家正在选择...");acts(`<p class="action-hint">等待输家选择...</p>`);}
  });
  es.addEventListener('truth_or_dare_chosen',e=>{const d=JSON.parse(e.data);const l=d.choice==="truth"?"💬 真心话":"🔥 大冒险";noti(`输家选择了 ${l}！`);});

  es.addEventListener('content_input_prompt',e=>{const d=JSON.parse(e.data);const lb=d.choice==="truth"?"真心话问题":"大冒险挑战";
    if(d.winnerId===state.playerId){const o=$("modal-overlay"),c=$("modal-content");if(!o||!c)return;
      c.innerHTML=`<div class="modal-title">输入${lb}</div><div class="modal-text">你是赢家！请为输家设计${lb}：</div><textarea id="m-input" class="modal-input" placeholder="输入${lb}内容..." maxlength="200"></textarea><div class="modal-actions"><button id="m-sub" class="btn btn-primary">提交</button></div>`;
      o.classList.remove("hidden");setTimeout(()=>$("m-input")?.focus(),300);
      $("m-sub")?.addEventListener("click",()=>{const v=$("m-input")?.value.trim();if(!v){toast("请输入内容");return;}
        api('/submit_content',{playerId:state.playerId,content:v});o.classList.add("hidden");acts(`<p class="action-hint">内容已提交</p>`);noti("内容已提交");});
    }else{noti(`赢家正在输入${lb}...`);acts(`<p class="action-hint">等待赢家输入...</p>`);}
  });

  es.addEventListener('content_revealed',e=>{const d=JSON.parse(e.data);state.gameData||={};state.gameData.content=d.content;noti(`内容已公布！`,"important");});

  es.addEventListener('vote_content_prompt',e=>{const d=JSON.parse(e.data);
    if(d.excludePlayerId===state.playerId){noti("等待其他玩家投票...");acts(`<p class="action-hint">等待投票结果...</p>`);return;}
    showModal("投票：内容是否合适？",`<div class="modal-text" style="font-size:16px;font-weight:600;color:var(--text-primary);background:var(--bg-secondary);padding:12px;border-radius:12px;">${d.content}</div><p style="color:var(--text-muted);font-size:13px;">所有非输家玩家需全体同意</p>`, [
      {text:"✅ 同意",class:"btn btn-success",action:()=>{api('/vote_content',{playerId:state.playerId,agree:true});closeModal();acts(`<p class="action-hint">已投票：同意</p>`);}},
      {text:"❌ 不同意",class:"btn btn-danger",action:()=>{api('/vote_content',{playerId:state.playerId,agree:false});closeModal();acts(`<p class="action-hint">已投票：不同意</p>`);}},
    ]);
  });
  es.addEventListener('content_approved',()=>noti("✅ 全体通过！"));
  es.addEventListener('content_rejected',()=>toast("有人不同意，重新输入",2000));

  es.addEventListener('loser_decide_prompt',e=>{const d=JSON.parse(e.data);
    if(d.loserId===state.playerId){showModal("接受还是拒绝？",`<div class="modal-text" style="font-size:16px;font-weight:600;color:var(--text-primary);background:var(--bg-secondary);padding:12px;border-radius:12px;">${d.content}</div>`, [
      {text:"✅ 接受",class:"btn btn-success",action:()=>{api('/loser_accept',{playerId:state.playerId});closeModal();}},
      {text:"🃏 拒绝（抽卡）",class:"btn btn-danger",action:()=>{api('/loser_reject',{playerId:state.playerId});closeModal();}},
    ]);}else{noti("等待输家决定...");acts(`<p class="action-hint">等待输家决定...</p>`);}
  });
  es.addEventListener('loser_accepted',()=>noti("输家接受了挑战！"));
  es.addEventListener('card_drawn',e=>{const d=JSON.parse(e.data);const t=d.type==="truth"?"💬真心话":"🔥大冒险";
    noti(`🃏 抽到${t}卡牌！`,"important");toast(`抽卡结果：${d.card}`,3000);
  });

  es.addEventListener('execute_prompt',e=>{const d=JSON.parse(e.data);
    if(d.loserId===state.playerId){noti(`请执行：${d.content}`,"important");
      acts(`<p class="action-hint">第 ${d.executionCount} 次执行</p><button id="exec-btn" class="btn btn-success btn-large">✅ 执行完毕</button>`);
      $("exec-btn")?.addEventListener("click",()=>{api('/execution_done',{playerId:state.playerId});acts(`<p class="action-hint">等待满意度投票...</p>`);});
    }else{noti(`输家正在执行：${d.content}`);acts(`<p class="action-hint">等待输家执行完毕...</p>`);}
  });

  es.addEventListener('vote_satisfaction_prompt',e=>{const d=JSON.parse(e.data);
    if(d.excludePlayerId===state.playerId){noti("等待其他玩家评价...");acts(`<p class="action-hint">等待评价结果...</p>`);return;}
    showModal("投票：是否满意？","<p>对输家的执行结果是否满意？</p><p style=\"color:var(--text-muted);font-size:13px;\">超过一半满意才算通过</p>",[
      {text:"😊 满意",class:"btn btn-success",action:()=>{api('/vote_satisfaction',{playerId:state.playerId,satisfied:true});closeModal();acts(`<p class="action-hint">已投票：满意</p>`);}},
      {text:"😞 不满意",class:"btn btn-danger",action:()=>{api('/vote_satisfaction',{playerId:state.playerId,satisfied:false});closeModal();acts(`<p class="action-hint">已投票：不满意</p>`);}},
    ]);
  });
  es.addEventListener('satisfaction_result',e=>{const d=JSON.parse(e.data);
    if(d.passed){if(d.maxAttempts)noti("⏰ 已达最大执行次数，通过！");else noti(`😊 满意度 ${d.satisfied}/${d.total}，通过！`);}
    else{noti("😞 满意度未通过，重新执行");toast("需重新执行",2000);}
  });

  es.addEventListener('round_ended',e=>{const d=JSON.parse(e.data);
    noti(d.message||"🎉 本轮结束！","important");
    const hb=state.isHost?`<button id="nr-btn" class="btn btn-success btn-large">下一轮</button>`:`<p class="action-hint">等待房主开始下一轮...</p>`;
    acts(`<div class="round-end-score"><div class="score-item"><div class="score-label">🏆 赢家</div><div class="score-value" style="color:var(--warning)">${P(d.winnerId)||"未知"}</div></div><div class="score-item"><div class="score-label">😅 输家</div><div class="score-value" style="color:var(--danger)">${P(d.loserId)||"未知"}</div></div></div>${hb}`);
    $("nr-btn")?.addEventListener("click",()=>api('/next_round',{playerId:state.playerId}));
  });

  es.addEventListener('game_message',e=>{const d=JSON.parse(e.data);if(d.type==="error"){toast(d.message,3000);noti(d.message,"warning");}});
  es.addEventListener('error',e=>{console.error('SSE error:',e);});
}

// ── 圆桌绘制 ──────────────────────────────────────────────────
function initTable() {
  const container=document.querySelector(".table-container"),canvas=$("table-canvas");
  if(!container||!canvas)return;
  const cr=container.getBoundingClientRect(),size=Math.min(cr.width,cr.height)*0.9;
  const dpr=window.devicePixelRatio||1;
  canvas.width=size*dpr;canvas.height=size*dpr;
  canvas.style.width=size+"px";canvas.style.height=size+"px";
  const ctx=canvas.getContext("2d");ctx.scale(dpr,dpr);
  const cx=size/2,cy=size/2,R=size/2-4,iR=R*0.35;
  ctx.clearRect(0,0,size,size);
  ctx.beginPath();ctx.arc(cx,cy,R,0,Math.PI*2);ctx.fillStyle="#5a3a1a";ctx.fill();
  const sR=R-10;ctx.beginPath();ctx.arc(cx,cy,sR,0,Math.PI*2);
  const g=ctx.createRadialGradient(cx,cy,0,cx,cy,sR);
  g.addColorStop(0,"#3d7a37");g.addColorStop(0.7,"#2d5a27");g.addColorStop(1,"#1e4a18");
  ctx.fillStyle=g;ctx.fill();
  ctx.beginPath();ctx.arc(cx,cy,iR,0,Math.PI*2);
  const ig=ctx.createRadialGradient(cx,cy,0,cx,cy,iR);
  ig.addColorStop(0,"#4a3a1a");ig.addColorStop(1,"#3a2a0a");ctx.fillStyle=ig;ctx.fill();
  ctx.strokeStyle="rgba(255,255,255,0.1)";ctx.lineWidth=1;ctx.stroke();
  const pc=state.currentPlayers?.length||4;
  for(let i=0;i<pc;i++){const a=(2*Math.PI/pc)*i-Math.PI/2;ctx.beginPath();
    ctx.moveTo(cx+Math.cos(a)*iR,cy+Math.sin(a)*iR);ctx.lineTo(cx+Math.cos(a)*sR,cy+Math.sin(a)*sR);
    ctx.strokeStyle="rgba(255,255,255,0.12)";ctx.setLineDash([3,5]);ctx.lineWidth=1.5;ctx.stroke();}
  ctx.setLineDash([]);
  const ca=$("card-area");if(ca){ca.style.width=iR*2+"px";ca.style.height=iR*2+"px";}
  const ov=$("avatar-overlay");if(!ov)return;ov.innerHTML="";ov.style.pointerEvents="none";
  const pl=state.currentPlayers||[],cnt=pl.length;if(!cnt)return;
  const aR=R+6,aS=48;
  pl.forEach((p,i)=>{const a=(2*Math.PI/cnt)*i-Math.PI/2;
    const x=cx+Math.cos(a)*aR-aS/2,y=cy+Math.sin(a)*aR-aS/2;
    const d=document.createElement("div");d.className="game-avatar";d.dataset.playerId=p.id;
    d.style.cssText=`left:${x}px;top:${y}px;pointer-events:auto;`;d.textContent=p.avatarNumber;
    const l=document.createElement("span");l.className="avatar-label";l.textContent=p.name;d.appendChild(l);
    if(p.id===state.playerId){d.style.borderColor="var(--accent-light)";d.style.boxShadow="0 0 12px rgba(162,155,254,0.5)";}
    ov.appendChild(d);
  });
}

// ── UI 事件绑定 ──────────────────────────────────────────────
function bindUI() {
  $("enter-platform-btn")?.addEventListener("click",()=>{const n=$("player-name-input")?.value.trim();state.playerName=n||`玩家${Math.floor(Math.random()*900)+100}`;$("lobby-player-name").textContent=state.playerName;showPage("page-lobby");});
  $("player-name-input")?.addEventListener("keydown",e=>{if(e.key==="Enter")$("enter-platform-btn")?.click();});
  document.querySelectorAll(".create-room-btn").forEach(b=>{b.addEventListener("click",async()=>{const d=await api('/create_room',{name:pn()});if(!d)return;state.playerId=d.playerId;state.roomCode=d.code;state.isHost=true;state.inRoom=true;state.gamePhase="room";$("room-code-display").textContent=d.code;$("join-room-section")?.classList.add("hidden");showPage("page-room");connectSSE(d.playerId);});});
  document.querySelectorAll(".join-room-btn").forEach(b=>{b.addEventListener("click",()=>{$("join-room-section")?.classList.remove("hidden");setTimeout(()=>$("join-code-input")?.focus(),100);});});
  $("join-confirm-btn")?.addEventListener("click",async()=>{const c=$("join-code-input")?.value.trim();if(!c||c.length!==4){toast("请输入4位房间号");return;}const d=await api('/join_room',{code:c,name:pn()});if(!d)return;state.playerId=d.playerId;state.roomCode=d.code;state.inRoom=true;state.isHost=false;$("room-code-display").textContent=d.code;$("join-room-section")?.classList.add("hidden");showPage("page-room");connectSSE(d.playerId);});
  $("join-code-input")?.addEventListener("keydown",e=>{if(e.key==="Enter")$("join-confirm-btn")?.click();});
  $("ready-btn")?.addEventListener("click",()=>api('/toggle_ready',{playerId:state.playerId}));
  $("start-game-btn")?.addEventListener("click",()=>api('/start_game',{playerId:state.playerId}));
  [$("leave-room-btn"),$("room-back-btn"),$("game-leave-btn")].forEach(b=>{b?.addEventListener("click",()=>{api('/leave_room',{playerId:state.playerId});state.inRoom=false;state.roomCode=null;state.gamePhase="lobby";if(state.eventSource){state.eventSource.close();state.eventSource=null;}showPage("page-lobby");$("join-room-section")?.classList.add("hidden");});});
}

// ── 自适应 ───────────────────────────────────────────────────
let rt=null;window.addEventListener("resize",()=>{clearTimeout(rt);rt=setTimeout(()=>{if(state.gamePhase==="game")initTable();},200);});

// ══════════════════════════════════════════════════════════════
// 启动
// ══════════════════════════════════════════════════════════════
document.addEventListener("DOMContentLoaded",()=>{bindUI();showPage("page-name");setTimeout(()=>$("player-name-input")?.focus(),300);});
console.log("🎮 小游戏平台已加载");

(function(){
if(window.__gt)return;window.__gt=1;
var cfg=window.__gtCfg||{ads:true,shorts:true,sb:true,dis:true,notop:true,speed:1};

/* Mantém a página "visível": o vídeo não pausa em segundo plano */
try{
  Object.defineProperty(document,'hidden',{get:function(){return false}});
  Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});
  document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);
  window.addEventListener('blur',function(e){e.stopImmediatePropagation()},true);
  Object.defineProperty(document,'webkitHidden',{get:function(){return false}});
  Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'}});
  document.hasFocus=function(){return true};
}catch(e){}

/* Anti-pausa: dentro do picture-in-picture (window.__gtGuard) o vídeo só pausa se o usuário pedir
   (botão da janelinha/notificação marca window.__gtUser). Qualquer outra pausa é desfeita. */
window.__gtGuard=false; window.__gtUser=false;
(function(){
  var op=HTMLMediaElement.prototype.pause;
  HTMLMediaElement.prototype.pause=function(){
    if(window.__gtGuard&&!window.__gtUser) return;
    return op.apply(this,arguments);
  };
  var redo=0,t0=0;
  document.addEventListener('pause',function(e){
    var v=e.target;
    if(!window.__gtGuard||window.__gtUser||!v||v.ended||v.tagName!=='VIDEO') return;
    var now=Date.now(); if(now-t0>3000){ redo=0; t0=now; }
    if(redo++<4) setTimeout(function(){ if(window.__gtGuard&&!window.__gtUser&&v.paused&&!v.ended) v.play(); },60);
  },true);
})();

/* ---------- Anúncios: remove dados de anúncio das respostas do player ---------- */
if(cfg.ads){
  var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
  var strip=function(o){
    try{
      if(o&&typeof o==='object'){
        for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o) delete o[KEYS[i]]; }
        if(o.playerResponse) strip(o.playerResponse);
        if(o.response&&o.response.playerResponse) strip(o.response.playerResponse);
      }
    }catch(e){}
    return o;
  };
  var jp=JSON.parse;
  JSON.parse=function(){ return strip(jp.apply(this,arguments)); };
  if(window.Response&&Response.prototype.json){
    var oj=Response.prototype.json;
    Response.prototype.json=function(){ return oj.call(this).then(strip); };
  }
  try{
    var ipr;
    Object.defineProperty(window,'ytInitialPlayerResponse',{
      configurable:true,
      get:function(){return ipr;},
      set:function(v){ipr=strip(v);}
    });
  }catch(e){}
}

/* ---------- CSS ---------- */
function addCss(id,txt){
  if(document.getElementById(id))return;
  var s=document.createElement('style');s.id=id;s.textContent=txt;
  (document.head||document.documentElement).appendChild(s);
}
function css(){
  if(cfg.ads){
    addCss('gt-ads',[
     'ytm-promoted-sparkles-web-renderer','ytm-promoted-video-renderer','ytm-companion-ad-renderer',
     'ytm-display-ad-renderer','ytd-ad-slot-renderer','ad-slot-renderer','ytm-ad-slot-renderer',
     'ytm-statement-banner-renderer','ytm-mealbar-promo-renderer','#player-ads','.ad-container',
     '.ytp-ad-overlay-container','.ytp-ad-image-overlay','ytm-brand-video-singleton-renderer',
     'ytm-item-section-renderer:has(ad-slot-renderer)','ytm-rich-item-renderer:has(ad-slot-renderer)'
    ].join(',')+'{display:none!important}');
  }
  if(cfg.shorts){
    addCss('gt-shorts',[
     'ytm-reel-shelf-renderer','ytm-shorts-lockup-view-model','ytm-shorts-lockup-view-model-v2',
     'ytm-video-with-context-renderer:has(a[href^="/shorts/"])','ytm-compact-video-renderer:has(a[href^="/shorts/"])',
     'ytm-rich-item-renderer:has(a[href^="/shorts/"])','ytm-item-section-renderer:has(ytm-reel-shelf-renderer)',
     'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)'
    ].join(',')+'{display:none!important}');
  }
}
css();
document.addEventListener('DOMContentLoaded',css);

/* ---------- Esconde a barra do topo (logo YouTube + lupa) na tela de vídeo ---------- */
addCss('gt-notop',[
  'html.gt-notop ytm-mobile-topbar-renderer','html.gt-notop header.mobile-topbar-header'
].join(',')+'{display:none!important}'
 +'html.gt-notop .gt-nt-t{top:0!important}'
 +'html.gt-notop .gt-nt-p{padding-top:0!important}'
 +'html.gt-notop .gt-nt-m{margin-top:0!important}');
var topH=48;
/* O site reserva o espaço da barra (top/padding/margin = altura dela). Acha quem usa esse valor e zera. */
function fixTop(){
  try{
    if(!document.documentElement.classList.contains('gt-notop')||!document.body) return;
    var all=document.body.getElementsByTagName('*'),h=topH;
    for(var i=0;i<all.length;i++){
      var el=all[i],cs=getComputedStyle(el);
      if(cs.display==='none') continue;
      var p=cs.position;
      if((p==='fixed'||p==='sticky')&&Math.abs(parseFloat(cs.top)-h)<1.5) el.classList.add('gt-nt-t');
      if(Math.abs(parseFloat(cs.paddingTop)-h)<1.5) el.classList.add('gt-nt-p');
      if(Math.abs(parseFloat(cs.marginTop)-h)<1.5) el.classList.add('gt-nt-m');
    }
  }catch(e){}
}
function updateTop(){
  if(!cfg.notop||window.__gtMini) return;
  var rt=document.documentElement,w=location.pathname==='/watch',on=rt.classList.contains('gt-notop');
  if(w&&!on){
    var tb=document.querySelector('ytm-mobile-topbar-renderer');
    var hh=tb?Math.round(tb.getBoundingClientRect().height):0;
    if(hh>0) topH=hh;
    rt.classList.add('gt-notop');
    setTimeout(fixTop,50); setTimeout(fixTop,700); setTimeout(fixTop,2000);
  }else if(!w&&on){
    rt.classList.remove('gt-notop');
  }
}

/* ---------- Picture-in-picture: o vídeo ocupa a janelinha toda ---------- */
window.__gtPip=function(on){
  var e=document.getElementById('gt-pip'); if(e) e.remove();
  if(on){
    var s=document.createElement('style');s.id='gt-pip';
    s.textContent='html,body{overflow:hidden!important;background:#000!important}'
      +'video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;'
      +'z-index:2147483647!important;object-fit:contain!important;background:#000!important}';
    document.documentElement.appendChild(s);
  }
};

/* ---------- Tela cheia própria (ao girar o celular) ---------- */
window.__gtFS=function(on,pip){
  var old=document.getElementById('gt-fs'); if(old) old.remove();
  var prev=document.querySelectorAll('.gt-fsanc');
  for(var i=0;i<prev.length;i++) prev[i].classList.remove('gt-fsanc');
  var pl=document.querySelector('.html5-video-player')||document.querySelector('#movie_player');
  if(on&&pl){
    var e=pl.parentElement;
    while(e&&e!==document.documentElement){ e.classList.add('gt-fsanc'); e=e.parentElement; }
    var s=document.createElement('style'); s.id='gt-fs';
    s.textContent='html,body{overflow:hidden!important;background:#000!important}'
      +'.gt-fsanc{transform:none!important;contain:none!important;filter:none!important;overflow:visible!important;z-index:2147483646!important}'
      +'.html5-video-player,#movie_player{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;'
      +'z-index:2147483647!important;background:#000!important;margin:0!important}'
      +'.html5-video-player .html5-video-container,#movie_player .html5-video-container{position:absolute!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important}'
      +'.html5-video-player video,#movie_player video{position:absolute!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;transform:none!important;object-fit:contain!important;background:#000!important}';
    if(pip) s.textContent+='.player-controls-top,.player-controls-middle,.player-controls-bottom,.player-controls-background,'
      +'.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-pause-overlay{display:none!important}';
    document.documentElement.appendChild(s);
  }
  setTimeout(function(){ window.dispatchEvent(new Event('resize')); },60);
  setTimeout(function(){ window.dispatchEvent(new Event('resize')); },400);
};
try{
  var mq=window.matchMedia('(orientation: landscape)');
  var oc=function(e){ if(window.GT) GT.orient(e.matches); };
  if(mq.addEventListener) mq.addEventListener('change',oc); else mq.addListener(oc);
}catch(e){}

/* ---------- Mini player (WebView secundária do app): só o vídeo, sem controles ---------- */
if(window.__gtMini){
  window.__gtGuard=true;
  addCss('gt-mini','ytm-mobile-topbar-renderer,header.mobile-topbar-header{display:none!important}'
    +'html,body{overflow:hidden!important;background:#000!important}');
  var lastFit=0;
  setInterval(function(){
    try{
      /* tela preta por trás: nada da página vaza na janelinha */
      if(!document.getElementById('gt-mcover')&&document.documentElement){
        var c=document.createElement('div'); c.id='gt-mcover';
        c.style.cssText='position:fixed;top:0;left:0;width:100vw;height:100vh;background:#000;z-index:2147483640;pointer-events:none';
        document.documentElement.appendChild(c);
      }
      var v=document.querySelector('video');
      var pl=document.querySelector('.html5-video-player')||document.querySelector('#movie_player');
      if(pl&&v){
        /* o vídeo precisa ocupar a janelinha toda; se o site reorganizou a página, aplica de novo */
        var r=v.getBoundingClientRect(),now=Date.now();
        if((Math.abs(r.width-innerWidth)>3||Math.abs(r.height-innerHeight)>3)&&now-lastFit>700){
          lastFit=now; window.__gtFS(true,true);
        }
        /* o site inicia o vídeo mudo (autoplay): tira o mudo (fora de anúncio) */
        if(!adShowing()&&(v.muted||v.volume===0)){
          try{ if(pl.unMute) pl.unMute(); if(pl.setVolume) pl.setVolume(100); }catch(e){}
          v.muted=false; if(v.volume===0) v.volume=1;
        }
      }
      if(v&&!v.__gtP&&v.paused&&!v.ended){ v.__gtP=1; var pr=v.play(); if(pr&&pr.catch) pr.catch(function(){}); }
    }catch(e){}
  },300);
}

/* ---------- Dislikes (Return YouTube Dislike, via app) ---------- */
var curVid='',segs=[],dis=null;
function fmt(n){ try{return new Intl.NumberFormat('en',{notation:'compact'}).format(n);}catch(e){return ''+n;} }
function renderDis(){
  var el=document.getElementById('gt-dis');
  if(!cfg.dis||!curVid||dis===null){ if(el) el.remove(); return; }
  if(!el){
    el=document.createElement('div'); el.id='gt-dis';
    el.style.cssText='position:fixed;left:8px;bottom:calc(env(safe-area-inset-bottom,0px) + 12px);z-index:9999;'
      +'background:rgba(0,0,0,.65);color:#fff;font:600 12px Roboto,Arial,sans-serif;padding:5px 10px;'
      +'border-radius:14px;pointer-events:none';
    document.documentElement.appendChild(el);
  }
  el.textContent='\uD83D\uDC4E '+fmt(dis);
}
window.__gtRYD=function(id,likes,d){ if(id!==curVid)return; dis=d; renderDis(); };

/* ---------- SponsorBlock (via app) ---------- */
window.__gtSB=function(id,list){ if(id===curVid) segs=list||[]; };

function adShowing(){
  var p=document.querySelector('.html5-video-player')||document.querySelector('#movie_player');
  return !!(p&&p.classList.contains('ad-showing'));
}
function bind(v){
  if(v.__gtB)return; v.__gtB=1;
  v.addEventListener('loadedmetadata',function(){ if(!adShowing()) v.playbackRate=cfg.speed; });
  v.addEventListener('timeupdate',function(){
    if(!cfg.sb||!segs.length||adShowing())return;
    var t=v.currentTime;
    for(var i=0;i<segs.length;i++){
      if(t>=segs[i][0]&&t<segs[i][1]-0.4){ v.currentTime=segs[i][1]; break; }
    }
  });
  if(v.readyState>0&&!adShowing()) v.playbackRate=cfg.speed;
}
function vid(){
  if(location.pathname!=='/watch')return '';
  return new URLSearchParams(location.search).get('v')||'';
}

setInterval(function(){
  try{
    /* Shorts abrem no player normal; esconde a aba Shorts */
    if(cfg.shorts){
      var m=location.pathname.match(/^\/shorts\/([\w-]{11})/);
      if(m){ location.replace('/watch?v='+m[1]); return; }
      var it=document.querySelectorAll('ytm-pivot-bar-item-renderer');
      for(var k=0;k<it.length;k++){ if(/^\s*shorts\s*$/i.test(it[k].textContent)) it[k].style.display='none'; }
    }
    updateTop();
    /* mudou de vídeo */
    var id=vid();
    if(id!==curVid){
      curVid=id; segs=[]; dis=null; renderDis();
      if(id&&window.GT){ if(cfg.sb) GT.sponsor(id); if(cfg.dis) GT.dislikes(id); }
    }
    var v=document.querySelector('video');
    if(v) bind(v);
    /* anúncio de vídeo que passou: pula/acelera */
    if(cfg.ads){
      if(adShowing()&&v){
        var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
        if(b) b.click();
        var c=document.querySelector('.ytp-ad-overlay-close-button');
        if(c) c.click();
        v.muted=true; v.playbackRate=16;
        if(isFinite(v.duration)&&v.duration>0) v.currentTime=v.duration;
        window.__gtAdSeen=true;
      }else if(window.__gtAdSeen&&v){
        window.__gtAdSeen=false; v.muted=false; v.playbackRate=cfg.speed;
      }
    }
  }catch(e){}
},400);

/* ---------- Estado para a notificação de mídia ---------- */
var lastMeta='';
function meta(){
  try{
    var v=document.querySelector('video');
    var t=(document.title||'DevTube').replace(/ - YouTube$/,'');
    var pl=!!(v&&!v.paused&&!v.ended);
    var k=t+'|'+pl;
    if(k!==lastMeta){ lastMeta=k; if(window.GT) GT.meta(t,pl); }
  }catch(e){}
}
document.addEventListener('play',meta,true);
document.addEventListener('pause',meta,true);
setInterval(meta,1000);
})();

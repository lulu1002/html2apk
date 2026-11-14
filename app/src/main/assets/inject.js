(function(){
if(window.__gt)return;window.__gt=1;
var cfg=window.__gtCfg||{ads:true,shorts:true,sb:true,dis:true,notop:true,qc:true,dtap:true,cap:{size:100},accent:'',capPref:'',audPref:'',keep:true,bar:true,speed:1};

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
/* quadro do player sempre preto (em vez do cinza que aparece na troca de vídeo) */
addCss('gt-black','.html5-video-player,.html5-video-container,#player-container-id,.player-container,#player,'
  +'ytm-custom-control,.player-placeholder,ytm-player-placeholder,.ytp-cued-thumbnail-overlay,'
  +'.ytp-cued-thumbnail-overlay-image{background-color:#000!important}');

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
  if(window.__gtMini) return;
  if(!cfg.notop){ document.documentElement.classList.remove('gt-notop'); return; }
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
  window.__gtFsOn=!!on&&!pip;
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
      +'z-index:2147483646!important;background:#000!important;margin:0!important}'
      +'.html5-video-player .html5-video-container,#movie_player .html5-video-container{position:absolute!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important}'
      +'.html5-video-player video,#movie_player video{position:absolute!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;transform:none!important;object-fit:contain!important;background:#000!important}';
    if(!pip) s.textContent+='#player-control-overlay{position:fixed!important;top:0!important;left:0!important;'
      +'width:100vw!important;height:100vh!important;margin:0!important;z-index:2147483647!important}'
      +'#player-control-overlay .player-controls-content{z-index:2147483647!important}';
    if(!pip) s.textContent+='ytm-bottom-sheet-renderer,ytm-menu-popup-renderer,ytm-popup-container,.bottom-sheet-container,'
      +'.modal-container,tp-yt-paper-dialog{z-index:2147483647!important}';
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

/* ---------- Estilo das legendas (tamanho, cor, fundo, borda) ---------- */
window.__gtCapStyle=function(c){
  try{
    c=c||cfg.cap||{};
    var css='';
    if(c.size&&c.size!==100) css+='font-size:'+(c.size/100)+'em!important;';
    if(c.color) css+='color:'+c.color+'!important;';
    var bg={dark:'rgba(0,0,0,.75)',solid:'#000',none:'transparent'}[c.bg]; if(bg) css+='background-color:'+bg+'!important;';
    var ed={shadow:'1px 1px 3px #000,0 0 4px #000',
            outline:'-1px -1px 0 #000,1px -1px 0 #000,-1px 1px 0 #000,1px 1px 0 #000',none:'none'}[c.edge];
    if(ed) css+='text-shadow:'+ed+'!important;';
    var st=document.getElementById('gt-cap');
    if(!st){ st=document.createElement('style'); st.id='gt-cap'; (document.head||document.documentElement).appendChild(st); }
    st.textContent=css?'.ytp-caption-segment,.captions-text{'+css+'}':'';
  }catch(e){}
};
window.__gtCapStyle(cfg.cap);

/* ---------- Cor de destaque (barra de progresso do player) ---------- */
window.__gtAccent=function(c){
  try{
    c=(c===undefined)?cfg.accent:c;
    var st=document.getElementById('gt-accent');
    if(!st){ st=document.createElement('style'); st.id='gt-accent'; (document.head||document.documentElement).appendChild(st); }
    st.textContent=c?'.ytp-play-progress,.ytp-swatch-background-color,[class*="progress-bar-played"],'
      +'.ytp-scrubber-button,.ytp-scrubber-circle{background-color:'+c+'!important}':'';
  }catch(e){}
};
window.__gtAccent(cfg.accent);

/* ---------- Idioma preferido: legendas e áudio (aplicado ao começar cada vídeo) ---------- */
function lbase(c){ return String(c||'').toLowerCase().split(/[-_]/)[0]; }
window.__gtSetCapTr=function(lang){
  try{
    var p=mp(),tl=p.getOption('captions','tracklist')||[],b=null;
    tl.forEach(function(t){ if(!b&&t.is_translateable!==false) b=t; });
    if(b){ p.loadModule('captions'); p.setOption('captions','track',{languageCode:b.languageCode,translationLanguage:{languageCode:lang}}); }
  }catch(e){}
};
window.__gtAutoCap=function(){
  try{
    var pref=cfg.capPref; if(!pref) return true;
    var p=mp(); if(!p||!p.getOption) return false;
    if(p.loadModule) p.loadModule('captions');
    var tl=p.getOption('captions','tracklist')||[];
    if(!tl.length) return false;
    if(pref==='off'){ p.setOption('captions','track',{}); return true; }
    var m=null; tl.forEach(function(t){ if(!m&&lbase(t.languageCode)===lbase(pref)) m=t; });
    if(m){ p.setOption('captions','track',{languageCode:m.languageCode}); return true; }
    window.__gtSetCapTr(lbase(pref));   /* sem faixa nesse idioma: tradução automática */
    return true;
  }catch(e){ return false; }
};
window.__gtAutoAud=function(){
  try{
    var pref=cfg.audPref; if(!pref) return true;
    var p=mp(); if(!p||!p.getAvailableAudioTracks) return false;
    var tr=p.getAvailableAudioTracks()||[]; if(tr.length<2) return tr.length>0;
    var re=new RegExp('(^|[^a-z])'+lbase(pref)+'([^a-z]|$)','i'),want=null;
    tr.forEach(function(t){ var x=''; try{ x=(t.id||'')+' '+JSON.stringify(t); }catch(e){ x=String(t.id||''); } if(!want&&re.test(x)) want=t; });
    if(want&&p.setAudioTrack) p.setAudioTrack(want);
    return true;
  }catch(e){ return false; }
};
function autoLang(d){
  if(window.__gtMini||(!cfg.capPref&&!cfg.audPref)) return;
  var nc=0,na=0,ok1=!cfg.capPref,ok2=!cfg.audPref;
  setTimeout(function t(){
    if(!ok1) ok1=window.__gtAutoCap()||++nc>6;
    if(!ok2) ok2=window.__gtAutoAud()||++na>6;
    if(!(ok1&&ok2)) setTimeout(t,1500);
  },d||1800);
}
window.__gtAutoLangNow=autoLang;

/* ---------- Barra de navegação de baixo (Início / Shorts / Inscrições) ---------- */
window.__gtBar=function(on){
  try{
    var st=document.getElementById('gt-nobar');
    if(on){ if(st) st.remove(); return; }
    if(!st){
      st=document.createElement('style'); st.id='gt-nobar';
      st.textContent='ytm-pivot-bar-renderer{display:none!important}';
      (document.head||document.documentElement).appendChild(st);
    }
  }catch(e){}
};
window.__gtBar(cfg.bar);

/* ---------- Abrir vídeo numa camada nova: a página de origem fica guardada, intacta ---------- */
(function(){
  function vurl(a){
    try{
      var u=new URL(a.href,location.href);
      if(!/(^|\.)youtube\.com$/.test(u.hostname)) return null;
      if(u.pathname==='/watch'&&u.searchParams.get('v')) return u.href;
      var m=u.pathname.match(/^\/shorts\/([A-Za-z0-9_-]{11})/);
      if(m) return 'https://m.youtube.com/watch?v='+m[1];
    }catch(e){}
    return null;
  }
  document.addEventListener('click',function(e){
    if(!cfg.keep||window.__gtMini||!window.GT||!GT.openWatch) return;
    if(location.pathname==='/watch') return;            /* dentro do vídeo a navegação segue normal */
    if(e.defaultPrevented||e.button>0||e.ctrlKey||e.metaKey||e.shiftKey) return;
    var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;
    if(!a) return;
    var u=vurl(a); if(!u) return;
    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
    GT.openWatch(u);
  },true);
})();

/* estilos que dependem da configuração: reaplica quando o documento existe de fato */
document.addEventListener('DOMContentLoaded',function(){
  try{ window.__gtBar(cfg.bar); window.__gtAccent(); window.__gtCapStyle(); }catch(e){}
});

/* ---------- Loop do vídeo atual ---------- */
window.__gtToggleLoop=function(){
  var v=document.querySelector('video'); if(!v) return false;
  v.loop=!v.loop; return v.loop;
};

/* ---------- Toque duplo na lateral do vídeo: volta / avança 10s ---------- */
(function(){
  var lt=0,lx=0,ly=0;
  function fb(txt,left,r){
    var e=document.createElement('div'); e.textContent=txt;
    e.style.cssText='position:fixed;top:'+(r.top+r.height/2-22)+'px;'+(left?'left:'+(r.left+r.width*0.12):'left:'+(r.left+r.width*0.72))+'px;'
      +'background:rgba(0,0,0,.65);color:#fff;font:700 18px Roboto,Arial,sans-serif;padding:10px 16px;border-radius:24px;'
      +'z-index:2147483647;pointer-events:none;transition:opacity .5s';
    document.documentElement.appendChild(e);
    setTimeout(function(){ e.style.opacity='0'; },500); setTimeout(function(){ e.remove(); },1100);
  }
  document.addEventListener('touchend',function(e){
    if(!cfg.dtap||window.__gtMini||location.pathname!=='/watch') return;
    var t=e.changedTouches&&e.changedTouches[0]; if(!t) return;
    if(e.target&&e.target.closest&&e.target.closest('button,a,input,[role="slider"],[class*="progress"],[class*="seek"],[class*="scrubber"]')){ lt=0; return; }
    var v=document.querySelector('video'); if(!v) return;
    var r=v.getBoundingClientRect();
    if(t.clientX<r.left||t.clientX>r.right||t.clientY<r.top||t.clientY>r.bottom){ lt=0; return; }
    var now=Date.now(),rel=(t.clientX-r.left)/r.width;
    if(now-lt<320&&Math.abs(t.clientX-lx)<70&&Math.abs(t.clientY-ly)<70&&(rel<0.35||rel>0.65)){
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      var back=rel<0.5;
      v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+(back?-10:10)));
      fb(back?'\u221210s':'+10s',back,r);
      lt=now; lx=t.clientX; ly=t.clientY; return;
    }
    lt=now; lx=t.clientX; ly=t.clientY;
  },{capture:true,passive:false});
})();

/* ---------- Engrenagem do player na tela cheia própria: abre o menu nativo do app ---------- */
(function(){
  var last=0;
  function h(e){
    if(!window.__gtFsOn||!window.GT||!GT.settings) return;
    var t=e.target&&e.target.closest?e.target.closest(
      'button[aria-label*="onfigura" i],button[aria-label*="ettings" i],[class*="settings-icon"],[class*="settings-button"]'):null;
    if(!t) return;
    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
    var now=Date.now();
    if(now-last>600){ last=now; GT.settings(); }
  }
  ['touchend','pointerup','mouseup','click'].forEach(function(n){ document.addEventListener(n,h,true); });
})();

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

/* ---------- Atalhos rápidos: funções chamadas pelos botões/menus NATIVOS do app ---------- */
var QL={highres:'4320p',hd2880:'2880p',hd2160:'2160p',hd1440:'1440p',hd1080:'1080p',hd720:'720p',
        large:'480p',medium:'360p',small:'240p',tiny:'144p',auto:'Auto'};
function mp(){ return document.querySelector('#movie_player')||document.querySelector('.html5-video-player'); }
window.__gtQcState=function(){
  try{
    var p=mp(),v=document.querySelector('video');
    var q=p&&p.getPlaybackQuality?p.getPlaybackQuality():'';
    return JSON.stringify({q:QL[q]||'Qualidade',s:v?Math.round(v.playbackRate*100)/100:1,l:!!(v&&v.loop)});
  }catch(e){ return '{}'; }
};
window.__gtQcQuality=function(){
  try{
    var p=mp(),lv=p.getAvailableQualityLevels()||[];
    return JSON.stringify({cur:p.getPlaybackQuality(),
      levels:lv.filter(function(x){return x!=='auto';}).map(function(x){return [x,QL[x]||x];})});
  }catch(e){ return '{}'; }
};
window.__gtSetQ=function(q){
  try{
    var p=mp();
    if(q==='auto'){ p.setPlaybackQualityRange('tiny','highres'); p.setPlaybackQuality('auto'); }
    else{ p.setPlaybackQualityRange(q,q); p.setPlaybackQuality(q); }
  }catch(e){}
};
window.__gtSetSpeed=function(x){ var v=document.querySelector('video'); if(v) v.playbackRate=x; };
window.__gtCapPrep=function(){ try{ var p=mp(); if(p.loadModule) p.loadModule('captions'); }catch(e){} };
window.__gtCapList=function(){
  try{
    var p=mp(),tl=p.getOption('captions','tracklist')||[],cur=p.getOption('captions','track')||{};
    var trc=''; tl.forEach(function(t){ if(!trc&&t.is_translateable!==false) trc=t.languageCode; });
    return JSON.stringify({cur:cur.languageCode||'',tr:trc,
      tracks:tl.map(function(t){return [t.languageCode,t.displayName||t.languageName||t.languageCode];})});
  }catch(e){ return '{}'; }
};
window.__gtSetCap=function(code){
  try{
    var p=mp();
    if(!code){ p.setOption('captions','track',{}); p.unloadModule('captions'); }
    else{ p.loadModule('captions'); p.setOption('captions','track',{languageCode:code}); }
  }catch(e){}
};
window.__gtCapNative=function(){
  var b=document.querySelector('button[aria-label*="egenda" i],button[aria-label*="aption" i],button[aria-label*="ubtitle" i]');
  if(b){ b.click(); return true; }
  return false;
};

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
      try{ var lv=document.querySelector('video'); if(lv) lv.loop=false; }catch(e){}
      if(id) autoLang(1800);
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

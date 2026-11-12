(function(){
if(window.__gt)return;window.__gt=1;

/* 1) Mantém a página "visível": o vídeo não pausa em segundo plano */
try{
  Object.defineProperty(document,'hidden',{get:function(){return false}});
  Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});
  document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);
  window.addEventListener('blur',function(e){e.stopImmediatePropagation()},true);
}catch(e){}

/* 2) Remove os dados de anúncio das respostas do player antes de o YouTube usá-los */
var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
function strip(o){
  try{
    if(o&&typeof o==='object'){
      for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o) delete o[KEYS[i]]; }
      if(o.playerResponse) strip(o.playerResponse);
      if(o.response&&o.response.playerResponse) strip(o.response.playerResponse);
    }
  }catch(e){}
  return o;
}
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

/* 3) Esconde blocos de anúncio na interface */
var css=[
 'ytm-promoted-sparkles-web-renderer','ytm-promoted-video-renderer','ytm-companion-ad-renderer',
 'ytm-display-ad-renderer','ytd-ad-slot-renderer','ad-slot-renderer','ytm-ad-slot-renderer',
 'ytm-statement-banner-renderer','ytm-mealbar-promo-renderer','#player-ads','.ad-container',
 '.ytp-ad-overlay-container','.ytp-ad-image-overlay','ytm-brand-video-singleton-renderer',
 'ytm-item-section-renderer:has(ad-slot-renderer)','ytm-rich-item-renderer:has(ad-slot-renderer)'
].join(',')+'{display:none!important}';
function addCss(){
  if(document.getElementById('gt-css'))return;
  var s=document.createElement('style');s.id='gt-css';s.textContent=css;
  (document.head||document.documentElement).appendChild(s);
}
addCss();
document.addEventListener('DOMContentLoaded',addCss);

/* 4) Reserva: se um anúncio de vídeo aparecer mesmo assim, pula/acelera */
setInterval(function(){
  try{
    var p=document.querySelector('.html5-video-player')||document.querySelector('#movie_player');
    var v=document.querySelector('video');
    var ad=p&&p.classList.contains('ad-showing');
    if(ad&&v){
      var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
      if(b) b.click();
      var c=document.querySelector('.ytp-ad-overlay-close-button');
      if(c) c.click();
      v.muted=true; v.playbackRate=16;
      if(isFinite(v.duration)&&v.duration>0) v.currentTime=v.duration;
      window.__gtAd=true;
    }else if(window.__gtAd&&v){
      window.__gtAd=false; v.muted=false; v.playbackRate=1;
    }
  }catch(e){}
},250);
})();

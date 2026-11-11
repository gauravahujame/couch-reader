/* All publication content has been sanitized before this shell can open it. */
'use strict';
let book, rendition, queue=Promise.resolve(), opening=true, currentPreferences, pendingAnchor=null;
let indexReady=false, toc=[], lastLocation, bookHash;
async function loadLocationCache(target){
 const response=await fetch('https://reader.invalid/reader/locations.json');
 if(!response.ok)throw new Error('No index');
 const cache=await response.json();
 if(cache.version!==1||cache.hash!==bookHash||!Array.isArray(cache.locations)||!cache.locations.length||!cache.locations.every(c=>typeof c==='string'&&c.startsWith('epubcfi(')))throw new Error('Invalid index');
 target.locations.load(cache.locations);
}
function navigationHref(href){
 try{
  const url=new URL(href,book.resolve(book.packaging.navPath||book.packaging.ncxPath||''));
  const section=book.spine.spineItems.find(item=>new URL(item.url).pathname===url.pathname);
  return section?section.href+url.hash:href;
 }catch(ignored){return href;}
}
function chapterTitle(locator){
 const section=book.spine.get(locator.href?.split('#')[0]);
 if(!section)return 'Chapter';
 let title='Section '+(section.index+1);
 for(const item of toc){
  const [path,fragment]=item.href.split('#'), candidate=book.spine.get(path);
  if(!candidate||candidate.index>section.index)continue;
  if(candidate.index===section.index&&fragment){
   const contents=rendition?.getContents().find(content=>content.sectionIndex===section.index);
   const element=contents?.document.getElementById(decodeURIComponent(fragment));
   if(!element)continue;
   if(new ePub.CFI().compare(section.cfiFromElement(element),locator.cfi)>0)continue;
  }
  title=item.title.trim();
 }
 return title;
}
function readingInfo(locator= pendingAnchor||lastLocation?.start){
 if(!locator?.cfi)return;
 let fraction=indexReady?book.locations.percentageFromCfi(locator.cfi):null;
 if(indexReady&&lastLocation?.atStart&&!pendingAnchor)fraction=0;
 if(indexReady&&lastLocation?.atEnd&&!pendingAnchor)fraction=1;
 tell('readingInfo',{chapter:chapterTitle(locator),fraction:typeof fraction==='number'?Math.max(0,Math.min(1,fraction)):null});
}
window.refreshProgress=async function(){
 try{await loadLocationCache(book);indexReady=true;}catch(ignored){}
 readingInfo();
};
window.indexProgress=function(locator){if(locator)readingInfo(locator);else tell('readingInfo',{chapter:'',fraction:0});};
const tell=(type,value)=>ReaderBridge.event(type,JSON.stringify(value||{}));
function serial(action){queue=queue.then(action).catch(()=>tell('error',{message:'This section could not be displayed. Open Chapters to try another section.'}));return queue;}
function applyPreferences(p){
 currentPreferences=p;const bg=p.dark?'#202822':'#f5f0e5',fg=p.dark?'#efece0':'#28372e';document.body.style.background=bg;document.documentElement.style.background=bg;
 rendition.themes.default({
 '@font-face':{'font-family':'Literata','src':'url("https://reader.invalid/reader/literata.ttf")'},
 'html':{'background':bg+' !important','color':fg+' !important'},
 'body':{'font-family':'Literata, Georgia, serif !important','font-size':p.fontSize+'px !important','line-height':'1.58 !important','color':fg+' !important','background':bg+' !important','margin':'0 !important','padding':'0 !important','overflow-wrap':'break-word'},
 'p':{'margin':'0 0 .8em !important','text-align':'left !important'},
 'h1,h2,h3':{'line-height':'1.2 !important','color':fg+' !important'},
 'img':{'max-width':'100% !important','max-height':'70vh !important','object-fit':'contain'},
 'a':{'color':fg+' !important'},'pre':{'white-space':'pre-wrap !important'},'table':{'max-width':'100% !important'}
 });
 rendition.themes.fontSize(p.fontSize+'px');
 // The vendored manager calculates both column width and page-turn delta from this gap.
 const gap=p.spread && document.documentElement.clientWidth>=900 ? 40 : 0;
 rendition.settings.gap=gap;
 if(rendition.manager)rendition.manager.settings.gap=gap;
 rendition.spread(p.spread?'auto':'none',900);
}
window.openBook=function(config){
 serial(async()=>{
  pendingAnchor=null;bookHash=config.hash;
  book=ePub(config.url,{replacements:'none'});
  if(config.indexOnly){
   await book.ready;
   const navigation=await book.loaded.navigation;
   const flatten=items=>{for(const item of items){toc.push({title:item.label||'Chapter',href:navigationHref(item.href)});if(item.subitems)flatten(item.subitems);}};
   flatten(navigation.toc||[]);
   try{await loadLocationCache(book);indexReady=true;tell('indexed',{cached:true});}
   catch(ignored){
    // A dedicated book instance may unload sections while indexing; never use the rendered book.
    // EPUB.js yields for 100ms after each section so page input can run between sections.
    try{await book.locations.generate(1600);indexReady=true;ReaderBridge.cacheLocations(book.locations.save());}
    catch(error){tell('indexError');}
   }
   return;
  }
  rendition=book.renderTo('pages',{width:'100%',height:'100%',flow:'paginated',spread:'none',gap:0,allowScriptedContent:false,allowPopups:false});
  applyPreferences(config.preferences);
  rendition.on('relocated',location=>{lastLocation=location;if(location.start&&location.start.cfi){tell('location',{cfi:pendingAnchor?.cfi||location.start.cfi,href:pendingAnchor?.href||location.start.href,atStart:location.atStart,atEnd:location.atEnd});readingInfo();}});
  rendition.on('displayError',()=>tell('error',{message:'Unable to render this section. Try another chapter.'}));
  await book.ready;
  if(document.fonts)await document.fonts.load(config.preferences.fontSize+'px Literata');
  const chapters=[];
  function flatten(items,depth){for(const item of items){chapters.push({title:('  '.repeat(depth))+(item.label||'Chapter'),href:navigationHref(item.href)});if(item.subitems)flatten(item.subitems,depth+1);}}
  const navigation=await book.loaded.navigation;flatten(navigation.toc||[],0);
  if(!chapters.length)book.spine.each(section=>{if(section.linear)chapters.push({title:'Section '+(chapters.length+1),href:section.href});});
  toc=chapters;try{await loadLocationCache(book);indexReady=true;}catch(ignored){}
  tell('chapters',chapters);
  try{await rendition.display(config.locator?.cfi||undefined);}
  catch(error){try{await rendition.display(config.locator?.href||undefined);}catch(ignored){await rendition.display();}tell('notice',{message:'Saved passage unavailable. Opened the nearest available section.'});}
  opening=false;tell('ready',{indexed:indexReady});
 });
};
window.turn=function(forward){return serial(async()=>{if(opening)return;pendingAnchor=null;const loc=rendition.currentLocation();if((forward&&loc.atEnd)||(!forward&&loc.atStart)){tell('boundary',{end:forward});return;}await (forward?rendition.next():rendition.prev());});};
window.preferences=function(p){return serial(async()=>{// Preserve the original passage across a series of reflows, including persisted relocations.
 if(!pendingAnchor){const start=rendition.currentLocation()?.start;if(start?.cfi)pendingAnchor={cfi:start.cfi,href:start.href};}
 applyPreferences(p);if(pendingAnchor)await rendition.display(pendingAnchor.cfi);});};
window.chapter=function(href){return serial(()=>{pendingAnchor=null;return rendition.display(href||undefined);});};

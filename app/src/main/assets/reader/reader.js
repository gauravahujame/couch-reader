/* All publication content has been sanitized before this shell can open it. */
'use strict';
let book, rendition, queue=Promise.resolve(), opening=true, currentPreferences;
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
}
window.openBook=function(config){
 serial(async()=>{
  book=ePub(config.url,{replacements:'none'});
  rendition=book.renderTo('pages',{width:'100%',height:'100%',flow:'paginated',spread:'none',gap:0,allowScriptedContent:false,allowPopups:false});
  applyPreferences(config.preferences);
  rendition.on('relocated',location=>{if(location.start&&location.start.cfi){tell('location',{cfi:location.start.cfi,href:location.start.href,atStart:location.atStart,atEnd:location.atEnd});}});
  rendition.on('displayError',()=>tell('error',{message:'Unable to render this section. Try another chapter.'}));
  await book.ready;
  if(document.fonts)await document.fonts.load(config.preferences.fontSize+'px Literata');
  const chapters=[];
  function flatten(items,depth){for(const item of items){chapters.push({title:('  '.repeat(depth))+(item.label||'Chapter'),href:item.href});if(item.subitems)flatten(item.subitems,depth+1);}}
  const navigation=await book.loaded.navigation;flatten(navigation.toc||[],0);
  if(!chapters.length)book.spine.each(section=>{if(section.linear)chapters.push({title:'Section '+(chapters.length+1),href:section.href});});
  tell('chapters',chapters);
  try{await rendition.display(config.locator?.cfi||undefined);}
  catch(error){try{await rendition.display(config.locator?.href||undefined);}catch(ignored){await rendition.display();}tell('notice',{message:'Saved passage unavailable. Opened the nearest available section.'});}
  opening=false;tell('ready');
 });
};
window.turn=function(forward){return serial(async()=>{if(opening)return;const loc=rendition.currentLocation();if((forward&&loc.atEnd)||(!forward&&loc.atStart)){tell('boundary',{end:forward});return;}await (forward?rendition.next():rendition.prev());});};
window.preferences=function(p){return serial(async()=>{const anchor=rendition.currentLocation()?.start?.cfi;applyPreferences(p);if(anchor)await rendition.display(anchor);});};
window.chapter=function(href){return serial(()=>rendition.display(href||undefined));};

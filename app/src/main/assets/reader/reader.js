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
 currentPreferences=p;const bg=p.dark?'#000000':'#f5f0e5',fg=p.dark?'#efece0':'#28372e';document.body.style.background=bg;document.documentElement.style.background=bg;
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
  rendition.on('relocated',location=>{lastLocation=location;pageList=[];if(location.start&&location.start.cfi){tell('location',{cfi:pendingAnchor?.cfi||location.start.cfi,href:pendingAnchor?.href||location.start.href,atStart:location.atStart,atEnd:location.atEnd});readingInfo();tell('page');}});
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
/* Read-aloud. Each section document is split into sentences once; a sentence is a DOM Range, so the
   book markup is never modified and CFIs stay valid. The highlight uses the CSS Custom Highlight API. */
let pageList=[];
const BLOCKS='p,h1,h2,h3,h4,h5,h6,li,blockquote,dd,dt,figcaption,caption,td,th,pre,div,section,article,aside,header,footer,body';
const sentenceCache=new WeakMap();
const cleanText=text=>text.replace(/\s+/g,' ').trim();
function regexSentences(text){
 const out=[],pattern=/[^.!?…]+(?:[.!?…]+["'”’)\]]*|$)/g;let match;
 while((match=pattern.exec(text)))out.push([match.index,match.index+match[0].length]);
 return out;
}
function locateOffset(nodes,offset,isEnd){
 for(let i=nodes.length-1;i>=0;i--){const item=nodes[i];if(isEnd?item.start<offset:item.start<=offset)return{node:item.node,offset:offset-item.start};}
 return{node:nodes[0].node,offset:0};
}
function documentSentences(doc){
 const cached=sentenceCache.get(doc);if(cached)return cached;
 const blocks=[];let current=null;
 const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);
 for(let node=walker.nextNode();node;node=walker.nextNode()){
  const parent=node.parentElement;
  // Footnote reference markers are skipped so the voice does not read "12" mid-sentence.
  if(!parent||parent.closest('script,style,rt,sup a,a sup'))continue;
  const block=parent.closest(BLOCKS);
  if(!current||current.block!==block){current={block,nodes:[],text:''};blocks.push(current);}
  current.nodes.push({node,start:current.text.length});current.text+=node.data;
 }
 const Segmenter=doc.defaultView?.Intl?.Segmenter||(typeof Intl!=='undefined'?Intl.Segmenter:undefined);
 let segmenter=null;
 try{if(Segmenter)segmenter=new Segmenter(doc.documentElement.lang||undefined,{granularity:'sentence'});}catch(ignored){}
 const sentences=[];
 for(const block of blocks){
  const pieces=segmenter?Array.from(segmenter.segment(block.text),piece=>[piece.index,piece.index+piece.segment.length]):regexSentences(block.text);
  for(const [start,end] of pieces){
   const raw=block.text.slice(start,end);
   if(!/[\p{L}\p{N}]/u.test(raw))continue;
   const lead=raw.length-raw.trimStart().length,trail=raw.length-raw.trimEnd().length;
   const from=locateOffset(block.nodes,start+lead,false),to=locateOffset(block.nodes,end-trail,true);
   const range=doc.createRange();
   try{range.setStart(from.node,from.offset);range.setEnd(to.node,to.offset);}catch(ignored){continue;}
   sentences.push({range,text:cleanText(raw)});
  }
 }
 sentenceCache.set(doc,sentences);return sentences;
}
function visibleContents(){
 const location=rendition?.currentLocation();
 if(!location?.start?.cfi||!location?.end?.cfi)return null;
 const all=rendition.getContents();
 const contents=all.find(item=>item.sectionIndex===location.start.index)||all[0];
 if(!contents)return null;
 return{contents,start:contents.range(location.start.cfi),end:contents.range(location.end.cfi)};
}
window.pageSentences=function(id){
 serial(async()=>{
  let items=[];
  try{
   const visible=opening?null:visibleContents();
   if(visible){
    const{start,end}=visible;
    // EPUB.js's visible start/end can sit a word off, so which screen a sentence is on is decided by
    // where its first and last characters are laid out. Columns run left to right inside the
    // section iframe; one screen (one page, or a spread) is layout.delta pixels wide.
    const delta=rendition.manager?.layout?.delta||document.documentElement.clientWidth;
    const screenOf=(node,offset,last)=>{
     const length=node.nodeType===3?node.length:node.childNodes.length;
     const from=last?Math.max(0,offset-1):offset,to=Math.min(from+1,length);
     if(to<=from)return null;
     const probe=node.ownerDocument.createRange();
     try{probe.setStart(node,from);probe.setEnd(node,to);}catch(ignored){return null;}
     const rect=probe.getClientRects()[0];
     return rect&&(rect.width||rect.height)?Math.floor((rect.left+rect.width/2)/delta):null;
    };
    const screen=screenOf(start.startContainer,start.startOffset,false);
    const place=s=>{
     const first=screen===null?null:screenOf(s.range.startContainer,s.range.startOffset,false);
     const last=screen===null?null:screenOf(s.range.endContainer,s.range.endOffset,true);
     if(first===null||last===null)return{on:start.comparePoint(s.range.endContainer,s.range.endOffset)>0&&end.comparePoint(s.range.startContainer,s.range.startOffset)<0,
      continued:start.comparePoint(s.range.startContainer,s.range.startOffset)<0};
     return{on:first<=screen&&last>=screen,continued:first<screen};
    };
    const placed=documentSentences(visible.contents.document).map(s=>({s,where:place(s)})).filter(item=>item.where.on);
    pageList=placed.map(item=>item.s);
    items=placed.map(({s,where})=>{
     let visibleLength=s.text.length;
     if(end.comparePoint(s.range.endContainer,s.range.endOffset)>0){
      const shown=s.range.cloneRange();shown.setEnd(end.startContainer,end.startOffset);
      visibleLength=Math.min(s.text.length,cleanText(shown.toString()).length);
     }
     return{text:s.text,continued:where.continued,visible:visibleLength};
    });
   }else pageList=[];
  }catch(ignored){pageList=[];items=[];}
  ReaderBridge.sentences(id,JSON.stringify(items));
 });
};
/* The highlight is drawn as one rounded box per printed line, laid over the text with a blend mode
   so the text stays crisp. Per-glyph highlights (CSS ::highlight) paint the font's full ascent and
   descent, which on tight lines such as headings overlapped the next line and doubled the tint. */
let highlightLayer=null;
function clearReadAloudHighlight(){highlightLayer?.remove();highlightLayer=null;}
function lineBoxes(range){
 const rects=Array.from(range.getClientRects()).filter(r=>r.width>.5&&r.height>.5).sort((a,b)=>a.top-b.top||a.left-b.left);
 const lines=[];
 for(const r of rects){
  // Pieces of one printed line share a centre line and sit side by side (columns are far apart).
  const line=lines.find(l=>Math.abs((l.top+l.bottom)/2-(r.top+r.bottom)/2)<Math.min(l.bottom-l.top,r.height)/2&&r.left<=l.right+24&&r.right>=l.left-24);
  if(line){line.left=Math.min(line.left,r.left);line.right=Math.max(line.right,r.right);line.top=Math.min(line.top,r.top);line.bottom=Math.max(line.bottom,r.bottom);}
  else lines.push({left:r.left,right:r.right,top:r.top,bottom:r.bottom});
 }
 // Neighbouring lines of one column split any vertical overlap, leaving a hairline gap between them.
 const gap=2;
 for(const line of lines){
  const below=lines.filter(o=>o!==line&&o.top>line.top&&o.left<line.right&&o.right>line.left).sort((a,b)=>a.top-b.top)[0];
  if(below&&line.bottom+gap>below.top){const middle=(line.bottom+below.top)/2;line.bottom=middle-gap/2;below.top=middle+gap/2;}
 }
 return lines;
}
window.highlightSentence=function(index){
 clearReadAloudHighlight();
 const sentence=pageList[index];if(!sentence)return;
 const doc=sentence.range.startContainer.ownerDocument,win=doc.defaultView;
 const dark=!!currentPreferences?.dark;
 // Multiply darkens the warm page to amber and leaves dark ink dark; screen lifts black to amber and keeps ivory text light.
 const fill=dark?'rgb(81, 65, 34)':'rgb(235, 218, 201)',blend=dark?'screen':'multiply';
 const layer=doc.createElement('div');
 layer.setAttribute('aria-hidden','true');
 // No z-index: that would make the layer its own isolated stacking context, and the marks would then
 // blend with nothing and paint as opaque boxes over the text. Positioned, it still paints after the text.
 layer.style.cssText='position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;';
 const x=win?.scrollX||0,y=win?.scrollY||0,pad=3;
 for(const line of lineBoxes(sentence.range)){
  const mark=doc.createElement('div');
  mark.style.cssText='position:absolute;border-radius:4px;mix-blend-mode:'+blend+';background:'+fill+';left:'+(line.left+x-pad)+'px;top:'+(line.top+y)+'px;width:'+(line.right-line.left+pad*2)+'px;height:'+(line.bottom-line.top)+'px;';
  layer.appendChild(mark);
 }
 // A child of <html>, not <body>: positioned against the whole document, outside the book's own layout.
 doc.documentElement.appendChild(layer);highlightLayer=layer;
};

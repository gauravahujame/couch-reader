/* Run on a fresh fixture shell. Select EPUB 2 with ?fixture=2; default is EPUB 3. */
(async () => {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  for (const version of [Number(new URLSearchParams(window.location.search).get("fixture") || 3)]) {
    const win = window;
    let events = [];
    win.ReaderBridge = {event: (type, payload) => events.push({type, data: JSON.parse(payload)})};
    const wait = async predicate => {
      const deadline = Date.now() + 15000;
      while (!predicate()) {
        const error = events.find(e => e.type === 'error');
        if (error) throw new Error(error.data.message);
        if (Date.now() > deadline) throw new Error('Timed out waiting for renderer');
        await new Promise(resolve => win.requestAnimationFrame(resolve));
      }
    };
    const settle = () => new Promise(resolve => win.requestAnimationFrame(() => win.requestAnimationFrame(resolve)));
    const location = () => rendition.currentLocation();
    const contains = cfi => {
      const compare = new ePub.CFI().compare, loc = location();
      return compare(loc.start.cfi, cfi) <= 0 && compare(loc.end.cfi, cfi) >= 0;
    };
    const config = {url: window.location.origin + '/book/' + version + '/EPUB/package.opf', preferences: {fontSize:32, dark:false}};
    // Avoid shadowing window.location with the location() helper above.
    config.url = window.location.origin + '/book/' + version + '/EPUB/package.opf';
    win.openBook(config);
    await wait(() => events.some(e => e.type === 'ready') && events.some(e => e.type === 'location'));
    const toc = events.find(e => e.type === 'chapters').data;
    assert(toc.length === 2, 'EPUB ' + version + ': missing chapter list');
    const img = rendition.getContents()[0].document.querySelector('img');
    await wait(() => img.complete);
    assert(img.naturalWidth === 1, 'EPUB ' + version + ': inline image did not load');
    const start = location().start.cfi;
    await win.turn(false); await settle();
    assert(location().start.cfi === start, 'Beginning boundary changed position');
    assert(events.some(e => e.type === 'boundary' && !e.data.end), 'Beginning boundary missing');
    await win.turn(true); await settle();
    const next = location().start.cfi;
    assert(next !== start, 'Next page did not move');
    await win.turn(false); await settle();
    assert(location().start.cfi === start, 'Previous page did not restore the first page');
    await win.turn(true); await settle();
    const anchor = location().start.cfi;
    for (const size of [24,28,32,36,42,48]) {
      const sizeAnchor=location().start.cfi;
      await win.preferences({fontSize:size,dark:size >= 36}); await settle();
      assert(contains(sizeAnchor), 'Font size ' + size + ' lost passage anchor');
      // Restore the same original passage before testing the next size.
      await rendition.display(anchor); await settle();
    }
    await win.chapter('two.xhtml'); await settle();
    assert(location().start.href === 'two.xhtml', 'Chapter jump failed');
    const chapterStart = location().start.cfi;
    await win.turn(false); await settle();
    assert(location().start.href === 'one.xhtml', 'Previous did not cross chapter boundary');
    await win.turn(true); await settle();
    assert(location().start.cfi === chapterStart, 'Next repeated or skipped chapter opening');
    let turns = 0;
    while (!location().atEnd && turns++ < 200) { await win.turn(true); await settle(); }
    assert(location().atEnd, 'Could not reach book end');
    const end = location().start.cfi;
    await win.turn(true); await settle();
    assert(location().start.cfi === end, 'End boundary changed position');
    assert(events.some(e => e.type === 'boundary' && e.data.end), 'End boundary missing');
    // Recreate the shell to simulate a renderer restart, restoring a CFI rather than page number.
    rendition.destroy(); document.getElementById('pages').replaceChildren(); opening=true;
    events=[];
    win.openBook({...config,preferences:{fontSize:42,dark:true},locator:{cfi:anchor,href:'one.xhtml'}});
    await wait(()=>events.some(e=>e.type==='ready')&&events.some(e=>e.type==='location'));
    assert(contains(anchor), 'Restart lost passage');
    results.push({version,passed:true,chapters:toc.length,inlineImage:true,sizes:6,themes:2,chapterBoundary:true,restoredAnchor:true});

  }
  return results;
})()

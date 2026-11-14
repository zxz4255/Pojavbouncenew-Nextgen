package liquidbounce.DroidBridge;

/**
 * The pieces of javascript the bridge puts into a page. They stay in the page, so an interaction
 * only sends its coordinates or its text instead of the whole handler.
 */
final class WryScripts {

    /**
     * Chrome 116 drops the unprefixed mask properties, which the theme uses to paint the menu icons.
     * The icons are drawn as a masked background, while an invisible image inside the same element
     * already carries the resolved source, so that source is what the prefixed form is built from.
     */
    static final String MASKS =
            "(function(){"
                    + "if(window.__wryMask){return 'already';}"
                    + "window.__wryMask=true;"
                    + "var style=document.createElement('style');"
                    + "style.textContent='.title-button-icon{-webkit-mask-position:center!important;"
                    + "-webkit-mask-repeat:no-repeat!important;-webkit-mask-size:contain!important;}';"
                    + "document.head.appendChild(style);"
                    + "var fixed=0;"
                    + "function fromImage(el){"
                    + "if(!el.classList||!el.classList.contains('title-button-icon')){return false;}"
                    + "if(el.__wryMasked){return false;}"
                    + "var img=el.querySelector('img');"
                    + "if(!img||!img.src){return false;}"
                    + "el.style.webkitMaskImage='url(\"'+img.src+'\")';"
                    + "el.__wryMasked=true;"
                    + "return true;"
                    + "}"
                    + "function fromStyle(el){"
                    + "if(el.__wryMasked){return false;}"
                    + "var raw=el.getAttribute('style');"
                    + "if(!raw||raw.indexOf('mask')<0){return false;}"
                    + "var pattern=/(^|;)\\s*mask-(image|position|repeat|size|origin|clip)\\s*:\\s*([^;]+)/g;"
                    + "var match;"
                    + "var found=false;"
                    + "var seen={};"
                    + "while((match=pattern.exec(raw))!==null){"
                    + "var value=match[3].trim().replace(/([^:])\\/{2,}/g,'$1/');"
                    + "el.style.setProperty('-webkit-mask-'+match[2],value);"
                    + "seen[match[2]]=true;"
                    + "found=true;"
                    + "}"
                    + "if(found){"
                    + "if(!seen.repeat){el.style.setProperty('-webkit-mask-repeat','no-repeat');}"
                    + "if(!seen.size){el.style.setProperty('-webkit-mask-size','contain');}"
                    + "if(!seen.position){el.style.setProperty('-webkit-mask-position','center');}"
                    + "el.__wryMasked=true;"
                    + "}"
                    + "return found;"
                    + "}"
                    + "function mirror(el){"
                    + "if(!el||el.nodeType!==1){return;}"
                    + "var done=fromImage(el)||fromStyle(el);"
                    + "if(done){fixed++;}"
                    + "}"
                    + "function scan(root){"
                    + "if(!root.querySelectorAll){return;}"
                    + "var icons=root.querySelectorAll('.title-button-icon');"
                    + "for(var i=0;i<icons.length;i++){mirror(icons[i]);}"
                    + "var styled=root.querySelectorAll('[style*=\"mask\"]');"
                    + "for(var j=0;j<styled.length;j++){mirror(styled[j]);}"
                    + "}"
                    + "var observer=new MutationObserver(function(records){"
                    + "for(var i=0;i<records.length;i++){"
                    + "var record=records[i];"
                    + "if(record.type==='attributes'){mirror(record.target);continue;}"
                    + "var added=record.addedNodes;"
                    + "for(var j=0;j<added.length;j++){"
                    + "if(added[j].nodeType===1){mirror(added[j]);scan(added[j]);}"
                    + "}"
                    + "}"
                    + "});"
                    + "observer.observe(document.body,{childList:true,subtree:true,"
                    + "attributes:true,attributeFilter:['style']});"
                    + "scan(document.body);"
                    + "console.log('[DroidBridge] mask rewrite on '+fixed+' elements');"
                    + "return 'masked '+fixed;"
                    + "})()";

    /**
     * The width the theme was drawn for, which is a desktop window.
     *
     * The theme writes its sizes in css pixels: the menu pads itself by 50, the drawer of the hud
     * editor is 600 wide, the text of the buttons at the top of the click gui is 14 tall. A phone
     * hands the page a viewport of 805x360, so those same numbers take up two and a half times the
     * share of the screen that they take on a desktop, and the whole page looks zoomed in. Sizing
     * the page from the viewport instead is what brings it back to the size of a desktop.
     */
    private static final int DESKTOP_WIDTH = 1600;

    /**
     * The narrowest the page is allowed to become, so that a small screen stays readable.
     */
    private static final float MIN_FIT = 0.2f;

    /**
     * The height the menu needs at zoom 1, taken from the media queries of the theme: they pick .8
     * for a window of 900, so the content is 900/.8 = 1125 tall in css pixels.
     */
    private static final int MENU_HEIGHT = 1125;

    /**
     * The theme sizes the menu by the width alone, so a narrow screen gets a zoom that is too large
     * for its height and the bottom bar falls below the screen. The zoom is therefore capped by what
     * the height can hold, while a window that is tall enough keeps the value of the theme.
     */
    private static final float MENU_FIT = 0.2f;

    /**
     * Applies the values the game actually uses to the parts of the theme that have no setting of
     * their own, before the first frame is shown.
     *
     * Why this exists: the theme asks the client for the window and the ClickGUI settings while it
     * paints, so its first frame is drawn with the defaults of the theme - the click gui of that
     * frame is at the wrong scale and only then shrinks to the configured one, which is the jolt
     * when it opens. The numbers are therefore read here first, the css is written with
     * `!important`, and {@code WryStack.pollFonts} keeps the page invisible until
     * {@code __wryScaleReady} is set, so the first frame that appears is already the final one.
     *
     * The ClickGUI scale is `guiScale * moduleScale`, exactly what the theme itself computes; it is
     * only applied earlier, and it is applied on every route, so the ClickGUI opened from the title
     * screen follows the slider as well.
     *
     * The HUD is zoomed by `guiScale * 50 * fit` on every route. The screen and the preview of the
     * hud editor are the same `.hud` element, so one rule means the preview is the size of what the
     * game draws. Before, only the screen was multiplied by the fit of the phone while the preview
     * was not, which is why the preview came out several times larger.
     */
    static final String SCALE =
            "(function(){"
                    + "if(window.__wryDriver){window.__wryScale();return 'already';}"
                    + "window.__wryDriver=true;"
                    + "var W=" + DESKTOP_WIDTH + ";"
                    + "var MIN=" + MIN_FIT + ";"
                    + "var MH=" + MENU_HEIGHT + ";"
                    + "var MF=" + MENU_FIT + ";"
                    + "var g=0;"
                    + "var s=0;"
                    + "var ready=false;"
                    + "function num(v){var n=parseFloat(v);return isFinite(n)&&n>0?n:0;}"
                    + "function clamp(v,lo,hi){return v<lo?lo:(v>hi?hi:v);}"
                    + "function fit(){var w=window.innerWidth||W;var f=w/W;"
                    + "if(!isFinite(f)||f<=0){return 1;}return clamp(f,MIN,1);}"
                    + "function css(text){"
                    + "var el=document.getElementById('wry-scale');"
                    + "if(!el){el=document.createElement('style');el.id='wry-scale';"
                    + "document.head.appendChild(el);}"
                    + "if(el.textContent!==text){el.textContent=text;}}"
                    + "function menuZoom(){"
                    + "var base=window.__wryMenuBase;"
                    + "if(!base){var el=document.querySelector('.menu');if(!el){return 0;}"
                    + "var z=num(getComputedStyle(el).zoom);if(!z){return 0;}"
                    + "base=z;window.__wryMenuBase=base;}"
                    + "var room=(window.innerHeight||MH)/MH;"
                    + "var want=Math.min(base,Math.max(MF,room));"
                    + "return want>=base?0:want;}"
                    + "function build(){"
                    + "var f=fit();var out='';"
                    + "if(g>0&&s>0){var k=g*s;"
                    + "out+='.scaled-clickgui-content{transform:scale('+(k*50).toFixed(4)+'%)!important;"
                    + "width:'+(200/k).toFixed(4)+'vw!important;height:'+(200/k).toFixed(4)+'vh!important;}';}"
                    + "out+='.available-tabs{zoom:'+f.toFixed(4)+';}';"
                    + "out+='.search{zoom:'+f.toFixed(4)+';}';"
                    + "out+='.component-drawer{zoom:'+f.toFixed(4)+';}';"
                    + "if(g>0){out+='.hud{zoom:'+(g*50).toFixed(4)+'%!important;}';}"
                    + "var m=menuZoom();if(m>0){out+='.menu{zoom:'+m.toFixed(4)+'!important;}';}"
                    + "return out;}"
                    + "window.__wryScale=function(){css(build());return 'g='+g+' s='+s;};"
                    + "function readWindow(json){var v=num(json&&json.scaleFactor);if(v){g=v;}}"
                    + "function readSettings(json){"
                    + "var list=json&&json.value;if(!list||!list.length){return;}"
                    + "for(var i=0;i<list.length;i++){var e=list[i];"
                    + "if(!e||e.name!=='Scale'){continue;}"
                    + "var v=num(e.value);if(v){s=clamp(v,0.1,4);}return;}}"
                    + "function get(path,done){"
                    + "try{fetch(path,{credentials:'same-origin'})"
                    + ".then(function(r){return r.ok?r.json():null;})"
                    + ".then(done).catch(function(){done(null);});}catch(e){done(null);}}"
                    + "function refresh(){"
                    + "var left=2;"
                    + "function done(){if(--left>0){return;}"
                    + "if(!ready){ready=true;window.__wryScaleReady=true;}"
                    + "window.__wryScale();}"
                    + "get('/api/v1/client/window',function(j){readWindow(j);done();});"
                    + "get('/api/v1/client/modules/settings?name=ClickGUI',"
                    + "function(j){readSettings(j);done();});}"
                    + "window.__wryScale();"
                    + "refresh();"
                    + "setTimeout(function(){if(!ready){ready=true;window.__wryScaleReady=true;"
                    + "window.__wryScale();}},1500);"
                    + "window.addEventListener('hashchange',function(){"
                    + "window.__wryScale();refresh();});"
                    + "window.addEventListener('resize',function(){window.__wryScale();});"
                    + "setInterval(function(){"
                    + "var h=location.hash||'';if(h.indexOf('/clickgui')>=0){refresh();}},400);"
                    + "return 'driver';"
                    + "})()";

    /**
     * The theme draws its text with the Inter fonts, which it fetches over http at 2.8 MB. A page
     * that paints before they arrive lays the text out with a fallback font, and the swap to the real
     * font leaves the white blocks of the previous frame behind. Waiting for the fonts before the
     * first frame becomes visible is what keeps those blocks off the screen.
     */
    static final String FONTS =
            "(function(){"
                    + "if(!document.fonts||!document.fonts.ready){return 'unsupported';}"
                    + "var pending=[];"
                    + "['300','400','500','600'].forEach(function(weight){"
                    + "pending.push(document.fonts.load(weight+' 14px Inter'));"
                    + "});"
                    + "window.__wryFonts=false;"
                    + "Promise.all(pending).then(function(){window.__wryFonts=true;})"
                    + ".catch(function(){window.__wryFonts=true;});"
                    + "return 'loading';"
                    + "})()";

    /**
     * A pointer event is fired from the page itself, so the target is resolved by hit testing and
     * the coordinates are taken as fractions of the viewport.
     */
    static final String DISPATCH =
            "window.__wry=function(phase,fx,fy,btn,detail,bit){"
                    + "var bx=fx*window.innerWidth,by=fy*window.innerHeight;"
                    + "var t=document.elementFromPoint(bx,by)||document.body;"
                    + "var o={bubbles:true,cancelable:true,view:window,button:btn,buttons:bit,"
                    + "clientX:bx,clientY:by,screenX:bx,screenY:by,detail:detail,"
                    + "pointerId:1,pointerType:'mouse',isPrimary:true,width:1,height:1,"
                    + "pressure:phase==='up'?0:0.5};"
                    + "if(phase==='down'){"
                    + "var target=t;"
                    + "while(target&&target!==document.body){"
                    + "if(target.tagName==='INPUT'||target.tagName==='TEXTAREA'"
                    + "||target.isContentEditable||target.getAttribute('contenteditable')==='true'){break;}"
                    + "target=target.parentElement;}"
                    + "if(target&&target!==document.body&&target.focus){"
                    + "try{target.focus();}catch(e){}}"
                    + "t.dispatchEvent(new PointerEvent('pointerdown',o));"
                    + "t.dispatchEvent(new MouseEvent('mousedown',o));"
                    + "}else if(phase==='up'){"
                    + "t.dispatchEvent(new PointerEvent('pointerup',o));"
                    + "t.dispatchEvent(new MouseEvent('mouseup',o));"
                    + "if(btn===2){t.dispatchEvent(new MouseEvent('contextmenu',o));}"
                    + "else{t.dispatchEvent(new MouseEvent('click',o));"
                    + "if(detail>1){t.dispatchEvent(new MouseEvent('dblclick',o));}}"
                    + "}else{"
                    + "t.dispatchEvent(new PointerEvent('pointermove',o));"
                    + "t.dispatchEvent(new MouseEvent('mousemove',o));}"
                    + "return t.tagName;};";

    static final String DISPATCH_SETUP =
            "(function(){"
                    + "if(window.__wry){return 'already';}"
                    + DISPATCH
                    + "return 'ready';})()";

    /**
     * Puts text into the field the page has focused, the way typing would.
     */
    static final String INSERT =
            "var e=document.activeElement;"
                    + "if(!e||e===document.body){return 'no field';}"
                    + "var editable=e.tagName==='INPUT'||e.tagName==='TEXTAREA'"
                    + "||e.isContentEditable||e.getAttribute('contenteditable')==='true';"
                    + "if(!editable){return 'not editable';}"
                    + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){e.textContent+=text;}"
                    + "else{"
                    + "var start=e.selectionStart;"
                    + "var end=e.selectionEnd;"
                    + "if(typeof start==='number'){"
                    + "e.value=e.value.slice(0,start)+text+e.value.slice(end);"
                    + "e.selectionStart=start+text.length;"
                    + "e.selectionEnd=start+text.length;"
                    + "}else{e.value+=text;}}"
                    + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return e.tagName;";

    /**
     * Enter is raised in the page, because the fields that wait for it listen to the dom event and a
     * key event of a web view without an input connection never becomes one.
     */
    static final String ENTER =
            "var e=document.activeElement||document.body;"
                    + "var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,"
                    + "view:window};"
                    + "e.dispatchEvent(new KeyboardEvent('keydown',k));"
                    + "e.dispatchEvent(new KeyboardEvent('keypress',k));"
                    + "e.dispatchEvent(new KeyboardEvent('keyup',k));"
                    + "return e.tagName;";

    /**
     * Backspace and delete are done in the page, because a key event does not edit a field of a web
     * view that the system has never given an input connection.
     */
    static String edit(boolean backspace) {
        return "var e=document.activeElement;"
                + "if(!e||e===document.body){return 'no field';}"
                + "var editable=e.tagName==='INPUT'||e.tagName==='TEXTAREA'"
                + "||e.isContentEditable||e.getAttribute('contenteditable')==='true';"
                + "if(!editable){return 'not editable';}"
                + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){"
                + (backspace
                    ? "var s=String(e.textContent||'');if(!s.length){return 'empty';}e.textContent=s.slice(0,-1);"
                    : "return 'delete in rich text';")
                + "}else{"
                + "var v=e.value||'';"
                + "var start=e.selectionStart;"
                + "var end=e.selectionEnd;"
                + "if(typeof start!=='number'){start=v.length;end=v.length;}"
                + (backspace
                    ? "if(start===end){if(start===0){return 'at start';}start=start-1;}"
                    : "if(start===end){if(end>=v.length){return 'at end';}end=end+1;}")
                + "e.value=v.slice(0,start)+v.slice(end);"
                + "e.selectionStart=start;"
                + "e.selectionEnd=start;"
                + "}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'edited';";
    }

    /**
     * The axis of a scroll event does not carry the steps the game reports, so the wheel is raised in
     * the page instead, where the delta is what the page expects.
     */
    static String wheel(float x, float y, float delta) {
        return "var bx=" + x + "*window.innerWidth,by=" + y + "*window.innerHeight;"
                + "var t=document.elementFromPoint(bx,by);"
                + "var dy=" + delta + ";"
                + "var moved=null;"
                + "var found=false;"
                + "while(t&&t!==document.body&&t!==document.documentElement){"
                + "var style=getComputedStyle(t);"
                + "var scrollable=(style.overflowY==='auto'||style.overflowY==='scroll')"
                + "&&t.scrollHeight>t.clientHeight;"
                + "if(scrollable){"
                + "found=true;"
                + "var before=t.scrollTop;"
                + "var smooth=getComputedStyle(t).scrollBehavior;"
                + "if(smooth!=='auto'){t.style.scrollBehavior='auto';}"
                + "t.scrollTop=before+dy;"
                + "if(smooth!=='auto'){t.style.scrollBehavior=smooth;}"
                + "if(t.scrollTop!==before){moved=t.tagName+' '+before+'->'+t.scrollTop;break;}"
                + "var room=dy>0?(t.scrollHeight-t.clientHeight-before):before;"
                + "if(room<=0){moved=t.tagName+' at '+(dy>0?'end':'start');}"
                + "break;}"
                + "t=t.parentElement;}"
                + "if(!moved&&!found){"
                + "if(document.scrollingElement){"
                + "var root=document.scrollingElement;"
                + "var was=root.scrollTop;"
                + "root.scrollTop=was+dy;"
                + "moved='root '+was+'->'+root.scrollTop;"
                + "}else{window.scrollBy(0,dy);moved='window';}}"
                + "var chain=[];"
                + "var p=document.elementFromPoint(bx,by);"
                + "while(p&&p!==document.documentElement){"
                + "var s2=getComputedStyle(p);"
                + "chain.push((p.className||p.tagName)+'[ov'+s2.overflowY+' '+p.clientHeight+'/'+p.scrollHeight+']');"
                + "p=p.parentElement;}"
                + "return (moved||'nothing')+' | hit '+chain.slice(0,4).join(' < ');";
    }

    private WryScripts() {
    }
}

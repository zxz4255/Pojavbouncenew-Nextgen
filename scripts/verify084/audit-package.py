#!/usr/bin/env python3
"""Strict immutable083 -> 084; preserve entire baseline except explicit input/item fixes."""
import hashlib,json,pathlib,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2])
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(baseline.read_bytes())=='9b863ad05f2f66735bdfa03dbb9363322518dbdc68592493811fe4ac3ba7a8a1','Wrong083 baseline'
r='src/main/java/net/ccbluex/liquidbounce/'
allowed={r+'utils/input/AndroidGuiCursor.java',r+'injection/mixins/minecraft/client/MixinGui.java'}
audit='scripts/verify084/scope-audit.json'
additions={'084-ANDROID-NATIVE-CURSOR-ONLY.md',audit}
additions.update(str(p.relative_to(root)) for p in (root/'scripts/verify084').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
ignored_parts={'.gradle','.kotlin','__pycache__','build','.cache','node_modules'}
results=(root/'scripts/verify084/results.txt').read_text()
for marker in ['20036 assertions','084 desktop 4 assertions','76 checks']:
    assert marker in results,marker
helper=(root/r/'utils/input/AndroidGuiCursor.java').read_text()
for forbidden in ['GuiGraphicsExtractor','ARROW','public static void extract(', 'getScaledXPos','getScaledYPos','graphics.', 'SDL_WarpMouse','SDL_CreateCursor']:
    assert forbidden not in helper,forbidden
assert 'lb$extractAndroidCursor' not in (root/r/'injection/mixins/minecraft/client/MixinGui.java').read_text()
with zipfile.ZipFile(baseline) as old:
    names={n for n in old.namelist() if not n.endswith('/')};changed=[];identical=[]
    for n in sorted(names):
        p=root/n
        assert p.is_file() and not p.is_symlink(),'Missing/symlink '+n
        before=old.read(n);after=p.read_bytes()
        if before!=after:
            assert n in allowed,'Out-of-scope modification '+n
            changed.append({'path':n,'before':sha(before),'after':sha(after)})
        else:identical.append(n)
    assert {x['path'] for x in changed}==allowed
    assert not additions&names
    ignored=[]
    for p in root.rglob('*'):
        if not p.is_file():continue
        n=str(p.relative_to(root))
        if n in names|additions:continue
        assert any(part in ignored_parts for part in p.relative_to(root).parts),'Unexpected untracked '+n
        ignored.append(n)
    assert not any('neumorph' in n.lower() for n in names|additions),'Deleted Neumorphism source returned'
    report={'baseline':'083','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[],'added':sorted(additions),'packaged_count':len(names|additions),'excluded_generated':sorted(ignored),'protected':'Every083 byte except two named native cursor/GUI files is unchanged. Software cursor implementation and rendering hook removed; sword1.8, all mouse event dispatch,082 Regex,081 music/online keys and all other features/config retained.'}
    (root/audit).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for n in sorted(names|additions):
            assert (root/n).is_file() and not (root/n).is_symlink(),n
            z.write(root/n,n)
with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    assert set(z.namelist())==names|additions
    assert len(z.namelist())==len(names|additions)
    for n in z.namelist():assert z.read(n)==(root/n).read_bytes(),n
raw=dest.read_bytes();blocks=b''.join(hashlib.sha256(raw[i:i+4194304]).digest() for i in range(0,len(raw),4194304))
result={'file':str(dest),'baseline':'083','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'084 real MC26.3/SDL/Mixin API compile;76 bytecode checks,3 state hooks/zero drawing hooks;20036 Android+4 desktop assertions/2000 simulatedGUI cycles with explicit runtime hosts. Native pointer only/no synthesis/remap/warp; native refusal has no software fallback. Other suites unchanged/not rerun. No full Gradle/Mixin/Android hardware/system pointer test.','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/084-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))

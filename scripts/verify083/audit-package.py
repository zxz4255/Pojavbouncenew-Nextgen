#!/usr/bin/env python3
"""Strict immutable082 -> 083; preserve entire baseline except explicit input/item fixes."""
import hashlib,json,pathlib,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2])
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(baseline.read_bytes())=='26b08ebf9ea877f2504852da4183ad76bb069a7ac99545ad152cf99fcc3ce1a2','Wrong082 baseline'
r='src/main/java/net/ccbluex/liquidbounce/'
allowed={r+'injection/mixins/'+n for n in ['minecraft/client/MixinGui.java','minecraft/client/MixinMouseHandler.java','minecraft/item/MixinItem.java']}
audit='scripts/verify083/scope-audit.json'
additions={'083-ANDROID-CURSOR-LEGACY-BLOCK.md',r+'utils/input/AndroidGuiCursor.java',r+'utils/item/LegacySwordBlocking.java',audit}
additions.update(str(p.relative_to(root)) for p in (root/'scripts/verify083').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
ignored_parts={'.gradle','.kotlin','__pycache__','build','.cache','node_modules'}
for name,marker in [('results.txt','7085 assertions'),('retained082-results.txt','PASS: 082 10044 assertions:'),('retained081-results.txt','PASS: 081 144 assertions:')]:
    assert marker in (root/'scripts/verify083'/name).read_text(),name
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
    report={'baseline':'082','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[],'added':sorted(additions),'packaged_count':len(names|additions),'excluded_generated':sorted(ignored),'protected':'Every082 file other than three named existing input/item mixins is byte-identical; no module/config/schema changes;082 Regex fix,081 music/online keys,080 Neo fixes, knife/inventory/chest preserved.'}
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
result={'file':str(dest),'baseline':'082','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'083 real MC26.3/VFP5.1.1/SDL API compilation;77 selector/LVT bytecode checks; unchanged real-API-compiled code:7085 Android/sword assertions (1000 simulated round changes),2 desktop assertions; no-Via JVM1000 queries+cursor PASS.08210044 and081 offline suites rerun PASS. No full Gradle/Mixin runtime/game/device/SDL-native/actual translation/server-match validation; exact reported post-match root cause not confirmed.','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/083-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))

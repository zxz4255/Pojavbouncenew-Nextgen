#!/usr/bin/env python3
"""Immutable delivered079 -> 080 strict scope/content audit and flat source package."""
import hashlib,json,pathlib,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2])
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(baseline.read_bytes())=='49433d5437a97e094852f64a8167cab37f6d258d2876fe250437e239695ac1d0','Wrong 079 baseline'
r='src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/player/neoraven/'
allowed={r+n for n in ['ModuleNeoChestAura.kt','ModuleNeoChestStealer.kt','ModuleNeoInvManager.kt']}
audit='scripts/verify080/scope-audit.json'
with zipfile.ZipFile(baseline) as old:
    names={n for n in old.namelist() if not n.endswith('/')};changed=[];identical=[]
    for n in sorted(names):
        p=root/n
        assert p.is_file(),'Missing '+n
        before=old.read(n);after=p.read_bytes()
        if before!=after:
            assert n in allowed,'Out-of-scope change '+n
            changed.append({'path':n,'before':sha(before),'after':sha(after)})
        else:identical.append(n)
    assert {x['path'] for x in changed}==allowed,'Unexpected changed set'
    additions={'080-NEORAVEN-COMPILE-FIX.md',audit}
    for directory in ['scripts/verify080']:
        additions.update(str(p.relative_to(root)) for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    assert not (additions&names)
    report={'baseline':'079','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[],'added':sorted(additions),'packaged_count':len(names|additions),'protected':'Every baseline file except the three explicit Neo module fixes is byte-identical; all music/KGG, Island, knife, old inventory/chest, ClickGUI, historical tests/docs and logs retained.'}
    (root/audit).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for n in sorted(names|additions):z.write(root/n,n)
with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    assert set(z.namelist())==names|additions
    for n in z.namelist():assert z.read(n)==(root/n).read_bytes(),n
raw=dest.read_bytes();blocks=b''.join(hashlib.sha256(raw[i:i+4194304]).digest() for i in range(0,len(raw),4194304))
result={'file':str(dest),'baseline':'079','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'Original079 three compiler errors reproduced; patched actual3 modules compile against real MC26.3 and MinecraftShortcuts with other host fixtures; existing chest/inventory model regressions PASS; no full Gradle/game/Pojav validation','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/080-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))

#!/usr/bin/env python3
"""Strict immutable080 -> 081 audit and flat source package; no silent source omission."""
import hashlib,json,pathlib,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2])
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(baseline.read_bytes())=='00fa0cf22100e6887214b923aab91a36f5516ffe725544e5b2f4a937245a1851','Wrong 080 baseline'
r='src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/'
allowed={r+n for n in ['KugouClient.kt','KugouLocalFiles.kt','MusicManager.kt','MusicScreen.kt']}
audit='scripts/verify081/scope-audit.json'
additions={'081-KGG-ONLINE-KEYS.md',r+'KugouOnlineKeys.kt',audit}
for directory in ['scripts/verify081','references/kgg081']:
    additions.update(str(p.relative_to(root)) for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
ignored_parts={'.gradle','.kotlin','__pycache__','build','.cache','node_modules'}
with zipfile.ZipFile(baseline) as old:
    names={n for n in old.namelist() if not n.endswith('/')};changed=[];identical=[]
    for n in sorted(names):
        p=root/n
        assert p.is_file() and not p.is_symlink(),'Missing/symlink '+n
        before=old.read(n);after=p.read_bytes()
        if before!=after:
            assert n in allowed,'Out-of-scope change '+n
            changed.append({'path':n,'before':sha(before),'after':sha(after)})
        else:identical.append(n)
    assert {x['path'] for x in changed}==allowed,'Unexpected changed set'
    assert not (additions&names)
    ignored=[]
    for p in root.rglob('*'):
        if not p.is_file():continue
        n=str(p.relative_to(root))
        if n in names|additions:continue
        assert any(part in ignored_parts for part in p.relative_to(root).parts),'Unexpected untracked file '+n
        ignored.append(n)
    assert not any('neumorph' in n.lower() for n in names|additions),'Deleted Neumorphism style was reintroduced'
    report={'baseline':'080','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[],'added':sorted(additions),'packaged_count':len(names|additions),'excluded_generated':sorted(ignored),'protected':'All baseline files except four explicit music integration/UI files are byte-identical, including 080 Neo compile fixes, knife, inventory, chest, ClickGUI, Island, old crypto and historical tests/configuration.'}
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
result={'file':str(dest),'baseline':'080','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'081 144 assertions + prior music/KGG/Island suites PASS; actual Node upstream wire oracle; synthetic intercepted API/loopback download integration; no live account, actual TLS, native audio or full Gradle/game/Pojav test','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/081-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))

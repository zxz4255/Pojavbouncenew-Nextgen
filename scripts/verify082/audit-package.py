#!/usr/bin/env python3
"""Strict immutable081 -> 082 audit; include every source-baseline byte except explicit fixes."""
import hashlib,json,pathlib,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[2]
baseline=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2])
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(baseline.read_bytes())=='4ef8b17ad9ec340ce9ca506434db7b1c70ff3a5512e972db15d2b7ff1d55fc12','Wrong081 baseline'
r='src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/setting/'
allowed={r+n for n in ['SettingRenderer.kt','SettingWidgets.kt','ListSettingWidgets.kt']}
audit='scripts/verify082/scope-audit.json'
additions={'082-CLICKGUI-REGEX-FIX.md',r+'TextSettingCodec.kt',audit}
additions.update(str(p.relative_to(root)) for p in (root/'scripts/verify082').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
ignored_parts={'.gradle','.kotlin','__pycache__','build','.cache','node_modules'}
assert 'PASS: 082 10044 assertions:' in (root/'scripts/verify082/results.txt').read_text()
assert 'PASS: 081 144 assertions:' in (root/'scripts/verify082/retained081-results.txt').read_text()
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
    report={'baseline':'081','baseline_sha256':sha(baseline.read_bytes()),'baseline_files':len(names),'changed':changed,'identical_count':len(identical),'deleted':[],'added':sorted(additions),'packaged_count':len(names|additions),'excluded_generated':sorted(ignored),'protected':'Every081 file other than three named ClickGUI widgets is byte-identical; online keys/music, Neo compile fixes, knife, inventory, chest, config factories/adapters and all historical tests/docs retained.'}
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
result={'file':str(dest),'baseline':'081','files':len(names|additions),'size':len(raw),'sha256':sha(raw),'md5':hashlib.md5(raw).hexdigest(),'dropbox_content_hash':sha(blocks),'tests':'082: actual081 crash/list corruption negative controls; 10044 assertions including2000 reopen/edit cycles PASS. Unchanged081 offline suite rerun PASS. Actual Value/editor/dispatcher and extracted widget/list bodies with explicit game/draw fixtures. No full Gradle/game/Android/live-account/native validation.','changed_baseline_files':len(changed),'identical_baseline_files':len(identical),'additions':len(additions),'zip_integrity':'PASS','flat_root':True}
dest.with_suffix(dest.suffix+'.sha256').write_text(result['sha256']+'  '+dest.name+'\n')
pathlib.Path('/home/user/082-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))

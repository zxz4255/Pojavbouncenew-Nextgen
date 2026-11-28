from pathlib import Path
import re,json,hashlib,sys
p=Path(sys.argv[1]);s=p.read_text();chunks=re.split(r'\n(?=\[NeoInvManager/092\])',s)
requests=[];committed={}
for c in chunks:
 head=c.splitlines()[0] if c.splitlines() else ''
 if 'stage=COMMITTED' in head:
  wid=re.search(r'write=(\d+)',head).group(1);committed[wid]=head
 if 'stage=REQUEST' not in head:continue
 fields=dict(re.findall(r'(write|previous|requested|actual|bindAction)=([^ ]+)',head))
 fields['elapsed_ms']=int(re.search(r'\+(\d+)ms',head).group(1))
 fields['origin']='NeoInvHotkeys.release' if 'NeoInvHotkeys.release(' in c else 'NeoInvHotkeys.press' if 'NeoInvHotkeys.press(' in c else 'other'
 requests.append(fields)
closing=[r for r in requests if r['requested']=='false']
opening=[r for r in requests if r['requested']=='true']
assert len(opening)==23 and len(closing)==23
assert all(r['bindAction']=='HOLD' and r['origin']=='NeoInvHotkeys.release' for r in closing)
assert all(r['bindAction']=='HOLD' and r['origin']=='NeoInvHotkeys.press' for r in opening)
assert all('actual=false' in committed[r['write']] for r in closing)
assert all('actual=true' in committed[r['write']] for r in opening)
result={'input_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'enable_requests':len(opening),'disable_requests':len(closing),'disable_origin':'23/23 NeoInvHotkeys.release, bindAction=HOLD','first_cycle':{'enable_ms':opening[0]['elapsed_ms'],'release_disable_ms':closing[0]['elapsed_ms'],'held_ms':closing[0]['elapsed_ms']-opening[0]['elapsed_ms']},'committed':'all 23 enable and 23 disable requests committed as requested','conclusion':'This capture establishes HOLD-release deactivation, not a spontaneous module-internal shutdown. It does not rule out unrelated failures outside this capture. User approved one-time HOLD→TOGGLE migration.','privacy':'No original log, session IDs, full stack or device paths are copied into the source package.'}
out=Path(__file__).with_name('inv-log-analysis.json');out.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n');print(json.dumps(result,indent=2,ensure_ascii=False))

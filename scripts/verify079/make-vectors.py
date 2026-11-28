#!/usr/bin/env python3
"""Deterministic NON-MUSIC synthetic fixtures, independently encrypted in Python.
Derivation/cipher oracle is pinned rallait/a8b3236 (MIT), not the Java implementation.
No account, live token, real eKey or copyrighted audio is required.
"""
import ast, base64, hashlib, json, math, pathlib, struct
R=pathlib.Path(__file__).resolve().parents[2]
source=(R/'references/kgg079/python-decrypt_my_audio.py').read_text()
names={'DecryptError','_u32','_tea_decrypt_block','decrypt_tencent_tea','_simple_make_key','_derive_key_v1','_derive_key_v2','derive_key','MapCipher','Rc4Cipher','make_qmc_cipher','_qmc_mask_shift'}
tree=ast.parse(source)
ns={'base64':base64,'math':math,'TEA_DELTA':0x9e3779b9,'struct':struct,'_MASK_PERIOD':0x7fff}
for node in tree.body:
    if isinstance(node,(ast.FunctionDef,ast.ClassDef)) and node.name in names:
        exec(compile(ast.Module(body=[node],type_ignores=[]),'pinned-python-oracle','exec'),ns)

def tea_block(block,key):
    a,b=struct.unpack('>2I',block);k=struct.unpack('>4I',key);s=0
    for _ in range(16):
        s=(s+0x9e3779b9)&0xffffffff
        a=(a+(((b<<4)+k[0])^(b+s)^((b>>5)+k[1])))&0xffffffff
        b=(b+(((a<<4)+k[2])^(a+s)^((a>>5)+k[3])))&0xffffffff
    return struct.pack('>2I',a,b)

def tea_encrypt(data,key):
    pad=(-(len(data)+10))%8
    data=bytes([pad])+bytes(range(pad))+b'\x37\x42'+data+b'\0'*7
    prev_a=prev_c=b'\0'*8;out=bytearray()
    for start in range(0,len(data),8):
        a=bytes(x^y for x,y in zip(data[start:start+8],prev_c))
        c=bytes(x^y for x,y in zip(tea_block(a,key),prev_a))
        out.extend(c);prev_a=a;prev_c=c
    return bytes(out)

folder=R/'scripts/verify079/vectors';folder.mkdir(exist_ok=True)
rows=[]
for size in (256,301):
    key=bytes((i*7+3)&255 for i in range(size));tk=bytes(v for i,sk in enumerate([105,86,70,56,43,32,21,11]) for v in (sk,key[i]))
    raw=key[:8]+tea_encrypt(key[8:],tk)
    for version in (1,2):
        packed=raw if version==1 else b'QQMusic EncV2,Key:'+tea_encrypt(tea_encrypt(base64.b64encode(raw),b'**#!(#$%&^a1cZ,T'),b'386ZJY!@#*$%^&)(')
        ekey=base64.b64encode(packed).decode()
        assert ns['derive_key'](ekey)==key
        plain=b'fLaC'+bytes(range(256))*273 # header fixture, NOT decodable FLAC
        cipher=ns['MapCipher'](key) if size<=300 else ns['Rc4Cipher'](key)
        encrypted=bytearray(plain);cipher.decrypt(encrypted,0)
        hash=hashlib.md5(f'fake-{size}-{version}'.encode()).hexdigest()
        header=bytearray(1024);header[:16]=bytes.fromhex('7cd532eb86027f4ba8afa68e0fff9914')
        struct.pack_into('<II',header,0x10,1024,5);struct.pack_into('<I',header,0x44,32);header[0x48:0x68]=hash.encode()
        name=f'synthetic-{size}-v{version}.kgg'
        (folder/name).write_bytes(header+encrypted)
        rows.append({'name':name,'hash':hash,'ekey':ekey,'key_hex':key.hex(),'plain_sha256':hashlib.sha256(plain).hexdigest(),'cipher_sha256':hashlib.sha256(encrypted).hexdigest()})
(folder/'synthetic.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Generated 4 reproducible synthetic v5 vectors: Map/RC4 x eKey v1/v2; Python oracle derivation verified')

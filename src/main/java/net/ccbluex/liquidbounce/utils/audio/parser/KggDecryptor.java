/* SPDX-License-Identifier: GPL-3.0-or-later
 * KGM v3 and KGG/v5 QMC algorithms adapted from Aaron-gx/KGG-Decryptor
 * commit 82aac3a78d2a90921021b9bae1d486faae1a736e (MIT).
 * See references/kgg079/LICENSE and THIRD-PARTY-NOTICES.md.
 * Only decrypt files for which the user already has playback rights and matching keys.
 */
package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded, streaming, key-assisted offline decryption. Never obtains account credentials. */
public final class KggDecryptor {
    private static final byte[] KGM = hex("7cd532eb86027f4ba8afa68e0fff9914");
    private static final byte[] VPR = hex("0528bc96e9e45a4391aabdd07af53631");
    private static final int MAX_HEADER = 1024 * 1024;
    public interface KeyLookup { String find(String audioHash) throws IOException; }
    public static final class MissingKeyException extends IOException {
        public MissingKeyException(String hash) { super("KGG 缺少匹配 eKey（audio_hash=" + hash + "）；请在本地密钥文件中提供该歌曲的合法密钥"); }
    }
    public static final class Header {
        public final int version;
        public final long audioOffset;
        public final String audioHash;
        private final byte[] seed;
        private Header(int v, long off, String hash, byte[] seed) { version=v; audioOffset=off; audioHash=hash; this.seed=seed; }
    }
    private KggDecryptor() {}
    private static IOException invalid() { return new IOException("KGG 文件头无效、截断或偏移越界"); }
    public static Header readHeader(File input) throws IOException {
        long size=input.length();
        if (!input.isFile() || size < 0x3c || size > MusicAudioParserFactory.MAX_FILE_BYTES) throw invalid();
        try (RandomAccessFile f=new RandomAccessFile(input,"r")) {
            byte[] head=new byte[0x3c]; f.readFully(head);
            byte[] magic=Arrays.copyOf(head,16);
            if (!Arrays.equals(magic,KGM) && !Arrays.equals(magic,VPR)) throw invalid();
            long offset=le(head,0x10), version=le(head,0x14);
            if (offset < 0x3c || offset > MAX_HEADER || offset >= size) throw invalid();
            String hash="";
            if (version==3) {
                if (le(head,0x18)!=1) throw new IOException("不支持的 KGM v3 密钥槽位");
            } else if (version==5) {
                if (offset < 0x68) throw invalid();
                f.seek(0x44); byte[] length=new byte[4]; f.readFully(length);
                long n=le(length,0);
                if ((n!=32 && n!=33) || 0x48+n>offset) throw invalid();
                byte[] raw=new byte[(int)n]; f.readFully(raw);
                if (n==33 && raw[32]!=0) throw invalid();
                hash=new String(raw,0,32,StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (!hash.matches("[a-f0-9]{32}")) throw invalid();
            } else throw new IOException("不支持的酷狗加密版本："+version);
            return new Header((int)version,offset,hash,Arrays.copyOfRange(head,0x2c,0x3c));
        }
    }
    private static long le(byte[] b,int at) { return Integer.toUnsignedLong(ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancel.getAsBoolean()) throw new InterruptedIOException("KGG 解密已取消");
    }
    /** Publishes a NEW output only after codec-header validation. Source is never modified/deleted. */
    public static MusicAudioParserFactory.Codec decrypt(File input, File output, KeyLookup lookup, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        Header h=readHeader(input);
        byte[] key=null;
        if (h.version==5) {
            String ekey=lookup.find(h.audioHash);
            if (ekey==null || ekey.isBlank()) throw new MissingKeyException(h.audioHash);
            key=deriveKey(ekey);
        }
        Mask mask=h.version==3 ? v3(h.seed) : cipher(key);
        Path temp=null;
        try {
            Path target=output.toPath().toAbsolutePath();
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) throw new IOException("解密输出已存在，拒绝覆盖");
            temp=Files.createTempFile(target.getParent(),".kgg-",".part");
            try (RandomAccessFile in=new RandomAccessFile(input,"r"); OutputStream out=Files.newOutputStream(temp)) {
                in.seek(h.audioOffset);
                byte[] chunk=new byte[16384]; long offset=0;
                int n;
                while ((n=in.read(chunk))!=-1) {
                    check(cancelled);
                    if (h.audioOffset+offset+n>MusicAudioParserFactory.MAX_FILE_BYTES) throw new IOException("KGG 超过大小上限");
                    mask.apply(chunk,n,offset); out.write(chunk,0,n); offset+=n;
                }
                if (offset==0) throw invalid();
            }
            check(cancelled);
            MusicAudioParserFactory.Codec codec=MusicAudioParserFactory.detect(temp.toFile());
            if (codec==MusicAudioParserFactory.Codec.UNKNOWN) throw new IOException("KGG 解密结果不是支持的音频；密钥可能不匹配或格式尚不支持");
            check(cancelled);
            // Same-filesystem rename, no REPLACE_EXISTING: never overwrite another output/source.
            Files.move(temp,target);
            temp=null;
            return codec;
        } finally {
            if (key!=null) Arrays.fill(key,(byte)0);
            if (temp!=null) Files.deleteIfExists(temp);
        }
    }
    private interface Mask { void apply(byte[] b,int length,long offset); }
    private static byte[] kgmd5(byte[] b) {
        try {
            byte[] d=MessageDigest.getInstance("MD5").digest(b), r=new byte[16];
            for(int i=0;i<16;i+=2) {r[i]=d[14-i];r[i+1]=d[15-i];} return r;
        } catch (NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static Mask v3(byte[] seed) {
        byte[] slot=kgmd5(new byte[]{0x6c,0x2c,0x2f,0x27}), file=Arrays.copyOf(kgmd5(seed),17);file[16]=0x6b;
        return (b,n,offset)->{ for(int j=0;j<n;j++) {long i=offset+j;int v=(b[j]^file[(int)(i%17)])&255;v^=v<<4;b[j]=(byte)(v^slot[(int)(i%16)]^i^(i>>8)^(i>>16)^(i>>24));} };
    }
    // Package-private primitives for independent known-answer/segmentation tests.
    static byte[] deriveKey(String ekey) throws IOException {
        if (ekey.length()>16384) throw new IOException("eKey 超过安全上限");
        byte[] raw=decode(ekey);
        byte[] prefix="QQMusic EncV2,Key:".getBytes(StandardCharsets.US_ASCII);
        if (starts(raw,prefix)) {
            raw=tea(Arrays.copyOfRange(raw,prefix.length,raw.length),"386ZJY!@#*$%^&)(".getBytes(StandardCharsets.US_ASCII));
            raw=tea(raw,"**#!(#$%&^a1cZ,T".getBytes(StandardCharsets.US_ASCII));
            raw=decode(new String(raw,StandardCharsets.US_ASCII));
        }
        if(raw.length<24) throw new IOException("eKey 长度无效");
        int[] sk={105,86,70,56,43,32,21,11};byte[] tk=new byte[16];
        for(int i=0;i<8;i++){tk[i*2]=(byte)sk[i];tk[i*2+1]=raw[i];}
        byte[] tail=tea(Arrays.copyOfRange(raw,8,raw.length),tk);
        byte[] key=Arrays.copyOf(raw,8+tail.length);System.arraycopy(tail,0,key,8,tail.length);
        Arrays.fill(tk,(byte)0);
        if(key.length<8 || key.length>4096) throw new IOException("eKey 派生长度无效");return key;
    }
    private static boolean starts(byte[] b,byte[] p){return b.length>=p.length && Arrays.equals(Arrays.copyOf(b,p.length),p);}
    private static byte[] decode(String s)throws IOException{try{return Base64.getDecoder().decode(s.trim());}catch(IllegalArgumentException e){throw new IOException("eKey Base64 格式无效");}}
    private static byte[] tea(byte[] input,byte[] key)throws IOException {
        if(input.length<16 || input.length%8!=0 || key.length!=16) throw new IOException("eKey TEA 长度无效");
        byte[] padded=new byte[input.length],prevA=new byte[8],prevC=new byte[8];
        for(int off=0;off<input.length;off+=8){
            byte[] block=Arrays.copyOfRange(input,off,off+8);
            for(int i=0;i<8;i++)block[i]^=prevA[i];
            byte[] a=teaBlock(block,key);
            for(int i=0;i<8;i++)padded[off+i]=(byte)(a[i]^prevC[i]);
            prevA=a;prevC=Arrays.copyOfRange(input,off,off+8);
        }
        int start=1+(padded[0]&7)+2,end=padded.length-7;
        if(end<start) throw new IOException("eKey TEA 填充无效");
        for(int i=end;i<padded.length;i++)if(padded[i]!=0)throw new IOException("eKey TEA 校验失败");
        return Arrays.copyOfRange(padded,start,end);
    }
    private static byte[] teaBlock(byte[] b,byte[] key){
        ByteBuffer bb=ByteBuffer.wrap(b);int v0=bb.getInt(),v1=bb.getInt();ByteBuffer kb=ByteBuffer.wrap(key);
        int[] k={kb.getInt(),kb.getInt(),kb.getInt(),kb.getInt()};int sum=0x9e3779b9*16;
        for(int i=0;i<16;i++){v1-=((v0<<4)+k[2])^(v0+sum)^((v0>>>5)+k[3]);v0-=((v1<<4)+k[0])^(v1+sum)^((v1>>>5)+k[1]);sum-=0x9e3779b9;}
        return ByteBuffer.allocate(8).putInt(v0).putInt(v1).array();
    }
    private static Mask cipher(byte[] key) {
        if(key.length>300)return new Rc4(key);
        return (b,n,offset)->{for(int i=0;i<n;i++){long at=offset+i;if(at>0x7fff)at%=0x7fff;int idx=(int)((at*at+71214)%key.length),v=key[idx]&255,r=((idx&7)+4)%8;b[i]^=(byte)((v<<r)|(v>>>r));}};
    }
    static void transform(byte[] key,byte[] data,long offset){cipher(key).apply(data,data.length,offset);}
    private static final class Rc4 implements Mask {
        private final byte[] key;private final int n;private final int[] box;private long hash=1;
        Rc4(byte[] key){this.key=key;n=key.length;box=new int[n];for(int i=0;i<n;i++)box[i]=i&255;int j=0;
            for(int i=0;i<n;i++){j=(j+box[i]+(key[i]&255))%n;int t=box[i];box[i]=box[j];box[j]=t;}
            for(byte b:key){int v=b&255;if(v==0)continue;long next=(hash*v)&0xffffffffL;if(next==0||next<=hash)break;hash=next;}}
        private int skip(long id){long denom=(id+1)*(key[(int)(id%n)]&255);return denom==0?0:(int)(((long)((double)hash/denom*100.0))%n);}
        public void apply(byte[] b,int length,long offset){int done=0;
            while(done<length){long at=offset+done;
                if(at<128){b[done++]^=key[skip(at)];continue;}
                int count=Math.min(length-done,5120-(int)(at%5120));int[] bx=box.clone();int j=0,k=0;
                long discard=at%5120+skip(at/5120);
                for(long i=-discard;i<count;i++){j=(j+1)%n;k=(bx[j]+k)%n;int t=bx[j];bx[j]=bx[k];bx[k]=t;if(i>=0)b[done+(int)i]^=(byte)bx[(bx[j]+bx[k])%n];}
                done+=count;
            }
        }
    }
    private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}

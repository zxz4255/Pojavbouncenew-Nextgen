package net.ccbluex.liquidbounce.utils.audio.parser;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class KggCipherRegression {
    private static int checks;
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static void ok(boolean b){if(!b)throw new AssertionError("Check "+checks);checks++;}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private interface Action {void run()throws Exception;}
    private static void fail(Action a)throws Exception{try{a.run();throw new AssertionError("Accepted invalid input");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        File root=new File(System.getProperty("music.test.root"));root.mkdirs();
        File project=new File(args[0]);File vectors=new File(project,"scripts/verify079/vectors");
        JsonArray rows=JsonParser.parseString(Files.readString(new File(vectors,"synthetic.json").toPath())).getAsJsonArray();
        for(JsonElement e:rows){JsonObject row=e.getAsJsonObject();String name=row.get("name").getAsString(),hash=row.get("hash").getAsString(),ekey=row.get("ekey").getAsString();
            byte[] key=KggDecryptor.deriveKey(ekey);ok(Arrays.equals(key,hex(row.get("key_hex").getAsString())));
            File in=new File(vectors,name),out=new File(root,name+".audio");
            byte[] before=Files.readAllBytes(in.toPath());
            KggDecryptor.Header h=KggDecryptor.readHeader(in);ok(h.version==5 && h.audioOffset==1024 && h.audioHash.equals(hash));
            ok(KggDecryptor.decrypt(in,out,x->x.equals(hash)?ekey:null,()->false)==MusicAudioParserFactory.Codec.FLAC);
            ok(sha(Files.readAllBytes(out.toPath())).equals(row.get("plain_sha256").getAsString()));ok(Arrays.equals(before,Files.readAllBytes(in.toPath())));
            fail(()->KggDecryptor.decrypt(in,out,x->ekey,()->false));ok(sha(Files.readAllBytes(out.toPath())).equals(row.get("plain_sha256").getAsString()));
            File missing=new File(root,name+".missing");fail(()->KggDecryptor.decrypt(in,missing,x->null,()->false));ok(!missing.exists());
            File wrong=new File(root,name+".wrong");fail(()->KggDecryptor.decrypt(in,wrong,x->"A".repeat(64),()->false));ok(!wrong.exists());
            fail(()->KggDecryptor.decrypt(in,wrong,x->"not-a-valid-key-!".repeat(3),()->false));
            fail(()->KggDecryptor.decrypt(in,wrong,x->ekey,()->true));ok(!wrong.exists());
            AtomicInteger calls=new AtomicInteger();fail(()->KggDecryptor.decrypt(in,wrong,x->ekey,()->calls.incrementAndGet()>2));ok(!wrong.exists());
            // Same stream at arbitrary 127/128/5120/32767 boundaries must match independent whole output.
            byte[] audio=Arrays.copyOfRange(before,1024,before.length),whole=audio.clone();KggDecryptor.transform(key,whole,0);
            for(int start=0;start<audio.length;start+=7777){byte[] part=Arrays.copyOfRange(audio,start,Math.min(audio.length,start+7777));KggDecryptor.transform(key,part,start);ok(Arrays.equals(part,Arrays.copyOfRange(whole,start,start+part.length)));}
            byte[] damaged=before.clone();for(int at:new int[]{0x10,0x14,0x44}){byte[] b=damaged.clone();Arrays.fill(b,at,at+4,(byte)255);File bad=new File(root,"bad.kgg");Files.write(bad.toPath(),b);fail(()->KggDecryptor.decrypt(bad,wrong,x->ekey,()->false));}
        }
        byte[] map=new byte[256];for(int i=0;i<256;i++)map[i]=(byte)i;
        byte[] golden=new byte[24];KggDecryptor.transform(map,golden,0);ok(Arrays.equals(golden,hex("bb7d80beff3881fbbbff823cffba8379bb7981baff3c80ff")));
        golden=new byte[8];KggDecryptor.transform(map,golden,0x7ffe);ok(Arrays.equals(golden,hex("807d7d80beff3881")));
        byte[] rc4=new byte[301];for(int i=0;i<301;i++)rc4[i]=(byte)(i*7+3);
        golden=new byte[64];KggDecryptor.transform(rc4,golden,0);ok(Arrays.equals(golden,hex("645e990d6387a2211c3a1f37bd6cb9b8e525ff5c73172c6ac6c3eb4af8cda47d62eb7a99948740930d11333a89b27d5e5ea36793c33dfa586c06444b5e49ce47")));
        File v3=new File(project,"references/kgg079/python-kgm_v3_sample.kgm"),clear=new File(root,"v3.audio");
        KggDecryptor.decrypt(v3,clear,x->{throw new AssertionError("v3 requested a key");},()->false);
        ok(Arrays.equals(Files.readAllBytes(clear.toPath()),Files.readAllBytes(new File(project,"references/kgg079/python-kgm_v3_sample.expected").toPath())));
        byte[] unsupported=Files.readAllBytes(v3.toPath());ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putInt(0x18,2);File bad=new File(root,"slot.kgm");Files.write(bad.toPath(),unsupported);fail(()->KggDecryptor.readHeader(bad));
        byte[] truncated=new byte[60];File tiny=new File(root,"tiny.kgg");Files.write(tiny.toPath(),truncated);fail(()->KggDecryptor.readHeader(tiny));
        fail(()->KggDecryptor.deriveKey("A".repeat(16385)));
        ok(Arrays.stream(root.listFiles()).noneMatch(f->f.getName().startsWith(".kgg-")));
        System.out.println("PASS: "+checks+" KGG cipher assertions: independent Python v1/v2 eKey derivation, Map/RC4 synthetic vectors, pinned v3 fixture byte equality, golden masks, streaming boundaries, malformed/missing keys, bounds, no-overwrite, cancellation and temp cleanup");
    }
}

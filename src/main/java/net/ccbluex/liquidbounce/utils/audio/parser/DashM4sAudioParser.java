package net.ccbluex.liquidbounce.utils.audio.parser;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Demultiplexes and decodes the AAC track in a fragmented MP4/DASH .m4s file.
 *
 * <p>Bilibili's DASH audio URL is an audio-only fragmented MP4: it contains an AAC initialization
 * section followed by moof/mdat fragments. This parser reads the AAC configuration from the
 * initialization boxes, then reads only the selected track's trun sample ranges and passes those
 * AAC access units to JAAD. No video URL, video sample, Minecraft sound effect, or global mixer
 * input is used.</p>
 */
public final class DashM4sAudioParser implements PcmAudioParser {
    private static final int MAX_PCM_FRAME_BYTES = 128 * 1024;
    private static final int MAX_SAMPLE_BYTES = 1024 * 1024;
    private static final long MAX_SAMPLES = 500_000L;

    private final RandomAccessFile input;
    private final BooleanSupplier cancelled;
    private final Decoder decoder;
    private final SampleBuffer sampleBuffer = new SampleBuffer();
    private final List<Sample> samples;
    private final int sampleRate;
    private final int channels;
    private final long mediaTimeScale;
    private final long totalSamples;

    private int nextSample;
    private long positionSamples;
    private boolean closed;

    public DashM4sAudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("DASH .m4s 音频文件不存在");
        if (file.length() > MusicAudioParserFactory.MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }

        this.cancelled = cancelled == null ? () -> false : cancelled;
        RandomAccessFile opened = new RandomAccessFile(file, "r");
        Decoder configured;
        int rate;
        int channelCount;
        long timeScale;
        long decodedSamples;
        List<Sample> parsedSamples;
        int firstSample;
        long initialPosition;
        try {
            checkCancelled();
            List<BoxHeader> topLevel = readBoxList(opened, 0L, opened.length());
            if (!containsType(topLevel, "moof")) throw new IOException("MP4 文件不是 DASH 分片格式");
            BoxHeader moov = findType(topLevel, "moov");
            if (moov == null) throw new IOException("DASH .m4s 文件缺少 AAC 初始化信息");

            TrackInfo trackInfo = findAudioTrackInfo(opened, moov);
            if (trackInfo == null || trackInfo.trackId <= 0L || trackInfo.timeScale <= 0L) {
                throw new IOException("DASH .m4s 缺少有效的 AAC track 信息");
            }
            rate = trackInfo.sampleRate;
            channelCount = trackInfo.channels;
            if (rate < 8_000 || rate > 192_000 || channelCount < 1 || channelCount > 2) {
                throw new IOException("不支持的 AAC 音频参数：" + rate + " Hz / " + channelCount + " 声道");
            }
            if (trackInfo.decoderInfo == null || trackInfo.decoderInfo.length == 0) {
                throw new IOException("DASH AAC 音轨缺少解码配置");
            }
            configured = new Decoder(trackInfo.decoderInfo);
            timeScale = trackInfo.timeScale;

            Defaults defaults = findFragmentDefaults(opened, moov, trackInfo.trackId);
            parsedSamples = readSamples(opened, topLevel, trackInfo.trackId, defaults, this.cancelled);
            if (parsedSamples.isEmpty()) throw new IOException("DASH .m4s 中没有 AAC 音频帧");

            long endTicks = 0L;
            for (Sample sample : parsedSamples) {
                endTicks = Math.max(endTicks, safeAdd(sample.timeTicks, sample.durationTicks));
            }
            decodedSamples = ticksToSamples(endTicks, rate, timeScale);

            long requestedTicks = millisecondsToTicks(Math.max(0L, startAtMs), timeScale);
            firstSample = 0;
            while (firstSample < parsedSamples.size()) {
                Sample sample = parsedSamples.get(firstSample);
                if (safeAdd(sample.timeTicks, sample.durationTicks) > requestedTicks) break;
                firstSample++;
            }
            if (firstSample >= parsedSamples.size()) {
                if (startAtMs > 0L) throw new IOException("seek beyond available DASH audio");
                firstSample = 0;
            }
            initialPosition = ticksToSamples(parsedSamples.get(firstSample).timeTicks, rate, timeScale);
        } catch (IOException e) {
            try { opened.close(); } catch (IOException ignored) { }
            throw e;
        } catch (Exception e) {
            try { opened.close(); } catch (IOException ignored) { }
            throw new IOException("DASH AAC 解码器初始化失败", e);
        }

        this.input = opened;
        this.decoder = configured;
        this.sampleRate = rate;
        this.channels = channelCount;
        this.mediaTimeScale = timeScale;
        this.totalSamples = decodedSamples;
        this.samples = parsedSamples;
        this.nextSample = firstSample;
        this.positionSamples = initialPosition;
    }

    /** Returns true when an ISO-BMFF file contains top-level DASH movie fragments. */
    public static boolean isFragmentedMp4(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 8L) return false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            return containsType(readBoxList(input, 0L, input.length()), "moof");
        }
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getChannels() {
        return channels;
    }

    @Override
    public long getTotalSamples() {
        return totalSamples;
    }

    @Override
    public long getPositionSamples() {
        return positionSamples;
    }

    @Override
    public String getCodec() {
        return "AAC/DASH-M4S";
    }

    @Override
    public short[] readFrame() throws IOException {
        ensureOpen();
        checkCancelled();
        if (nextSample >= samples.size()) return null;

        Sample sample = samples.get(nextSample++);
        byte[] accessUnit = new byte[(int) sample.size];
        input.seek(sample.offset);
        input.readFully(accessUnit);
        try {
            decoder.decodeFrame(accessUnit, sampleBuffer);
        } catch (Exception e) {
            throw new IOException("DASH AAC 音频帧解码失败", e);
        }
        checkCancelled();

        byte[] pcm = sampleBuffer.getData();
        int outputRate = sampleBuffer.getSampleRate();
        int outputChannels = sampleBuffer.getChannels();
        int bitsPerSample = sampleBuffer.getBitsPerSample();
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > MAX_PCM_FRAME_BYTES ||
                outputRate != sampleRate || outputChannels != channels || bitsPerSample != 16) {
            throw new IOException("DASH AAC 解码器输出了不支持的 PCM 格式");
        }

        short[] result = new short[pcm.length / 2];
        if (sampleBuffer.isBigEndian()) {
            for (int i = 0, j = 0; i < pcm.length; i += 2, j++) {
                result[j] = (short) (((pcm[i] & 0xff) << 8) | (pcm[i + 1] & 0xff));
            }
        } else {
            for (int i = 0, j = 0; i < pcm.length; i += 2, j++) {
                result[j] = (short) ((pcm[i] & 0xff) | ((pcm[i + 1] & 0xff) << 8));
            }
        }

        long sampleStart = ticksToSamples(sample.timeTicks, sampleRate, mediaTimeScale);
        positionSamples = Math.max(positionSamples, sampleStart) + result.length / channels;
        return result;
    }

    private static TrackInfo findAudioTrackInfo(RandomAccessFile input, BoxHeader moov) throws IOException {
        for (BoxHeader trak : readBoxList(input, moov.payloadOffset(), moov.endOffset())) {
            if (!"trak".equals(trak.type)) continue;
            BoxHeader mdia = findChild(input, trak, "mdia");
            if (mdia == null) continue;
            BoxHeader hdlr = findChild(input, mdia, "hdlr");
            if (hdlr == null || !"soun".equals(readHandlerType(input, hdlr))) continue;

            BoxHeader tkhd = findChild(input, trak, "tkhd");
            BoxHeader mdhd = findChild(input, mdia, "mdhd");
            BoxHeader minf = findChild(input, mdia, "minf");
            BoxHeader stbl = minf == null ? null : findChild(input, minf, "stbl");
            BoxHeader stsd = stbl == null ? null : findChild(input, stbl, "stsd");
            if (tkhd == null || mdhd == null || stsd == null) continue;

            long trackId = readTrackId(input, tkhd);
            long timeScale = readMediaTimeScale(input, mdhd);
            for (BoxHeader entry : readSampleDescriptions(input, stsd)) {
                if (!"mp4a".equals(entry.type)) continue;
                AudioDescription audio = readAudioDescription(input, entry);
                if (audio == null || audio.decoderInfo == null || audio.decoderInfo.length == 0) continue;
                return new TrackInfo(trackId, timeScale, audio.sampleRate, audio.channels, audio.decoderInfo);
            }
        }
        return null;
    }

    private static String readHandlerType(RandomAccessFile input, BoxHeader hdlr) throws IOException {
        input.seek(hdlr.payloadOffset());
        input.readUnsignedByte(); // version
        readFlags(input);
        input.skipBytes(4); // pre_defined
        byte[] bytes = new byte[4];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static long readTrackId(RandomAccessFile input, BoxHeader tkhd) throws IOException {
        input.seek(tkhd.payloadOffset());
        int version = input.readUnsignedByte();
        readFlags(input);
        if (version == 1) input.skipBytes(16); // creation_time + modification_time
        else if (version == 0) input.skipBytes(8);
        else throw new IOException("DASH tkhd version 不受支持");
        return readUnsignedInt(input);
    }

    private static long readMediaTimeScale(RandomAccessFile input, BoxHeader mdhd) throws IOException {
        input.seek(mdhd.payloadOffset());
        int version = input.readUnsignedByte();
        readFlags(input);
        if (version == 1) input.skipBytes(16); // creation_time + modification_time
        else if (version == 0) input.skipBytes(8);
        else throw new IOException("DASH mdhd version 不受支持");
        return readUnsignedInt(input);
    }

    private static List<BoxHeader> readSampleDescriptions(RandomAccessFile input, BoxHeader stsd) throws IOException {
        input.seek(stsd.payloadOffset());
        input.skipBytes(4); // version and flags
        long count = readUnsignedInt(input);
        if (count > 32L) throw new IOException("DASH stsd sample description 数量异常");
        List<BoxHeader> entries = readBoxList(input, input.getFilePointer(), stsd.endOffset());
        if (entries.size() != count) throw new IOException("DASH stsd sample description 数量不匹配");
        return entries;
    }

    private static AudioDescription readAudioDescription(RandomAccessFile input, BoxHeader entry) throws IOException {
        if (entry.size < entry.headerSize + 28L) throw new IOException("DASH mp4a sample entry 不完整");
        input.seek(entry.payloadOffset() + 8L);
        int version = input.readUnsignedShort();
        input.skipBytes(2); // revision level
        input.skipBytes(4); // vendor
        int channelCount = input.readUnsignedShort();
        input.skipBytes(2); // sample size
        input.skipBytes(4); // compression ID + packet size
        long fixedSampleRate = readUnsignedInt(input); // 16.16 fixed-point
        int sampleRate = (int) (fixedSampleRate >>> 16);
        if (version != 0) throw new IOException("DASH mp4a sample entry version 不受支持");

        long childrenStart = entry.payloadOffset() + 28L;
        byte[] decoderInfo = null;
        for (BoxHeader child : readBoxList(input, childrenStart, entry.endOffset())) {
            if (!"esds".equals(child.type)) continue;
            long descriptorStart = child.payloadOffset() + 4L; // FullBox version and flags
            if (descriptorStart > child.endOffset()) throw new IOException("DASH esds box 不完整");
            byte[] descriptors = readBytes(input, descriptorStart, child.endOffset());
            decoderInfo = findDecoderSpecificInfo(descriptors, 0, descriptors.length, 0);
            if (decoderInfo != null) break;
        }
        return new AudioDescription(sampleRate, channelCount, decoderInfo);
    }

    private static byte[] findDecoderSpecificInfo(byte[] data, int start, int end, int depth) throws IOException {
        if (depth > 8) throw new IOException("DASH AAC descriptor 嵌套过深");
        int position = start;
        while (position < end) {
            if (end - position < 2) throw new IOException("DASH AAC descriptor 不完整");
            int tag = data[position++] & 0xff;
            int length = 0;
            boolean lengthComplete = false;
            for (int i = 0; i < 4; i++) {
                if (position >= end) throw new IOException("DASH AAC descriptor 长度不完整");
                int value = data[position++] & 0xff;
                length = (length << 7) | (value & 0x7f);
                if ((value & 0x80) == 0) {
                    lengthComplete = true;
                    break;
                }
            }
            if (!lengthComplete || length < 0 || length > end - position) {
                throw new IOException("DASH AAC descriptor 长度无效");
            }
            int descriptorEnd = position + length;
            if (tag == 0x05) {
                if (length == 0) throw new IOException("AAC DecoderSpecificInfo 为空");
                byte[] result = new byte[length];
                System.arraycopy(data, position, result, 0, length);
                return result;
            }

            int nestedStart = position;
            if (tag == 0x03) { // ES_Descriptor header before its child descriptors
                if (length < 3) throw new IOException("ES_Descriptor 头不完整");
                nestedStart += 2; // ES_ID
                int flags = data[nestedStart++] & 0xff;
                if ((flags & 0x80) != 0) nestedStart += 2;
                if ((flags & 0x40) != 0) {
                    if (nestedStart >= descriptorEnd) throw new IOException("ES_Descriptor URL 不完整");
                    int urlLength = data[nestedStart++] & 0xff;
                    nestedStart += urlLength;
                }
                if ((flags & 0x20) != 0) nestedStart += 2;
            } else if (tag == 0x04) { // DecoderConfigDescriptor fixed fields
                nestedStart += 13;
            }
            if ((tag == 0x03 || tag == 0x04) && nestedStart <= descriptorEnd) {
                byte[] result = findDecoderSpecificInfo(data, nestedStart, descriptorEnd, depth + 1);
                if (result != null) return result;
            }
            position = descriptorEnd;
        }
        return null;
    }

    private static Defaults findFragmentDefaults(RandomAccessFile input, BoxHeader moov, long trackId) throws IOException {
        BoxHeader mvex = findChild(input, moov, "mvex");
        if (mvex == null) return new Defaults(0L, 0L);
        for (BoxHeader trex : readBoxList(input, mvex.payloadOffset(), mvex.endOffset())) {
            if (!"trex".equals(trex.type)) continue;
            input.seek(trex.payloadOffset());
            input.skipBytes(4); // version and flags
            long id = readUnsignedInt(input);
            input.skipBytes(4); // default sample description index
            long duration = readUnsignedInt(input);
            long size = readUnsignedInt(input);
            if (id == trackId) return new Defaults(duration, size);
        }
        return new Defaults(0L, 0L);
    }

    private static BoxHeader findChild(RandomAccessFile input, BoxHeader parent, String type) throws IOException {
        for (BoxHeader child : readBoxList(input, parent.payloadOffset(), parent.endOffset())) {
            if (type.equals(child.type)) return child;
        }
        return null;
    }

    private static List<Sample> readSamples(
            RandomAccessFile input,
            List<BoxHeader> topLevel,
            long audioTrackId,
            Defaults trexDefaults,
            BooleanSupplier cancelled) throws IOException {
        List<Sample> result = new ArrayList<>();
        long nextDecodeTime = 0L;
        for (int i = 0; i < topLevel.size(); i++) {
            checkCancelled(cancelled);
            BoxHeader moof = topLevel.get(i);
            if (!"moof".equals(moof.type)) continue;

            BoxHeader mdat = null;
            for (int j = i + 1; j < topLevel.size(); j++) {
                String type = topLevel.get(j).type;
                if ("moof".equals(type)) break;
                if ("mdat".equals(type)) {
                    mdat = topLevel.get(j);
                    break;
                }
            }
            if (mdat == null) throw new IOException("DASH moof 后缺少 mdat 音频数据");

            for (BoxHeader child : readBoxList(input, moof.payloadOffset(), moof.endOffset())) {
                if (!"traf".equals(child.type)) continue;
                nextDecodeTime = readTrackFragment(
                    input, child, moof, mdat, audioTrackId, trexDefaults, nextDecodeTime, result, cancelled);
            }
        }
        return result;
    }

    private static long readTrackFragment(
            RandomAccessFile input,
            BoxHeader traf,
            BoxHeader moof,
            BoxHeader mdat,
            long audioTrackId,
            Defaults trexDefaults,
            long previousDecodeTime,
            List<Sample> samples,
            BooleanSupplier cancelled) throws IOException {
        List<BoxHeader> children = readBoxList(input, traf.payloadOffset(), traf.endOffset());
        BoxHeader tfhdBox = null;
        BoxHeader tfdtBox = null;
        List<BoxHeader> trunBoxes = new ArrayList<>();
        for (BoxHeader child : children) {
            if ("tfhd".equals(child.type)) tfhdBox = child;
            else if ("tfdt".equals(child.type)) tfdtBox = child;
            else if ("trun".equals(child.type)) trunBoxes.add(child);
        }
        if (tfhdBox == null) throw new IOException("DASH traf 缺少 tfhd");

        Tfhd tfhd = readTfhd(input, tfhdBox, moof.offset);
        if (tfhd.trackId != audioTrackId) return previousDecodeTime;
        long defaultDuration = tfhd.defaultDuration > 0L ? tfhd.defaultDuration : trexDefaults.duration;
        long defaultSize = tfhd.defaultSize > 0L ? tfhd.defaultSize : trexDefaults.size;
        long decodeTime = tfdtBox == null ? previousDecodeTime : readTfdt(input, tfdtBox);
        long nextRunOffset = -1L;

        for (BoxHeader trun : trunBoxes) {
            checkCancelled(cancelled);
            RunHeader run = readRunHeader(input, trun);
            long dataOffset;
            if (run.hasDataOffset) {
                dataOffset = safeAdd(tfhd.baseDataOffset, run.dataOffset);
            } else if (nextRunOffset >= 0L) {
                dataOffset = nextRunOffset;
            } else if (tfhd.baseDataOffset >= mdat.payloadOffset() && tfhd.baseDataOffset < mdat.endOffset()) {
                dataOffset = tfhd.baseDataOffset;
            } else {
                dataOffset = mdat.payloadOffset();
            }
            if (dataOffset < mdat.payloadOffset() || dataOffset > mdat.endOffset()) {
                throw new IOException("DASH AAC sample 偏移超出 mdat 范围");
            }

            input.seek(run.entriesOffset);
            for (long index = 0; index < run.sampleCount; index++) {
                if ((index & 0x3ffL) == 0L) checkCancelled(cancelled);
                long duration = (run.flags & 0x000100) != 0 ? readUnsignedInt(input) : defaultDuration;
                long size = (run.flags & 0x000200) != 0 ? readUnsignedInt(input) : defaultSize;
                if ((run.flags & 0x000400) != 0) input.skipBytes(4);
                if ((run.flags & 0x000800) != 0) input.skipBytes(4);
                if (size <= 0L || size > MAX_SAMPLE_BYTES) {
                    throw new IOException("DASH AAC sample 大小无效");
                }
                if (duration <= 0L) throw new IOException("DASH AAC sample 时长无效");
                if (dataOffset < mdat.payloadOffset() || size > mdat.endOffset() - dataOffset) {
                    throw new IOException("DASH AAC sample 数据超出 mdat 范围");
                }
                if (samples.size() >= MAX_SAMPLES) throw new IOException("DASH AAC sample 数量超过安全限制");
                samples.add(new Sample(dataOffset, size, decodeTime, duration));
                dataOffset = safeAdd(dataOffset, size);
                decodeTime = safeAdd(decodeTime, duration);
            }
            nextRunOffset = dataOffset;
        }
        return decodeTime;
    }

    private static Tfhd readTfhd(RandomAccessFile input, BoxHeader box, long moofOffset) throws IOException {
        input.seek(box.payloadOffset());
        input.readUnsignedByte(); // version
        int flags = readFlags(input);
        long trackId = readUnsignedInt(input);
        long baseDataOffset = -1L;
        if ((flags & 0x000001) != 0) baseDataOffset = readUnsignedLong(input);
        if ((flags & 0x000002) != 0) input.skipBytes(4); // sample description index
        long defaultDuration = 0L;
        long defaultSize = 0L;
        if ((flags & 0x000008) != 0) defaultDuration = readUnsignedInt(input);
        if ((flags & 0x000010) != 0) defaultSize = readUnsignedInt(input);
        if ((flags & 0x000020) != 0) input.skipBytes(4); // default sample flags
        if (baseDataOffset < 0L) baseDataOffset = moofOffset; // default-base-is-moof (or first traf)
        return new Tfhd(trackId, baseDataOffset, defaultDuration, defaultSize);
    }

    private static long readTfdt(RandomAccessFile input, BoxHeader box) throws IOException {
        input.seek(box.payloadOffset());
        int version = input.readUnsignedByte();
        readFlags(input);
        if (version == 1) return readUnsignedLong(input);
        if (version == 0) return readUnsignedInt(input);
        throw new IOException("DASH tfdt version 不受支持");
    }

    private static RunHeader readRunHeader(RandomAccessFile input, BoxHeader box) throws IOException {
        input.seek(box.payloadOffset());
        input.readUnsignedByte(); // version
        int flags = readFlags(input);
        long count = readUnsignedInt(input);
        if (count > MAX_SAMPLES) throw new IOException("DASH AAC fragment 样本数超过安全限制");
        boolean hasDataOffset = (flags & 0x000001) != 0;
        int dataOffset = hasDataOffset ? input.readInt() : 0; // signed, relative to tfhd base_data_offset
        if ((flags & 0x000004) != 0) input.skipBytes(4); // first sample flags
        long entriesOffset = input.getFilePointer();

        long bytesPerEntry = 0L;
        if ((flags & 0x000100) != 0) bytesPerEntry += 4L;
        if ((flags & 0x000200) != 0) bytesPerEntry += 4L;
        if ((flags & 0x000400) != 0) bytesPerEntry += 4L;
        if ((flags & 0x000800) != 0) bytesPerEntry += 4L;
        long required = safeMultiply(count, bytesPerEntry);
        if (entriesOffset > box.endOffset() || required > box.endOffset() - entriesOffset) {
            throw new IOException("DASH trun 样本表无效");
        }
        return new RunHeader(count, flags, hasDataOffset, dataOffset, entriesOffset);
    }

    private static List<BoxHeader> readBoxList(RandomAccessFile input, long start, long end) throws IOException {
        if (start < 0L || end < start || end > input.length()) throw new IOException("MP4 box 范围无效");
        List<BoxHeader> boxes = new ArrayList<>();
        long offset = start;
        while (offset < end) {
            if (end - offset < 8L) throw new IOException("MP4 box 头不完整");
            input.seek(offset);
            long size = readUnsignedInt(input);
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (size == 1L) {
                size = readUnsignedLong(input);
                headerSize = 16;
            } else if (size == 0L) {
                size = end - offset;
            }
            if (size < headerSize || size > end - offset) throw new IOException("MP4 box 大小无效：" + type);
            BoxHeader box = new BoxHeader(offset, size, headerSize, type);
            boxes.add(box);
            offset = box.endOffset();
        }
        if (offset != end) throw new IOException("MP4 box 边界无效");
        return boxes;
    }

    private static byte[] readBytes(RandomAccessFile input, long start, long end) throws IOException {
        long length = end - start;
        if (length < 0L || length > Integer.MAX_VALUE) throw new IOException("MP4 descriptor 长度无效");
        byte[] result = new byte[(int) length];
        input.seek(start);
        input.readFully(result);
        return result;
    }

    private static BoxHeader findType(List<BoxHeader> boxes, String type) {
        for (BoxHeader box : boxes) if (type.equals(box.type)) return box;
        return null;
    }

    private static boolean containsType(List<BoxHeader> boxes, String type) {
        return findType(boxes, type) != null;
    }

    private static int readFlags(RandomAccessFile input) throws IOException {
        return (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
    }

    private static long readUnsignedInt(RandomAccessFile input) throws IOException {
        return input.readInt() & 0xffffffffL;
    }

    private static long readUnsignedLong(RandomAccessFile input) throws IOException {
        long high = readUnsignedInt(input);
        long low = readUnsignedInt(input);
        if ((high & 0x80000000L) != 0L) throw new IOException("MP4 64-bit 偏移超出安全范围");
        return (high << 32) | low;
    }

    private static long safeAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IOException("MP4 时间或偏移溢出", e);
        }
    }

    private static long safeMultiply(long left, long right) throws IOException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException e) {
            throw new IOException("MP4 sample 表溢出", e);
        }
    }

    private static long millisecondsToTicks(long milliseconds, long timeScale) throws IOException {
        long wholeSeconds = milliseconds / 1_000L;
        long remainderMs = milliseconds % 1_000L;
        return safeAdd(safeMultiply(wholeSeconds, timeScale), safeMultiply(remainderMs, timeScale) / 1_000L);
    }

    private static long ticksToSamples(long ticks, int sampleRate, long timeScale) {
        if (ticks <= 0L || timeScale <= 0L) return 0L;
        long wholeSeconds = ticks / timeScale;
        long remainder = ticks % timeScale;
        return wholeSeconds * sampleRate + remainder * sampleRate / timeScale;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("DASH AAC parser is closed");
    }

    private void checkCancelled() throws InterruptedIOException {
        checkCancelled(cancelled);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("DASH AAC decoding cancelled");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        input.close();
    }

    private static final class TrackInfo {
        final long trackId;
        final long timeScale;
        final int sampleRate;
        final int channels;
        final byte[] decoderInfo;
        TrackInfo(long trackId, long timeScale, int sampleRate, int channels, byte[] decoderInfo) {
            this.trackId = trackId;
            this.timeScale = timeScale;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.decoderInfo = decoderInfo;
        }
    }

    private static final class AudioDescription {
        final int sampleRate;
        final int channels;
        final byte[] decoderInfo;
        AudioDescription(int sampleRate, int channels, byte[] decoderInfo) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.decoderInfo = decoderInfo;
        }
    }

    private static final class Defaults {
        final long duration;
        final long size;
        Defaults(long duration, long size) { this.duration = duration; this.size = size; }
    }

    private static final class Tfhd {
        final long trackId;
        final long baseDataOffset;
        final long defaultDuration;
        final long defaultSize;
        Tfhd(long trackId, long baseDataOffset, long defaultDuration, long defaultSize) {
            this.trackId = trackId;
            this.baseDataOffset = baseDataOffset;
            this.defaultDuration = defaultDuration;
            this.defaultSize = defaultSize;
        }
    }

    private static final class RunHeader {
        final long sampleCount;
        final int flags;
        final boolean hasDataOffset;
        final int dataOffset;
        final long entriesOffset;
        RunHeader(long sampleCount, int flags, boolean hasDataOffset, int dataOffset, long entriesOffset) {
            this.sampleCount = sampleCount;
            this.flags = flags;
            this.hasDataOffset = hasDataOffset;
            this.dataOffset = dataOffset;
            this.entriesOffset = entriesOffset;
        }
    }

    private static final class Sample {
        final long offset;
        final long size;
        final long timeTicks;
        final long durationTicks;
        Sample(long offset, long size, long timeTicks, long durationTicks) {
            this.offset = offset;
            this.size = size;
            this.timeTicks = timeTicks;
            this.durationTicks = durationTicks;
        }
    }

    private static final class BoxHeader {
        final long offset;
        final long size;
        final int headerSize;
        final String type;
        BoxHeader(long offset, long size, int headerSize, String type) {
            this.offset = offset;
            this.size = size;
            this.headerSize = headerSize;
            this.type = type;
        }
        long payloadOffset() { return offset + headerSize; }
        long endOffset() { return offset + size; }
    }
}

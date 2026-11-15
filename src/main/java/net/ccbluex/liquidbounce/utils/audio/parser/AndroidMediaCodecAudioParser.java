package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Android-only PCM decoder backed by MediaExtractor/MediaCodec. Android routes MediaCodec through
 * its system native codec libraries; no unrelated application .so is loaded here. Reflection keeps
 * Android framework classes out of the desktop compile classpath. The parser only reads the selected
 * music file and returns PCM; it never reads from or mutates Minecraft's shared sound mixer.
 */
public final class AndroidMediaCodecAudioParser implements PcmAudioParser {
    private static final int INFO_TRY_AGAIN_LATER = -1;
    private static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    private static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;
    private static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    private static final int BUFFER_FLAG_END_OF_STREAM = 4;
    private static final int SEEK_TO_CLOSEST_SYNC = 2;
    private static final int PCM_8BIT = 3;
    private static final int PCM_16BIT = 2;
    private static final int PCM_FLOAT = 4;
    private static final int PCM_24BIT_PACKED = 21;
    private static final int PCM_32BIT = 22;
    private static final int MAX_PCM_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final long READ_TIMEOUT_NS = 10_000_000_000L;

    private final BooleanSupplier cancelled;
    private Object extractor;
    private Object codec;
    private Object bufferInfo;
    private Method extractorReadSampleData;
    private Method extractorAdvance;
    private Method extractorGetSampleTime;
    private Method codecDequeueInputBuffer;
    private Method codecGetInputBuffer;
    private Method codecQueueInputBuffer;
    private Method codecDequeueOutputBuffer;
    private Method codecGetOutputBuffer;
    private Method codecReleaseOutputBuffer;
    private Method codecGetOutputFormat;
    private Method formatGetInteger;
    private Method formatGetLong;
    private Field infoOffset;
    private Field infoSize;
    private Field infoPresentationTimeUs;
    private Field infoFlags;

    private String mime = "audio/unknown";
    private int sampleRate;
    private int channels;
    private int pcmEncoding = PCM_16BIT;
    private long durationSamples;
    private long positionSamples;
    private long targetStartUs;
    private long targetStartSamples;
    private boolean inputEnded;
    private boolean outputEnded;
    private boolean codecStarted;
    private boolean closed;
    private short[] primedFrame;
    private long primedEndPosition;

    public static boolean isAndroidRuntime() {
        String root = System.getenv("ANDROID_ROOT");
        String version = System.getProperty("os.version", "");
        return root != null && !root.isBlank() || version.toLowerCase().contains("android");
    }

    public AndroidMediaCodecAudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        this.cancelled = cancelled == null ? () -> false : cancelled;
        if (!isAndroidRuntime()) {
            throw new IOException("Android MediaCodec is unavailable on this runtime");
        }
        if (file == null || !file.isFile()) {
            throw new IOException("音频文件不存在");
        }
        checkCancelled();

        Object openedExtractor = null;
        Object openedCodec = null;
        try {
            Class<?> extractorClass = loadAndroidClass("android.media.MediaExtractor");
            Class<?> mediaFormatClass = loadAndroidClass("android.media.MediaFormat");
            Class<?> mediaCodecClass = loadAndroidClass("android.media.MediaCodec");
            Class<?> bufferInfoClass = loadAndroidClass("android.media.MediaCodec$BufferInfo");

            openedExtractor = extractorClass.getConstructor().newInstance();
            call(openedExtractor, method(extractorClass, "setDataSource", String.class), file.getAbsolutePath());

            Method getTrackCount = method(extractorClass, "getTrackCount");
            Method getTrackFormat = method(extractorClass, "getTrackFormat", int.class);
            Method formatGetString = method(mediaFormatClass, "getString", String.class);
            formatGetInteger = method(mediaFormatClass, "getInteger", String.class);
            formatGetLong = method(mediaFormatClass, "getLong", String.class);

            int trackCount = number(call(openedExtractor, getTrackCount)).intValue();
            Object audioFormat = null;
            for (int index = 0; index < trackCount; index++) {
                checkCancelled();
                Object candidate = call(openedExtractor, getTrackFormat, index);
                Object candidateMime = tryCall(candidate, formatGetString, "mime");
                if (candidateMime instanceof String && ((String) candidateMime).startsWith("audio/")) {
                    audioFormat = candidate;
                    mime = (String) candidateMime;
                    call(openedExtractor, method(extractorClass, "selectTrack", int.class), index);
                    break;
                }
            }
            if (audioFormat == null) {
                throw new IOException("Android MediaExtractor 未找到可解码的音频轨道");
            }

            sampleRate = formatInt(audioFormat, "sample-rate", 0);
            channels = formatInt(audioFormat, "channel-count", 0);
            long durationUs = formatLong(audioFormat, "durationUs", 0L);
            if (sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 2) {
                throw new IOException("Android 音轨 PCM 参数不支持：" + sampleRate + " Hz / " + channels + " 声道");
            }
            durationSamples = durationUs > 0L ? samplesForUs(durationUs, sampleRate) : 0L;

            long safeStartMs = Math.max(0L, startAtMs);
            targetStartUs = safeStartMs > Long.MAX_VALUE / 1_000L ? Long.MAX_VALUE : safeStartMs * 1_000L;
            targetStartSamples = samplesForUs(targetStartUs, sampleRate);
            if (targetStartUs > 0L) {
                call(openedExtractor, method(extractorClass, "seekTo", long.class, int.class),
                    targetStartUs, SEEK_TO_CLOSEST_SYNC);
            }
            long extractorTimeUs = number(call(openedExtractor,
                method(extractorClass, "getSampleTime"))).longValue();
            positionSamples = extractorTimeUs >= 0L
                ? Math.max(targetStartSamples, samplesForUs(extractorTimeUs, sampleRate))
                : targetStartSamples;

            Method createDecoder = method(mediaCodecClass, "createDecoderByType", String.class);
            openedCodec = call(null, createDecoder, mime);
            Method configure = findConfigureMethod(mediaCodecClass, mediaFormatClass);
            call(openedCodec, configure, audioFormat, null, null, 0);

            extractorReadSampleData = method(extractorClass, "readSampleData", ByteBuffer.class, int.class);
            extractorAdvance = method(extractorClass, "advance");
            extractorGetSampleTime = method(extractorClass, "getSampleTime");
            codecDequeueInputBuffer = method(mediaCodecClass, "dequeueInputBuffer", long.class);
            codecGetInputBuffer = method(mediaCodecClass, "getInputBuffer", int.class);
            codecQueueInputBuffer = method(mediaCodecClass, "queueInputBuffer",
                int.class, int.class, int.class, long.class, int.class);
            codecDequeueOutputBuffer = method(mediaCodecClass, "dequeueOutputBuffer",
                bufferInfoClass, long.class);
            codecGetOutputBuffer = method(mediaCodecClass, "getOutputBuffer", int.class);
            codecReleaseOutputBuffer = method(mediaCodecClass, "releaseOutputBuffer", int.class, boolean.class);
            codecGetOutputFormat = method(mediaCodecClass, "getOutputFormat");

            bufferInfo = bufferInfoClass.getConstructor().newInstance();
            infoOffset = bufferInfoClass.getField("offset");
            infoSize = bufferInfoClass.getField("size");
            infoPresentationTimeUs = bufferInfoClass.getField("presentationTimeUs");
            infoFlags = bufferInfoClass.getField("flags");

            extractor = openedExtractor;
            codec = openedCodec;
            call(codec, method(mediaCodecClass, "start"));
            codecStarted = true;
        } catch (IOException e) {
            releaseQuietly(openedCodec, openedExtractor, false);
            throw e;
        } catch (ReflectiveOperationException e) {
            releaseQuietly(openedCodec, openedExtractor, false);
            throw new IOException("Android MediaCodec API 不可用", e);
        } catch (RuntimeException e) {
            releaseQuietly(openedCodec, openedExtractor, false);
            throw new IOException("Android 原生音频解码器初始化失败", e);
        } catch (LinkageError e) {
            releaseQuietly(openedCodec, openedExtractor, false);
            throw new IOException("Android 原生音频 codec 链接失败", e);
        }
    }

    /** Prime one frame so the factory can fall back to the Java parser before playback starts. */
    void prime() throws IOException {
        if (primedFrame != null) return;
        long before = positionSamples;
        short[] decoded = readFrameInternal();
        if (decoded == null || decoded.length == 0) {
            throw new IOException("Android MediaCodec 没有输出 PCM 数据");
        }
        primedEndPosition = positionSamples;
        positionSamples = before;
        primedFrame = decoded;
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
        return durationSamples;
    }

    @Override
    public long getPositionSamples() {
        return positionSamples;
    }

    @Override
    public String getCodec() {
        return "Android MediaCodec / " + mime;
    }

    @Override
    public short[] readFrame() throws IOException {
        ensureOpen();
        if (primedFrame != null) {
            short[] result = primedFrame;
            primedFrame = null;
            positionSamples = primedEndPosition;
            return result;
        }
        return readFrameInternal();
    }

    private short[] readFrameInternal() throws IOException {
        ensureOpen();
        if (outputEnded) return null;
        long deadline = System.nanoTime() + READ_TIMEOUT_NS;

        while (!outputEnded) {
            checkCancelled();
            if (System.nanoTime() >= deadline) {
                throw new IOException("Android MediaCodec 等待 PCM 输出超时");
            }
            feedInputBuffer();
            int outputIndex = number(call(codec, codecDequeueOutputBuffer, bufferInfo, DEQUEUE_TIMEOUT_US)).intValue();
            if (outputIndex == INFO_TRY_AGAIN_LATER || outputIndex == INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            if (outputIndex == INFO_OUTPUT_FORMAT_CHANGED) {
                updateOutputFormat(call(codec, codecGetOutputFormat));
                continue;
            }
            if (outputIndex < 0) {
                throw new IOException("Android MediaCodec 返回未知输出状态：" + outputIndex);
            }

            short[] decoded = null;
            boolean endOfStream = false;
            try {
                int offset = infoOffset.getInt(bufferInfo);
                int size = infoSize.getInt(bufferInfo);
                long presentationTimeUs = infoPresentationTimeUs.getLong(bufferInfo);
                int flags = infoFlags.getInt(bufferInfo);
                endOfStream = (flags & BUFFER_FLAG_END_OF_STREAM) != 0;
                if (size > 0 && (flags & BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer output = (ByteBuffer) call(codec, codecGetOutputBuffer, outputIndex);
                    if (output == null || offset < 0 || size < 0 || size > MAX_PCM_BUFFER_BYTES ||
                            (long) offset + size > output.limit()) {
                        throw new IOException("Android MediaCodec 返回了无效 PCM 缓冲区");
                    }
                    ByteBuffer view = output.duplicate();
                    view.position(offset);
                    view.limit(offset + size);
                    byte[] pcmBytes = new byte[size];
                    view.get(pcmBytes);
                    decoded = toPcm16(pcmBytes, pcmEncoding);
                    decoded = trimBeforeRequestedPosition(decoded, presentationTimeUs);
                }
            } catch (IllegalAccessException e) {
                throw new IOException("无法读取 Android MediaCodec 缓冲区元数据", e);
            } finally {
                call(codec, codecReleaseOutputBuffer, outputIndex, false);
            }

            if (endOfStream) outputEnded = true;
            if (decoded != null && decoded.length > 0) return decoded;
            if (outputEnded) return null;
        }
        return null;
    }

    private void feedInputBuffer() throws IOException {
        if (inputEnded) return;
        int inputIndex = number(call(codec, codecDequeueInputBuffer, DEQUEUE_TIMEOUT_US)).intValue();
        if (inputIndex < 0) return;

        ByteBuffer input = (ByteBuffer) call(codec, codecGetInputBuffer, inputIndex);
        if (input == null) throw new IOException("Android MediaCodec 没有可用输入缓冲区");
        input.clear();
        int size = number(call(extractor, extractorReadSampleData, input, 0)).intValue();
        if (size < 0) {
            call(codec, codecQueueInputBuffer, inputIndex, 0, 0, 0L, BUFFER_FLAG_END_OF_STREAM);
            inputEnded = true;
            return;
        }

        long presentationTimeUs = number(call(extractor, extractorGetSampleTime)).longValue();
        if (presentationTimeUs < 0L) presentationTimeUs = 0L;
        call(codec, codecQueueInputBuffer, inputIndex, 0, size, presentationTimeUs, 0);
        call(extractor, extractorAdvance);
    }

    private short[] trimBeforeRequestedPosition(short[] samples, long presentationTimeUs) throws IOException {
        if (samples.length % channels != 0) {
            throw new IOException("Android MediaCodec PCM 样本未按声道对齐");
        }
        int frames = samples.length / channels;
        if (frames == 0) return samples;

        long frameStartSamples = presentationTimeUs >= 0L
            ? samplesForUs(presentationTimeUs, sampleRate)
            : positionSamples;
        long samplesToSkip = presentationTimeUs >= 0L && presentationTimeUs < targetStartUs
            ? samplesForUs(targetStartUs - presentationTimeUs, sampleRate)
            : 0L;
        int skipFrames = (int) Math.min(frames, Math.max(0L, samplesToSkip));
        if (skipFrames >= frames) {
            positionSamples = Math.max(positionSamples, frameStartSamples + frames);
            return new short[0];
        }

        int skipSamples = skipFrames * channels;
        short[] result = skipSamples == 0 ? samples : Arrays.copyOfRange(samples, skipSamples, samples.length);
        positionSamples = Math.max(positionSamples, frameStartSamples + skipFrames + result.length / channels);
        return result;
    }

    private short[] toPcm16(byte[] bytes, int encoding) throws IOException {
        final int bytesPerSample;
        switch (encoding) {
            case PCM_8BIT: bytesPerSample = 1; break;
            case PCM_16BIT: bytesPerSample = 2; break;
            case PCM_24BIT_PACKED: bytesPerSample = 3; break;
            case PCM_32BIT:
            case PCM_FLOAT: bytesPerSample = 4; break;
            default: throw new IOException("Android PCM 格式暂不支持：" + encoding);
        }
        if (bytes.length % bytesPerSample != 0) {
            throw new IOException("Android PCM 缓冲区大小与采样位宽不匹配");
        }

        int sampleCount = bytes.length / bytesPerSample;
        short[] result = new short[sampleCount];
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < sampleCount; index++) {
            switch (encoding) {
                case PCM_8BIT:
                    result[index] = (short) (((input.get() & 0xff) - 128) << 8);
                    break;
                case PCM_16BIT:
                    result[index] = input.getShort();
                    break;
                case PCM_24BIT_PACKED: {
                    int value = (input.get() & 0xff) | ((input.get() & 0xff) << 8) | ((input.get() & 0xff) << 16);
                    if ((value & 0x800000) != 0) value |= 0xff000000;
                    result[index] = (short) (value >> 8);
                    break;
                }
                case PCM_32BIT:
                    result[index] = (short) (input.getInt() >> 16);
                    break;
                case PCM_FLOAT: {
                    float value = input.getFloat();
                    if (!Float.isFinite(value)) value = 0.0F;
                    if (value <= -1.0F) result[index] = Short.MIN_VALUE;
                    else if (value >= 1.0F) result[index] = Short.MAX_VALUE;
                    else result[index] = (short) Math.round(value * Short.MAX_VALUE);
                    break;
                }
                default:
                    throw new IOException("Android PCM 格式暂不支持：" + encoding);
            }
        }
        return result;
    }

    private void updateOutputFormat(Object format) throws IOException {
        int outputRate = formatInt(format, "sample-rate", sampleRate);
        int outputChannels = formatInt(format, "channel-count", channels);
        int outputEncoding = formatInt(format, "pcm-encoding", PCM_16BIT);
        if (outputRate != sampleRate || outputChannels != channels || outputChannels < 1 || outputChannels > 2) {
            throw new IOException("Android 解码器改变了 PCM 参数：" + outputRate + " Hz / " + outputChannels + " 声道");
        }
        pcmEncoding = outputEncoding;
    }

    private int formatInt(Object format, String key, int fallback) {
        try {
            return number(call(format, formatGetInteger, key)).intValue();
        } catch (IOException e) {
            return fallback;
        }
    }

    private long formatLong(Object format, String key, long fallback) {
        try {
            return number(call(format, formatGetLong, key)).longValue();
        } catch (IOException e) {
            return fallback;
        }
    }

    private static long samplesForUs(long timeUs, int rate) {
        if (timeUs <= 0L || rate <= 0) return 0L;
        return (long) ((timeUs / 1_000_000.0) * rate);
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Android 音频解码已取消");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Android MediaCodec parser is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        releaseQuietly(codec, extractor, codecStarted);
        codec = null;
        extractor = null;
        codecStarted = false;
    }

    private static Class<?> loadAndroidClass(String name) throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, true, context);
            } catch (ClassNotFoundException ignored) {
                // Try the runtime's system/bootstrap loader next.
            }
        }
        return Class.forName(name);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws IOException {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IOException("Android API 方法不可用：" + type.getName() + "." + name, e);
        }
    }

    private static Method findConfigureMethod(Class<?> codecClass, Class<?> formatClass) throws IOException {
        for (Method candidate : codecClass.getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (candidate.getName().equals("configure") && parameters.length == 4 &&
                    parameters[0].isAssignableFrom(formatClass) && parameters[3] == int.class) {
                return candidate;
            }
        }
        throw new IOException("Android MediaCodec.configure(MediaFormat, Surface, MediaCrypto, int) 不可用");
    }

    private static Object call(Object receiver, Method method, Object... arguments) throws IOException {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException e) {
            throw new IOException("无法访问 Android API：" + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw new IOException("Android API 调用失败：" + method.getName() +
                (cause.getMessage() == null ? "" : " — " + cause.getMessage()), cause);
        }
    }

    private static Object tryCall(Object receiver, Method method, Object... arguments) {
        try {
            return call(receiver, method, arguments);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Number number(Object value) throws IOException {
        if (value instanceof Number) return (Number) value;
        throw new IOException("Android API 返回了非数值结果");
    }

    private static void releaseQuietly(Object codec, Object extractor, boolean codecStarted) {
        if (codec != null) {
            if (codecStarted) invokeQuietly(codec, "stop");
            invokeQuietly(codec, "release");
        }
        if (extractor != null) invokeQuietly(extractor, "release");
    }

    private static void invokeQuietly(Object receiver, String name) {
        try {
            receiver.getClass().getMethod(name).invoke(receiver);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }
}

package lt.tvcast.displayreceiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Plays what a Miracast source sends: RTP packets that carry MPEG-TS (H.264 video, AAC audio in
 * ADTS). Everything runs on the thread that calls {@link #feedRtp}; pictures go to the surface
 * the moment they are decoded, sound to a low-latency AudioTrack.
 */
final class TsPlayer {
    private static final int[] RATES = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };

    private final Surface surface;
    private final RtspSink.Log log;
    private final Runnable onFirstFrame;

    private int pmtPid = -1, videoPid = -1, audioPid = -1;
    private boolean videoHevc;
    private final ByteArrayOutputStream videoPes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream audioPes = new ByteArrayOutputStream();

    private MediaCodec video, audio;
    private AudioTrack track;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private boolean shown;
    private static final int LEVEL_OVERRIDE = 31;
    private boolean levelLogged;
    private long videoBytes, decodeSum, decodeMax, decodeN;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> queuedAt = new java.util.concurrent.ConcurrentHashMap<Long, Long>();
    private final Object statsLock = new Object();
    private volatile boolean draining;
    private Runnable keyframeRequest;
    private volatile long shownDelayMs;
    private long lastOutNs, gaps45, gaps80, maxIvMs;
    private long droppedFrames, pesOverflows;
    private boolean flushedEarly;
    private long videoIn, videoOut, audioIn, lastLog = System.currentTimeMillis();

    TsPlayer(Surface surface, RtspSink.Log log, Runnable onFirstFrame) {
        this.surface = surface;
        this.log = log;
        this.onFirstFrame = onFirstFrame;
    }

    void feedRtp(byte[] b, int len) {
        if (len < 12 || (b[0] & 0xC0) != 0x80) return;
        int start = 12 + (b[0] & 0x0F) * 4;
        if ((b[0] & 0x10) != 0 && start + 4 <= len) start += 4 + (((b[start + 2] & 0xFF) << 8 | (b[start + 3] & 0xFF)) * 4);
        for (int p = start; p + 188 <= len; p += 188) ts(b, p);

        long now = System.currentTimeMillis();
        if (now - lastLog >= 5000) {
            synchronized (statsLock) {
                log.note("video in " + videoIn + " out " + videoOut + " (" + (videoBytes * 8 / 1000 / 5) + " kb/s), decode avg " + (decodeN == 0 ? 0 : decodeSum / decodeN) + " ms max " + decodeMax + " ms, dropped " + droppedFrames + ", PES overflow " + pesOverflows + ", show delay " + shownDelayMs + " ms, gaps >45 ms " + gaps45 + " >80 ms " + gaps80 + " max " + maxIvMs + " ms, audio in " + audioIn);
            }
            synchronized (statsLock) { decodeSum = 0; decodeMax = 0; decodeN = 0; gaps45 = 0; gaps80 = 0; maxIvMs = 0; }
            videoBytes = 0;
            lastLog = now;
        }
    }

    void release() {
        draining = false;
        try { if (video != null) { video.stop(); video.release(); } } catch (Exception ignored) { }
        try { if (audio != null) { audio.stop(); audio.release(); } } catch (Exception ignored) { }
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) { }
        video = null;
        audio = null;
        track = null;
    }

    // ---- MPEG-TS ----

    private void ts(byte[] b, int o) {
        if (b[o] != 0x47) return;
        boolean start = (b[o + 1] & 0x40) != 0;
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        int afc = (b[o + 3] >> 4) & 3;
        if ((afc & 1) == 0) return;
        int p = o + 4;
        if ((afc & 2) != 0) p += 1 + (b[o + 4] & 0xFF);
        int end = o + 188;
        if (p >= end) return;
        if (pid == 0) {
            if (start) p += 1 + (b[p] & 0xFF);
            if (p + 12 <= end && pmtPid < 0 && b[p] == 0) {
                int sectionEnd = Math.min(end, p + 3 + (((b[p + 1] & 0x0F) << 8) | (b[p + 2] & 0xFF)) - 4);
                for (int q = p + 8; q + 4 <= sectionEnd; q += 4) {
                    int program = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
                    if (program != 0) { pmtPid = ((b[q + 2] & 0x1F) << 8) | (b[q + 3] & 0xFF); break; }
                }
            }
        } else if (pid == pmtPid) {
            if (start) p += 1 + (b[p] & 0xFF);
            if (p + 12 <= end && b[p] == 2 && videoPid < 0) {
                int sectionEnd = Math.min(end, p + 3 + (((b[p + 1] & 0x0F) << 8) | (b[p + 2] & 0xFF)) - 4);
                int q = p + 12 + (((b[p + 10] & 0x0F) << 8) | (b[p + 11] & 0xFF));
                while (q + 5 <= sectionEnd) {
                    int type = b[q] & 0xFF;
                    int espid = ((b[q + 1] & 0x1F) << 8) | (b[q + 2] & 0xFF);
                    if ((type == 0x1B || type == 0x24) && videoPid < 0) { videoPid = espid; videoHevc = type == 0x24; }
                    else if (type == 0x0F && audioPid < 0) audioPid = espid;
                    else log.note("stream type 0x" + Integer.toHexString(type) + " on PID " + espid + " ignored");
                    q += 5 + (((b[q + 3] & 0x0F) << 8) | (b[q + 4] & 0xFF));
                }
                log.note("TS: " + (videoHevc ? "HEVC" : "H.264") + " video PID " + videoPid + ", audio PID " + audioPid);
            }
        } else if (pid == videoPid) {
            pes(videoPes, b, p, end, start, true);
        } else if (pid == audioPid) {
            pes(audioPes, b, p, end, start, false);
        }
    }

    private void pes(ByteArrayOutputStream acc, byte[] b, int p, int end, boolean start, boolean isVideo) {
        if (start) {
            flush(acc, isVideo);
            acc.reset();
            if (isVideo) {
                boolean header = end - p >= 6 && b[p] == 0 && b[p + 1] == 0 && b[p + 2] == 1;
                videoPesLength = header ? ((b[p + 4] & 0xFF) << 8) | (b[p + 5] & 0xFF) : 0;
                if (!pesLogged && header) { pesLogged = true; log.note("video PES length field: " + videoPesLength); }
            }
        }
        acc.write(b, p, end - p);
        // A PES that states its length is complete as soon as that many bytes are here: no need to
        // wait for the next picture to begin, which can be a long time when the screen is still.
        if (isVideo && videoPesLength > 0 && acc.size() >= videoPesLength + 6) {
            flush(acc, true);
            acc.reset();
            videoPesLength = 0;
            flushedEarly = true;
        } else if (isVideo && flushedEarly && !start) {
            // more data arrived for a picture already handed over: its declared length was too short
            pesOverflows++;
        }
        if (isVideo && start) flushedEarly = false;
    }

    private int videoPesLength;
    private boolean pesLogged;

    private void flush(ByteArrayOutputStream acc, boolean isVideo) {
        byte[] d = acc.toByteArray();
        if (d.length < 9 || d[0] != 0 || d[1] != 0 || d[2] != 1) return;
        int hdr = 9 + (d[8] & 0xFF);
        if (hdr > d.length) return;
        long pts = 0;
        if ((d[7] & 0x80) != 0 && d.length >= 14) {
            pts = ((long) (d[9] & 0x0E) << 29) | ((long) (d[10] & 0xFF) << 22) | ((long) (d[11] & 0xFE) << 14)
                    | ((long) (d[12] & 0xFF) << 7) | ((d[13] & 0xFF) >> 1);
        }
        long us = pts * 1000 / 90;
        if (isVideo) videoFrame(d, hdr, d.length - hdr, us);
        else audioFrames(d, hdr, d.length - hdr, us);
    }

    // ---- video ----

    private void videoFrame(byte[] d, int off, int len, long us) {
        try {
            if (video == null) {
                String mime = videoHevc ? "video/hevc" : "video/avc";
                video = MediaCodec.createDecoderByType(mime);
                MediaFormat f = MediaFormat.createVideoFormat(mime, videoHevc ? 2560 : 1280, videoHevc ? 1440 : 720);
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
                try {
                    video.configure(f, surface, null, 0);
                } catch (RuntimeException e) {
                    // Some decoders refuse low latency: the MediaTek AVC decoder of the Fire TV Stick logs
                    // "decoder can not set low-latency to 1 (err -1010)" and configure fails with ERROR_UNSUPPORTED,
                    // so no picture is ever decoded. Try again without the two optional hints.
                    log.note("decoder refused low latency (" + e + "), configuring it without");
                    video.release();
                    video = MediaCodec.createDecoderByType(mime);
                    f.removeKey(MediaFormat.KEY_LOW_LATENCY);
                    f.removeKey(MediaFormat.KEY_PRIORITY);
                    video.configure(f, surface, null, 0);
                }
                video.start();
                log.note("video decoder: " + video.getName());
                startDrain();
            }
            // A decoder sizes its picture buffer from the level in the SPS (level 4.0 = four
            // 1080p pictures, so it holds ~3 back); naming level 3.1 (two) halves the decode delay.
            // Level 3.0 was slower and stuttered.
            for (int k = off; !videoHevc && k + 7 < off + len; k++) {
                if (d[k] == 0 && d[k + 1] == 0 && d[k + 2] == 1 && (d[k + 3] & 0x1F) == 7) {
                    d[k + 6] = (byte) LEVEL_OVERRIDE;
                    if (!levelLogged) { levelLogged = true; log.note("SPS level set to " + LEVEL_OVERRIDE); }
                }
            }
            // Never drop a picture quietly: every later picture is built on it, and a gap smears the
            // image until the next key frame. Wait for the decoder; if it still has no room, ask the
            // source for a new key frame.
            int i = -1;
            for (int tries = 0; tries < 20 && i < 0 && video != null; tries++) i = video.dequeueInputBuffer(10000);
            if (i < 0) {
                droppedFrames++;
                requestKeyframe();
                return;
            }
            ByteBuffer in = video.getInputBuffer(i);
            in.clear();
            if (len > in.capacity()) len = in.capacity();
            in.put(d, off, len);
            video.queueInputBuffer(i, 0, len, us, 0);
            videoIn++;
            queuedAt.put(us, System.nanoTime());
            videoBytes += len;
        } catch (Exception e) {
            log.note("video: " + e);
        }
    }

    /** Lets the owner ask the source for a key frame (wfd_idr_request). */
    void setKeyframeRequest(Runnable r) { keyframeRequest = r; }

    private long lastKeyframeRequest;

    private void requestKeyframe() {
        long now = System.currentTimeMillis();
        Runnable r = keyframeRequest;
        if (r != null && now - lastKeyframeRequest > 1000) {
            lastKeyframeRequest = now;
            r.run();
        }
    }

    /** Pictures leave the decoder on a thread of their own, so they are never held up by input or audio. */
    private void startDrain() {
        draining = true;
        final MediaCodec codec = video;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { drainLoop(codec); }
        }, "video-out");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private void drainLoop(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long baseNs = 0, jitterNs = 0, lastNs = 0;
        boolean haveBase = false;
        try {
            while (draining) {
                int o = codec.dequeueOutputBuffer(info, 20000);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log.note("picture format: " + codec.getOutputFormat());
                    continue;
                }
                if (o < 0) continue;
                // Smooth the arrival jitter: a picture is shown at its own time plus a small delay. "Its
                // own time" is the phone's timestamp relative to the earliest arrival seen, and that
                // reference creeps up by 0.5 ms a second, so the difference between the phone's clock and
                // the TV's can never hold pictures back for long. The delay follows the measured jitter (up
                // to 60 ms): with even arrivals it is close to nothing.
                long nowNs = System.nanoTime();
                if (lastOutNs != 0) {
                    long ivMs = (nowNs - lastOutNs) / 1000000L;
                    synchronized (statsLock) { if (ivMs > 45) gaps45++; if (ivMs > 80) gaps80++; maxIvMs = Math.max(maxIvMs, ivMs); }
                }
                lastOutNs = nowNs;
                long dNs = nowNs - info.presentationTimeUs * 1000;
                if (!haveBase || Math.abs(dNs - baseNs) > 1000000000L) {
                    baseNs = dNs;
                    haveBase = true;
                    jitterNs = 0;
                } else {
                    baseNs += (nowNs - lastNs) / 2000;          // 500 ppm
                    if (dNs < baseNs) baseNs = dNs;
                    jitterNs += ((dNs - baseNs) - jitterNs) / 20;
                }
                lastNs = nowNs;
                long delayNs = Math.min(60000000L, jitterNs * 2);
                shownDelayMs = delayNs / 1000000L;
                codec.releaseOutputBuffer(o, info.presentationTimeUs * 1000 + baseNs + delayNs);
                synchronized (statsLock) { videoOut++; }
                Long q = queuedAt.remove(info.presentationTimeUs);
                if (q != null) { long ms = (System.nanoTime() - q) / 1000000L; synchronized (statsLock) { decodeSum += ms; decodeMax = Math.max(decodeMax, ms); decodeN++; } }
                if (!shown) {
                    shown = true;
                    log.note("first picture");
                    if (onFirstFrame != null) onFirstFrame.run();
                }
            }
        } catch (Exception e) {
            if (draining) log.note("video out: " + e);
        }
    }

    // ---- audio (AAC in ADTS) ----

    private void audioFrames(byte[] d, int off, int len, long us) {
        int p = off, end = off + len;
        while (p + 7 <= end && (d[p] & 0xFF) == 0xFF && (d[p + 1] & 0xF0) == 0xF0) {
            int frame = ((d[p + 3] & 3) << 11) | ((d[p + 4] & 0xFF) << 3) | ((d[p + 5] & 0xFF) >> 5);
            int header = (d[p + 1] & 1) == 0 ? 9 : 7;
            if (frame < header || p + frame > end) break;
            try {
                if (audio == null) startAudio(d, p);
                if (audio != null) {
                    int i = audio.dequeueInputBuffer(0);
                    if (i >= 0) {
                        ByteBuffer in = audio.getInputBuffer(i);
                        in.clear();
                        in.put(d, p + header, frame - header);
                        audio.queueInputBuffer(i, 0, frame - header, us, 0);
                        audioIn++;
                    }
                    drainAudio();
                }
            } catch (Exception e) {
                log.note("audio: " + e);
                break;
            }
            p += frame;
        }
    }

    private void startAudio(byte[] d, int p) throws Exception {
        int aot = ((d[p + 2] >> 6) & 3) + 1;
        int sf = (d[p + 2] >> 2) & 0x0F;
        int ch = ((d[p + 2] & 1) << 2) | ((d[p + 3] >> 6) & 3);
        if (sf >= RATES.length || ch == 0) return;
        int rate = RATES[sf];
        MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, ch);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {
                (byte) ((aot << 3) | (sf >> 1)), (byte) (((sf & 1) << 7) | (ch << 3)) }));
        audio = MediaCodec.createDecoderByType("audio/mp4a-latm");
        audio.configure(f, null, null, 0);
        audio.start();
        int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(Math.max(min, rate / 5 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        track.play();
        log.note("audio: AAC " + rate + " Hz, " + ch + " ch");
    }

    private void drainAudio() {
        int o;
        while ((o = audio.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer out = audio.getOutputBuffer(o);
            byte[] pcm = new byte[info.size];
            out.position(info.offset);
            out.get(pcm, 0, info.size);
            track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
            audio.releaseOutputBuffer(o, false);
        }
    }
}

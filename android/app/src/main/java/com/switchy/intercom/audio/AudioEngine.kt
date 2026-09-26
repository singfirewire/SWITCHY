package com.switchy.intercom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.log10

/**
 * Audio Engine ของ Intercom — รวมทุกอย่างที่เกี่ยวกับเสียงไว้ที่เดียว
 *
 * สถาปัตยกรรม:
 *   [ไมค์]  AudioRecord(VOICE_COMMUNICATION)  หรือ  Oboe・native  -> NoiseGate -> (Opus) -> UDP
 *   [หูฟัง] UDP -> JitterBuffer(ต่อผู้พูด) -> ถอดรหัส -> มิกซ์ -> AudioTrack  หรือ  Oboe・native
 *
 * เส้นทางเสียงเลือกอัตโนมัติ:
 *   1) ถ้าบิลด์มี libswitchy_audio.so และผู้ใช้เปิด useNativeOboe -> ใช้ Oboe (latency ต่ำสุด)
 *   2) ถ้าไม่ -> ใช้ AudioRecord/AudioTrack with PERFORMANCE_MODE_LOW_LATENCY (ใช้ได้ทุกเครื่อง)
 *
 * ทั้งสองเส้นทางใช้ UDP + jitter buffer + codec ชุดเดียวกัน จึงสลับได้โดยไม่กระทบโปรโตคอล
 */
class AudioEngine(
    @Volatile var params: AudioParams,
    private val log: (String) -> Unit,
    private val onStats: (Stats) -> Unit
) {

    /** ข้อมูลไว้โชว์บนจอ (ส่งทุก ~250 ms) */
    class Stats(
        val micRunning: Boolean,
        val speakerRunning: Boolean,
        val micLevelDb: Float,
        val rxLevelDb: Float,
        val txPackets: Long,
        val rxPackets: Long,
        val sources: Int,
        val bufferedMs: Int,
        val codecName: String,
        val codecNote: String,
        val effects: String,
        val backend: String,
        val dest: String,
        val localPort: Int
    )

    /** ผู้พูดหนึ่งรายที่เรากำลังฟังอยู่ */
    inner class Source(val num: Int) {
        val buffer = JitterBuffer(targetDepth = jitterFrames, maxDepth = 16)
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        @Volatile var levelDb: Float = -120f
        @Volatile var codecName: String = "pcm"

        fun touch() {
            lastSeen = System.currentTimeMillis()
        }
    }

    // ---- ค่าที่ปรับได้ระหว่างใช้งาน ----
    @Volatile var pttOn: Boolean = false
    @Volatile var fullDuplex: Boolean = false
    @Volatile var muteWhileTalking: Boolean = true
    @Volatile var jitterFrames: Int = 3

    /** ใช้ Oboe เมื่อบิลด์มี native lib (ปิดได้ถ้าอยากทดสอบเส้นทางปกติ) */
    @Volatile var useNativeOboe: Boolean = true

    val gate = NoiseGate(params.frameMs)

    @Volatile var codec: AudioCodec = PcmCodec()
    @Volatile var codecNote: String = "pcm"

    var clientNum: Int = 0
    var effectsStatus: String = "ยังไม่เปิด"
        private set

    /** เส้นทางเสียงที่ใช้อยู่จริง — โชว์บนจอเพื่อให้รู้ว่า Oboe ทำงานไหม */
    var backendName: String = "AudioRecord/AudioTrack"
        private set

    var destDescription: String = "-"
        private set
    var localPort: Int = 0
        private set

    private val transport = UdpAudioTransport { log(it) }
    private val sources = ConcurrentHashMap<Int, Source>()

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var nativeBackend: NativeAudioBackend? = null

    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private var statsThread: Thread? = null

    private val txPackets = AtomicLong()
    private val rxPackets = AtomicLong()
    private val rxBad = AtomicLong()

    @Volatile private var micLevelDb: Float = -120f
    @Volatile private var rxLevelDb: Float = -120f

    /** state ฝั่งไมค์ (ถูกเรียกจาก thread เดียวกันเสมอ: mic thread หรือ Oboe audio thread) */
    private val micLock = Any()
    private var micSeq = 0
    private var micPrevTalking = false
    private var micFirstFrameLogged = false

    /** บัฟเฟอร์มิกซ์ที่ใช้ซ้ำ (ห้าม allocate ใน audio callback) */
    private var mixBuf = ShortArray(params.samplesPerFrame)

    val isRunning: Boolean get() = running

    // ---------------------------------------------------------------- lifecycle

    fun start(host: String, port: Int, clientNum: Int): Boolean {
        stop()
        this.clientNum = clientNum
        if (!transport.open(host, port)) return false
        destDescription = transport.destDescription
        localPort = transport.localPort

        var started = false
        android.util.Log.i(
            TAG,
            "start#${hashCode()} useNativeOboe=$useNativeOboe nativeAvailable=${NativeAudio.available} " +
                "(${NativeAudio.unavailableReason}) codec=${codec.name}"
        )

        // 1) ลองเส้นทาง native ก่อน (ถ้าบิลด์มี libswitchy_audio.so)
        if (useNativeOboe && NativeAudio.available) {
            val backend = NativeAudioBackend(
                params = params,
                log = log,
                onMicFrame = { pcm, len -> handleMicFrame(pcm, len) },
                fillPlayback = { dst, len -> fillPlaybackFrame(dst, len) }
            )
            if (backend.start()) {
                nativeBackend = backend
                backendName = "Oboe (native) ${backend.version}"
                started = true
            } else {
                log("Oboe ใช้ไม่ได้ - สลับไปใช้ AudioRecord/AudioTrack (${NativeAudio.unavailableReason})")
            }
        }

        // 2) เส้นทางปกติของ Android
        if (!started) {
            if (!openDevices()) {
                transport.close()
                return false
            }
            backendName = "AudioRecord/AudioTrack (AAudio path)"
        }

        running = true
        transport.startReceive { data, len -> onPacket(data, len) }

        if (nativeBackend == null) {
            micThread = thread(name = "switchy-mic") { micLoop() }
            playThread = thread(name = "switchy-spk") { playLoop() }
        }
        statsThread = thread(name = "switchy-stats") { statsLoop() }

        android.util.Log.i(TAG, "start#${hashCode()} → backend=$backendName codec=${codec.name}")
        log("เสียงพร้อม: ${params.sampleRate} Hz / ${params.frameMs} ms / ${codecNote} · $backendName → $destDescription")
        return true
    }

    fun stop() {
        running = false
        pttOn = false

        nativeBackend?.stop()
        nativeBackend = null

        micThread?.interrupt(); micThread = null
        playThread?.interrupt(); playThread = null
        statsThread?.interrupt(); statsThread = null

        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null

        AudioEffects.release()
        sources.clear()
        gate.reset()
        transport.close()
        synchronized(micLock) {
            micSeq = 0
            micPrevTalking = false
        }
        micLevelDb = -120f
        rxLevelDb = -120f
    }

    /** เปลี่ยนค่า frame/sample rate (ต้องรีสตาร์ท engine) */
    fun reconfigure(newParams: AudioParams) {
        params = newParams
        gate.thresholdDb = gate.thresholdDb // คงค่าเดิมไว้
        mixBuf = ShortArray(newParams.samplesPerFrame)
    }

    fun setPTT(on: Boolean) {
        pttOn = on
        nativeBackend?.setPTT(on)
        if (!on) gate.reset()
    }

    private fun openDevices(): Boolean {
        val p = params

        // ---------- ไมค์ ----------
        val rec: AudioRecord? = try {
            val minBuf = AudioRecord.getMinBufferSize(
                p.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(if (minBuf > 0) minBuf else p.bytesPerFrame * 4, p.bytesPerFrame * 4)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(p.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            // หมายเหตุ: AudioRecord.Builder.setAudioAttributes() เป็น SystemApi (ไม่อยู่ใน public SDK)
            // เราใช้ AudioSource = VOICE_COMMUNICATION ซึ่งให้เส้นทางสายสนทนา + AEC ของระบบอยู่แล้ว
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "เปิด AudioRecord ไม่ได้", t)
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            log("ไมโครโฟนใช้งานไม่ได้ (อาจถูกแอปอื่นยึดอยู่)")
            runCatching { rec?.release() }
            return false
        }
        record = rec
        effectsStatus = AudioEffects.attach(rec)

        // ---------- ลำโพง ----------
        val tr: AudioTrack? = try {
            val minOut = AudioTrack.getMinBufferSize(
                p.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val outBytes = maxOf(if (minOut > 0) minOut else p.bytesPerFrame * 4, p.bytesPerFrame * 4)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(p.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(outBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "เปิด AudioTrack ไม่ได้", t)
            null
        }
        if (tr == null || tr.state != AudioTrack.STATE_INITIALIZED) {
            log("ลำโพงใช้งานไม่ได้")
            runCatching { tr?.release() }
            return false
        }
        track = tr
        return true
    }

    // ---------------------------------------------------------------- ฝั่งส่ง (ไมค์)

    /** thread ของ AudioRecord (ใช้เมื่อไม่มี Oboe) */
    private fun micLoop() {
        val rec = record ?: return
        val p = params
        val frameBytes = p.bytesPerFrame
        val buf = ByteArray(frameBytes)

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            log("เริ่มอัดเสียงไม่สำเร็จ: ${t.message}")
            return
        }

        while (running) {
            if (!readFrame(rec, buf, frameBytes)) continue
            handleMicFrame(buf, frameBytes)
        }
        runCatching { rec.stop() }
    }

    /**
     * ประมวลผลหนึ่งเฟรมจากไมค์ (ทั้งสองเส้นทางเรียกที่นี่)
     * ทำ: noise gate → เข้ารหัส → ส่ง UDP (ส่งเฉพาะตอนกดพูด หรือ full-duplex)
     */
    private fun handleMicFrame(pcm: ByteArray, len: Int) {
        val talking = pttOn || fullDuplex
        synchronized(micLock) {
            if (!micFirstFrameLogged) {
                micFirstFrameLogged = true
                android.util.Log.i(TAG, "ได้เฟรมแรกจากไมค์ (${len} ไบต์) ผ่าน $backendName")
            }
            gate.process(pcm, len)
            micLevelDb = gate.lastLevelDb

            var flags = 0
            if (codec.isOpus) flags = flags or AudioPacket.FLAG_OPUS
            when {
                talking -> {
                    sendFrame(flags or AudioPacket.FLAG_TALK, micSeq++, pcm, len)
                    micPrevTalking = true
                }

                micPrevTalking -> {
                    // เฟรมสุดท้ายของชุด: ทำเครื่องหมาย LAST ให้ปลายทางรู้ว่าจบช่วงพูด
                    micPrevTalking = false
                    sendFrame(flags or AudioPacket.FLAG_TALK or AudioPacket.FLAG_LAST, micSeq++, pcm, len)
                }
            }
        }
    }

    /** อ่านให้เต็มหนึ่งเฟรม (AudioRecord อาจคืนค่ามาไม่ครบ) */
    private fun readFrame(rec: AudioRecord, buf: ByteArray, frameBytes: Int): Boolean {
        var got = try {
            rec.read(buf, 0, frameBytes)
        } catch (t: Throwable) {
            -1
        }
        if (got < 0) {
            log("อ่านไมค์ผิดพลาด (code=$got)")
            sleepQuiet(60)
            return false
        }
        while (got < frameBytes && running) {
            val more = try {
                rec.read(buf, got, frameBytes - got)
            } catch (t: Throwable) {
                -1
            }
            if (more <= 0) return false
            got += more
        }
        return got == frameBytes
    }

    private fun sendFrame(flags: Int, seq: Int, pcm: ByteArray, len: Int) {
        val payload = codec.encode(pcm, len)
        val packet = AudioPacket.encode(
            flags = flags,
            client = clientNum,
            seq = seq,
            tsMs = System.nanoTime() / 1_000_000L,
            payload = payload
        )
        if (transport.send(packet)) txPackets.incrementAndGet()
    }

    // ---------------------------------------------------------------- ฝั่งรับ

    private fun onPacket(data: ByteArray, len: Int) {
        val h = AudioPacket.decode(data, len)
        if (h == null) {
            rxBad.incrementAndGet()
            return
        }
        if (h.client == clientNum) return // เสียงของตัวเอง (ไม่ควรมี)
        val payload = data.copyOfRange(AudioPacket.HEADER_SIZE, AudioPacket.HEADER_SIZE + h.payloadLen)
        val src = sources.getOrPut(h.client) { Source(h.client) }
        src.codecName = if (h.flags and AudioPacket.FLAG_OPUS != 0) "opus" else "pcm"
        src.buffer.push(
            JitterBuffer.Frame(
                seq = h.seq,
                tsMs = h.tsMs,
                opus = h.flags and AudioPacket.FLAG_OPUS != 0,
                last = h.flags and AudioPacket.FLAG_LAST != 0,
                data = payload
            )
        )
        rxPackets.incrementAndGet()
    }

    /** thread ของ AudioTrack (ใช้เมื่อไม่มี Oboe) */
    private fun playLoop() {
        val tr = track ?: return
        val frameBytes = params.bytesPerFrame
        val out = ByteArray(frameBytes)

        try {
            tr.play()
        } catch (t: Throwable) {
            log("เริ่มเล่นเสียงไม่สำเร็จ: ${t.message}")
            return
        }

        while (running) {
            fillPlaybackFrame(out, frameBytes)
            runCatching { tr.write(out, 0, frameBytes) }
            reapIdle()
        }
        runCatching { tr.stop() }
    }

    /**
     * เติมหนึ่งเฟรมที่จะเล่นลง dst (ทั้ง AudioTrack และ Oboe เรียกที่นี่)
     * ทำ: ดึงเฟรมจาก jitter buffer ของทุกผู้พูด → ถอดรหัส → มิกซ์ (บวก + clip) → เขียน PCM16
     * คืนจำนวนไบต์ที่เติมจริง
     */
    private fun fillPlaybackFrame(dst: ByteArray, len: Int): Int {
        val mix = mixBuf
        if (mix.size * 2 != len) mixBuf = ShortArray(len / 2)
        val target = mixBuf
        java.util.Arrays.fill(target, 0)

        var voices = 0
        var peak = 0

        for (src in sources.values) {
            val frame = src.buffer.pop() ?: continue
            val pcm = if (frame.opus) codec.decode(frame.data, frame.data.size) else frame.data
            var i = 0
            while (i + 1 < pcm.size && (i / 2) < target.size) {
                val v = target[i / 2].toInt() + AudioPacket.getShort(pcm, i)
                target[i / 2] = v.coerceIn(-32768, 32767).toShort()
                i += 2
            }
            src.levelDb = NoiseGate.rmsDb(pcm, pcm.size)
            src.touch()
            voices++
        }

        val muted = muteWhileTalking && pttOn
        if (voices == 0 || muted) {
            java.util.Arrays.fill(dst, 0, len, 0)
            rxLevelDb = -120f
            return len
        }

        for (i in target.indices) {
            val s = target[i].toInt()
            if (s > peak || -s > peak) peak = if (s < 0) -s else s
            AudioPacket.putShort(dst, i * 2, s)
        }
        rxLevelDb = if (peak < 1) -120f else (20 * log10(peak / 32768.0)).toFloat()
        return len
    }

    private fun reapIdle() {
        val now = System.currentTimeMillis()
        val it = sources.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > SOURCE_TIMEOUT_MS) {
                e.value.buffer.clear()
                it.remove()
            }
        }
    }

    private fun statsLoop() {
        while (running) {
            sleepQuiet(250)
            val list = sources.values
            val buffered = if (list.isEmpty()) 0 else list.map { it.buffer.size() }.average().toInt() * params.frameMs
            onStats(
                Stats(
                    micRunning = nativeBackend?.isRunning
                        ?: (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING),
                    speakerRunning = nativeBackend?.isRunning
                        ?: (track?.playState == AudioTrack.PLAYSTATE_PLAYING),
                    micLevelDb = micLevelDb,
                    rxLevelDb = rxLevelDb,
                    txPackets = txPackets.get(),
                    rxPackets = rxPackets.get(),
                    sources = list.size,
                    bufferedMs = buffered,
                    codecName = codec.name,
                    codecNote = codecNote,
                    effects = if (nativeBackend != null) "AEC ผ่าน Oboe (usage=VoiceCommunication)" else effectsStatus,
                    backend = backendName,
                    dest = destDescription,
                    localPort = localPort
                )
            )
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "SwitchyAudio"
        private const val SOURCE_TIMEOUT_MS = 700L

        /** ชื่อโหมดเสียงของ Android ที่ใช้ (อ้างอิงในเอกสาร) */
        fun audioModeName(): String = when (AudioManager.MODE_IN_COMMUNICATION) {
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            else -> "?"
        }
    }
}

package ua.potik.speed

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Вимірювання швидкості через сервери Cloudflare (speed.cloudflare.com):
 * пінг і джитер, завантаження й вивантаження в кілька паралельних потоків, пінг під навантаженням.
 *
 * Події йдуть у [emit] з фонового потоку.
 */
class SpeedTest(
    private val economy: Boolean,
    private val emit: (String, JSONObject) -> Unit,
) {
    class Stopped : Exception()
    class TestError(message: String) : Exception(message)

    data class Result(
        val ping: Double,
        val jitter: Double,
        val down: Double,
        val up: Double,
        val loadedDown: Double?,
        val loadedUp: Double?,
        val bytes: Long,
        val meta: JSONObject,
    )

    private val stopped = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val usedBytes = AtomicLong(0)

    private val userAgent = "Potik/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"

    /** Потоки для вимірювання: лише HTTP/1.1, щоб кожен потік мав власне TCP-з'єднання. */
    private val bulk: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(16, 1, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
        .build()

    /** Окремий клієнт для пінгів, щоб вони мали власне «тепле» з'єднання. */
    private val pinger: OkHttpClient = bulk.newBuilder()
        .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun stop() {
        stopped.set(true)
        calls.forEach { runCatching { it.cancel() } }
    }

    private fun check() { if (stopped.get()) throw Stopped() }

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", userAgent).header("Cache-Control", "no-store")

    private fun track(call: Call): Call { calls += call; return call }

    private fun failFor(r: Response): Nothing {
        when (r.code) {
            429, 403 -> throw TestError("Сервер вимірювання тимчасово обмежив запити. Спробуйте ще раз за хвилину.")
            else -> throw TestError("Сервер вимірювання відповів помилкою ${r.code}.")
        }
    }

    // ---------- головний сценарій ----------

    fun run(): Result {
        val meta = connect()
        check()
        val (ping, jitter) = latency()
        check()

        val down = bandwidth(download = true)
        check()
        val up = bandwidth(download = false)
        check()

        return Result(ping, jitter, down.speed, up.speed, down.loaded, up.loaded, usedBytes.get(), meta)
    }

    // ---------- 1. з'єднання і відомості про сервер ----------

    private fun connect(): JSONObject {
        val meta = JSONObject()
        val call = track(pinger.newCall(req("$DOWN?bytes=0").build()))
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) failFor(r)
                r.body?.bytes()
                val colo = r.header("colo") ?: r.header("cf-meta-colo")
                meta.put("ip", r.header("cf-meta-ip") ?: "")
                meta.put("colo", colo ?: "")
                meta.put("coloCity", colo?.let { COLO_CITIES[it] } ?: "")
                meta.put("city", r.header("city") ?: "")
                meta.put("country", r.header("country") ?: "")
                meta.put("asn", r.header("asn") ?: "")
            }
        } catch (e: IOException) {
            if (stopped.get()) throw Stopped()
            throw TestError("Не вдалося з'єднатися із сервером вимірювання. Перевірте інтернет.")
        } finally {
            calls -= call
        }
        // Назву провайдера беремо окремо — це не критично для вимірювання
        val isp = runCatching { isp() }.getOrNull()
        if (!isp.isNullOrBlank()) meta.put("isp", isp) else ASN_NAMES[meta.optString("asn")]?.let { meta.put("isp", it) }
        emit("meta", meta)
        return meta
    }

    private fun isp(): String? {
        val client = pinger.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        val call = track(client.newCall(req("https://ipinfo.io/json").header("Accept", "application/json").build()))
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) return null
                val org = JSONObject(r.body?.string() ?: return null).optString("org")
                return prettyIsp(org.replace(Regex("^AS\\d+\\s+"), ""))
            }
        } finally {
            calls -= call
        }
    }

    // ---------- 2. пінг і джитер ----------

    /** Одне вимірювання затримки: від запиту до заголовків відповіді мінус час обробки на сервері, мс. */
    private fun pingOnce(client: OkHttpClient): Double? {
        val call = track(client.newCall(req("$DOWN?bytes=0").build()))
        try {
            val t0 = System.nanoTime()
            call.execute().use { r ->
                val t1 = System.nanoTime()
                if (!r.isSuccessful) return null
                r.body?.bytes()
                val server = serverTime(r.headers("Server-Timing").joinToString(", "))
                val ms = (t1 - t0) / 1e6 - server
                return max(0.1, ms)
            }
        } catch (e: IOException) {
            return null
        } finally {
            calls -= call
        }
    }

    private fun latency(): Pair<Double, Double> {
        emit("phase", JSONObject().put("phase", "latency"))
        val n = if (economy) 12 else 20
        val samples = ArrayList<Double>()
        repeat(n + 1) { i ->
            check()
            val v = pingOnce(pinger)
            if (v != null && i > 0) { // перший запит відкидаємо — він «прогріває» з'єднання
                samples += v
                emit("ping", JSONObject().put("ping", median(samples)).put("jitter", jitter(samples)))
            }
        }
        if (samples.isEmpty()) throw TestError("Сервер не відповідає на запити пінгу.")
        return median(samples) to jitter(samples)
    }

    // ---------- 3. пропускна здатність ----------

    private class Bw(val speed: Double, val loaded: Double?)

    private fun bandwidth(download: Boolean): Bw {
        val total = AtomicLong(0)
        val done = AtomicBoolean(false)
        val duration = if (economy) 6_000L else if (download) 10_000L else 9_000L
        val byteCap = if (economy) (if (download) 40_000_000L else 15_000_000L) else Long.MAX_VALUE
        var streams = if (economy) 2 else 4
        val maxStreams = if (economy) 3 else 8
        val workers = ArrayList<Thread>()
        val errors = ArrayList<Throwable>()

        fun spawn(count: Int) {
            repeat(count) {
                val t = Thread({
                    try {
                        while (!done.get() && !stopped.get()) {
                            if (download) downloadChunk(total, done) else uploadChunk(total, done)
                        }
                    } catch (e: Throwable) {
                        if (!done.get() && !stopped.get()) synchronized(errors) { errors += e }
                    }
                }, "potik-" + (if (download) "down" else "up"))
                t.isDaemon = true
                workers += t
                t.start()
            }
        }

        emit("phase", JSONObject().put("phase", if (download) "download" else "upload").put("streams", streams))

        // Пінг під навантаженням — окремим потоком
        val loadedSamples = ArrayList<Double>()
        val loadedThread = Thread({
            try {
                Thread.sleep(1200)
                while (!done.get() && !stopped.get()) {
                    pingOnce(pinger)?.let { synchronized(loadedSamples) { loadedSamples += it } }
                    Thread.sleep(400)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
            }
        }, "potik-loaded").apply { isDaemon = true }

        val start = System.nanoTime()
        spawn(streams)
        loadedThread.start()

        val bins = ArrayList<Double>()        // швидкість кожні 250 мс, Мбіт/с
        val window = ArrayDeque<Pair<Long, Long>>() // (час, байти) за останні ~0.6 с
        var lastBinBytes = 0L
        var lastBinTime = start
        var nextBin = start + 250_000_000L
        var scaled = false
        try {
            while (true) {
                if (stopped.get()) throw Stopped()
                Thread.sleep(100)
                val now = System.nanoTime()
                val bytes = total.get()
                val elapsed = (now - start) / 1_000_000L
                window.addLast(now to bytes)
                while (window.size > 2 && now - window.first().first > 600_000_000L) window.removeFirst()
                val (wt, wb) = window.first()
                val inst = if (now > wt) (bytes - wb) * 8.0 / ((now - wt) / 1e9) / 1e6 else 0.0
                if (now >= nextBin) {
                    val dt = (now - lastBinTime) / 1e9
                    if (dt > 0) bins += (bytes - lastBinBytes) * 8.0 / dt / 1e6
                    lastBinBytes = bytes; lastBinTime = now; nextBin = now + 250_000_000L
                }
                // Швидкий канал — додаємо потоків, щоб його завантажити
                if (!scaled && elapsed > 1500) {
                    scaled = true
                    val more = when {
                        inst > 300 -> maxStreams - streams
                        inst > 80 -> min(maxStreams, 6) - streams
                        else -> 0
                    }
                    if (more > 0) {
                        spawn(more); streams += more
                        emit("phase", JSONObject().put("phase", if (download) "download" else "upload").put("streams", streams))
                    }
                }
                val avg = estimate(bins)
                emit("speed", JSONObject()
                    .put("dir", if (download) "down" else "up")
                    .put("mbps", inst)
                    .put("avg", if (avg > 0) avg else inst)
                    .put("progress", min(1.0, elapsed.toDouble() / duration)))
                val failedAll = synchronized(errors) { errors.size >= streams }
                if (failedAll) {
                    val e = synchronized(errors) { errors.first() }
                    throw if (e is TestError) e else TestError(
                        if (download) "Завантаження перервалося: ${e.message ?: e.javaClass.simpleName}"
                        else "Вивантаження перервалося: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                if (elapsed >= duration || bytes >= byteCap) break
            }
        } finally {
            done.set(true)
            calls.forEach { runCatching { it.cancel() } }
            loadedThread.interrupt()
            workers.forEach { runCatching { it.join(1500) } }
        }
        val speed = estimate(bins).let { if (it > 0) it else total.get() * 8.0 / ((System.nanoTime() - start) / 1e9) / 1e6 }
        val loaded = synchronized(loadedSamples) { if (loadedSamples.size >= 2) median(loadedSamples) else null }
        return Bw(speed, loaded)
    }

    /**
     * Оцінка швидкості з 250-мілісекундних відрізків: відкидаємо перші 1,5 с (розгін TCP),
     * найповільніші 30% і найшвидші 5%, решту усереднюємо.
     */
    private fun estimate(bins: List<Double>): Double {
        if (bins.size < 4) return 0.0
        val tail = bins.drop(min(6, bins.size / 3)).sorted()
        if (tail.isEmpty()) return 0.0
        val from = (tail.size * 0.30).toInt()
        val to = max(from + 1, (tail.size * 0.95).toInt())
        return tail.subList(from, min(to, tail.size)).average()
    }

    private fun downloadChunk(total: AtomicLong, done: AtomicBoolean) {
        val call = track(bulk.newCall(req("$DOWN?bytes=$CHUNK_DOWN").build()))
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) failFor(r)
                val src = r.body?.source() ?: return
                val buf = okio.Buffer()
                while (!done.get()) {
                    val n = src.read(buf, 64 * 1024)
                    if (n < 0) break
                    buf.clear()
                    total.addAndGet(n)
                    usedBytes.addAndGet(n)
                }
            }
        } catch (e: IOException) {
            if (done.get() || stopped.get()) return
            throw e
        } finally {
            calls -= call
        }
    }

    private fun uploadChunk(total: AtomicLong, done: AtomicBoolean) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = CHUNK_UP
            override fun writeTo(sink: BufferedSink) {
                var left = CHUNK_UP
                while (left > 0) {
                    if (done.get() || stopped.get()) throw InterruptedIOException("stop")
                    val n = min(left, ZEROS.size.toLong()).toInt()
                    sink.write(ZEROS, 0, n)
                    sink.flush()
                    left -= n
                    total.addAndGet(n.toLong())
                    usedBytes.addAndGet(n.toLong())
                }
            }
        }
        val call = track(bulk.newCall(req(UP).post(body).build()))
        try {
            call.execute().use { r -> if (!r.isSuccessful) failFor(r) }
        } catch (e: IOException) {
            if (done.get() || stopped.get()) return
            throw e
        } finally {
            calls -= call
        }
    }

    companion object {
        private const val DOWN = "https://speed.cloudflare.com/__down"
        private const val UP = "https://speed.cloudflare.com/__up"
        private const val CHUNK_DOWN = 25_000_000L
        private const val CHUNK_UP = 8_000_000L
        private val ZEROS = ByteArray(64 * 1024)

        /** Час обробки на сервері з Server-Timing (як у відкритому рушії Cloudflare), мс. */
        fun serverTime(header: String): Double {
            if (header.isEmpty()) return 0.0
            Regex("(?:^|,\\s*)cfReq(?:uest)?Dur(?:ation)?;\\s*dur=([0-9.]+)", RegexOption.IGNORE_CASE).find(header)?.let {
                val v = it.groupValues[1].toDoubleOrNull() ?: 0.0
                if (v > 0.01) return v
            }
            var sum = 0.0
            Regex("(?:^|,\\s*)cfSpeed[a-zA-Z]*;\\s*dur=([0-9.]+)", RegexOption.IGNORE_CASE).findAll(header).forEach {
                sum += it.groupValues[1].toDoubleOrNull() ?: 0.0
            }
            return if (sum > 0.01) sum else 0.0
        }

        fun median(list: List<Double>): Double {
            if (list.isEmpty()) return 0.0
            val s = list.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** Джитер — середня різниця між сусідніми вимірюваннями пінгу, мс. */
        fun jitter(list: List<Double>): Double {
            if (list.size < 2) return 0.0
            var sum = 0.0
            for (i in 1 until list.size) sum += abs(list[i] - list[i - 1])
            return sum / (list.size - 1)
        }

        fun prettyIsp(raw: String): String {
            val s = raw.trim()
            ISP_ALIASES.entries.firstOrNull { s.contains(it.key, ignoreCase = true) }?.let { return it.value }
            return s.replace(Regex("\\s*(PJSC|PrJSC|PJSC\\.|LLC|Ltd\\.?|LTD|JSC|Private Joint Stock Company|Limited Liability Company|TOV|PP|PE)\\b\\.?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ").trim().trim(',', '"')
        }

        private val ISP_ALIASES = linkedMapOf(
            "Kyivstar" to "Київстар",
            "lifecell" to "lifecell",
            "Astelit" to "lifecell",
            "Vodafone Ukraine" to "Vodafone",
            "VF Ukraine" to "Vodafone",
            "Ukrtelecom" to "Укртелеком",
            "Volia" to "Воля",
            "Triolan" to "Тріолан",
            "Datagroup" to "Datagroup",
            "Lanet" to "Ланет",
            "Starlink" to "Starlink",
            "SpaceX" to "Starlink",
            "Fregat" to "Фрегат",
            "Maxnet" to "Maxnet",
            "Tenet" to "Тенет",
            "Freenet" to "Freenet",
        )

        /** Запасний варіант, якщо назву провайдера не вдалося отримати. */
        private val ASN_NAMES = mapOf(
            "15895" to "Київстар", "34058" to "lifecell", "21497" to "Vodafone", "6849" to "Укртелеком",
            "25229" to "Воля", "13188" to "Тріолан", "3326" to "Datagroup", "39608" to "Ланет",
            "14593" to "Starlink", "15497" to "Фрегат", "34700" to "Maxnet", "6876" to "Тенет",
        )

        private val COLO_CITIES = mapOf(
            "KBP" to "Київ", "KIV" to "Кишинів", "WAW" to "Варшава", "KRK" to "Краків", "FRA" to "Франкфурт", "VIE" to "Відень",
            "BUD" to "Будапешт", "OTP" to "Бухарест", "PRG" to "Прага", "BTS" to "Братислава", "AMS" to "Амстердам",
            "LHR" to "Лондон", "MAN" to "Манчестер", "CDG" to "Париж", "MRS" to "Марсель", "MXP" to "Мілан", "FCO" to "Рим",
            "MAD" to "Мадрид", "BCN" to "Барселона", "LIS" to "Лісабон", "ZRH" to "Цюрих", "GVA" to "Женева",
            "MUC" to "Мюнхен", "DUS" to "Дюссельдорф", "HAM" to "Гамбург", "TXL" to "Берлін", "BER" to "Берлін",
            "CPH" to "Копенгаген", "ARN" to "Стокгольм", "OSL" to "Осло", "HEL" to "Гельсінкі", "RIX" to "Рига",
            "VNO" to "Вільнюс", "TLL" to "Таллінн", "SOF" to "Софія", "BEG" to "Белград", "ZAG" to "Загреб",
            "LJU" to "Любляна", "ATH" to "Афіни", "IST" to "Стамбул", "BRU" to "Брюссель", "DUB" to "Дублін",
            "TBS" to "Тбілісі", "EVN" to "Єреван",
        )
    }
}

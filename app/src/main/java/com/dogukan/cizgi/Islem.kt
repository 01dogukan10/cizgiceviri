package com.dogukan.cizgi

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

enum class Yazi { LATIN, KO, JA, ZH }

/** ad = ekranda görünen ad, kod = Google çeviri kodu, yazi = OCR motoru (yazı sistemi) */
enum class Dil(val ad: String, val kod: String, val yazi: Yazi) {
    EN("İngilizce", "en", Yazi.LATIN), TR("Türkçe", "tr", Yazi.LATIN),
    FR("Fransızca", "fr", Yazi.LATIN), ES("İspanyolca", "es", Yazi.LATIN),
    DE("Almanca", "de", Yazi.LATIN), IT("İtalyanca", "it", Yazi.LATIN),
    PT("Portekizce", "pt", Yazi.LATIN), ID("Endonezce", "id", Yazi.LATIN),
    KO("Korece", "ko", Yazi.KO), JA("Japonca", "ja", Yazi.JA), ZH("Çince", "zh-CN", Yazi.ZH)
}

object Islem {
    private const val MAX_GEN = 1000
    private val istemciler = HashMap<Yazi, TextRecognizer>()
    val bulunan = java.util.concurrent.atomic.AtomicInteger()    // OCR'ın bulduğu balon sayısı
    val basarisiz = java.util.concurrent.atomic.AtomicInteger()  // çevrilemeyen balon sayısı

    private fun istemci(d: Yazi): TextRecognizer = synchronized(istemciler) {
        istemciler.getOrPut(d) {
            when (d) {
                Yazi.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Yazi.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Yazi.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Yazi.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun yukle(b: ByteArray, max: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var ss = 1
        while (o.outWidth / (ss * 2) >= max) ss *= 2
        var bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
            ?: error("Resim açılamadı")
        if (bmp.width > max) {
            val h = bmp.height * max / bmp.width
            bmp = Bitmap.createScaledBitmap(bmp, max, h, true)
        }
        return bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    suspend fun sayfa(url: String, kaynak: Dil, hedef: Dil, referer: String): Bitmap = coroutineScope {
        val bmp = withContext(Dispatchers.IO) { yukle(Net.indir(url, referer), if (kaynak.yazi == Yazi.LATIN) MAX_GEN else 1600) }
        val sonuc: Text = istemci(kaynak.yazi).process(InputImage.fromBitmap(bmp, 0)).await()
        val esik = if (kaynak.yazi == Yazi.LATIN) 3 else 2
        val bloklar = sonuc.textBlocks.filter { b ->
            b.boundingBox != null && b.text.count { it.isLetter() } >= esik
        }
        bulunan.addAndGet(bloklar.size)
        val ceviriler = bloklar.map { b ->
            async(Dispatchers.IO) { Ceviri.cevir(metin(b, kaynak), kaynak.kod, hedef.kod) }
        }.awaitAll()
        basarisiz.addAndGet(ceviriler.count { it == null })
        val c = Canvas(bmp)
        bloklar.forEachIndexed { i, b -> ceviriler[i]?.let { ciz(c, bmp, b.boundingBox!!, it) } }
        bmp
    }

    // --- OCR metin düzeltme ---
    private val SAYI_HARF = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '6' to 'G', '8' to 'B')
    private val SIRA = Regex("^\\d+(st|nd|rd|th|am|pm)$", RegexOption.IGNORE_CASE)
    private val KELIME = Regex("[\\p{L}\\p{N}]+")
    private const val TIRE = "-\u2010\u2011\u2012"

    /** Harflerin arasına karışan rakamları düzeltir: CAR60 -> CARGO, W0RLD -> WORLD. */
    fun duzelt(s: String): String = KELIME.replace(s) { m ->
        val t = m.value
        val harf = t.count { it.isLetter() }
        val rakam = t.count { it.isDigit() }
        val atla = rakam == 0 || harf < 2 || harf < rakam || !t[0].isLetter() ||
            SIRA.matches(t) || t.any { it.isDigit() && it !in SAYI_HARF }
        if (atla) t else t.map { SAYI_HARF[it] ?: it }.joinToString("")
    }

    /** Bloğun satırlarını birleştirir; satır sonundaki tireyle bölünmüş kelimeleri (INJEC- / TION) tek kelime yapar. */
    fun metin(b: Text.TextBlock, kaynak: Dil): String {
        if (kaynak.yazi == Yazi.JA || kaynak.yazi == Yazi.ZH) {
            var l = b.lines.filter { it.text.isNotBlank() }
            // Dikey yazı: sütunlar sağdan sola okunur, kelimeler arasında boşluk yoktur
            val dikey = l.count { (it.boundingBox?.height() ?: 0) > (it.boundingBox?.width() ?: 0) } * 2 > l.size
            if (dikey) l = l.sortedByDescending { it.boundingBox?.left ?: 0 }
            return l.joinToString("") { it.text.trim() }
        }
        val satirlar = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (kaynak.yazi != Yazi.LATIN) return satirlar.joinToString(" ")
        val sb = StringBuilder()
        for (s in satirlar) {
            if (sb.isEmpty()) { sb.append(s); continue }
            if (sb.last() in TIRE && s.first().isLetter()) {
                val parca = sb.dropLast(1).takeLastWhile { it.isLetter() }
                if (parca.length >= 2) sb.setLength(sb.length - 1) // SUDDEN- LY -> SUDDENLY
                sb.append(s)                                        // W- WHAT -> W-WHAT (kekeleme, tire kalır)
            } else {
                sb.append(' ').append(s)
            }
        }
        return duzelt(sb.toString())
    }

    private fun zemin(bmp: Bitmap, r: Rect): Int {
        var kr = 0; var kg = 0; var kb = 0; var n = 0
        fun al(x: Int, y: Int) {
            val p = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
            kr += Color.red(p); kg += Color.green(p); kb += Color.blue(p); n++
        }
        for (i in 0..8) {
            val x = r.left + (r.width() * i) / 8; val y = r.top + (r.height() * i) / 8
            al(x, r.top - 3); al(x, r.bottom + 3); al(r.left - 3, y); al(r.right + 3, y)
        }
        return Color.rgb(kr / n, kg / n, kb / n)
    }

    private fun ciz(c: Canvas, bmp: Bitmap, r0: Rect, metin: String) {
        val bg = zemin(bmp, r0)
        val r = Rect(r0).apply { inset(-4, -4) }
        c.drawRoundRect(RectF(r), 8f, 8f, Paint().apply { color = bg })
        val koyu = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000 < 128
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (koyu) Color.WHITE else Color.BLACK }
        val w = r.width().coerceAtLeast(40)
        var boyut = 40f
        var sl: StaticLayout
        while (true) {
            tp.textSize = boyut
            sl = StaticLayout.Builder.obtain(metin, 0, metin.length, tp, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            if (sl.height <= r.height() || boyut <= 10f) break
            boyut -= 1.5f
        }
        c.save()
        c.translate(r.left.toFloat(), r.top + (r.height() - sl.height) / 2f)
        sl.draw(c)
        c.restore()
    }
}

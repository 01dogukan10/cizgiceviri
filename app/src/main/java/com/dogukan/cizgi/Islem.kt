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

enum class Dil(val ad: String) { LATIN("Latin"), KO("한국어"), JA("日本語"), ZH("中文") }

object Islem {
    private const val MAX_GEN = 1000
    private val istemciler = HashMap<Dil, TextRecognizer>()

    private fun istemci(d: Dil): TextRecognizer = synchronized(istemciler) {
        istemciler.getOrPut(d) {
            when (d) {
                Dil.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Dil.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Dil.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Dil.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun yukle(b: ByteArray): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var ss = 1
        while (o.outWidth / (ss * 2) >= MAX_GEN) ss *= 2
        var bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
            ?: error("Resim açılamadı")
        if (bmp.width > MAX_GEN) {
            val h = bmp.height * MAX_GEN / bmp.width
            bmp = Bitmap.createScaledBitmap(bmp, MAX_GEN, h, true)
        }
        return bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    suspend fun sayfa(url: String, dil: Dil, referer: String): Bitmap = coroutineScope {
        val bmp = withContext(Dispatchers.IO) { yukle(Net.indir(url, referer)) }
        val sonuc: Text = istemci(dil).process(InputImage.fromBitmap(bmp, 0)).await()
        val bloklar = sonuc.textBlocks.filter { b ->
            b.boundingBox != null && b.text.count { it.isLetter() } >= 3
        }
        val ceviriler = bloklar.map { b ->
            async(Dispatchers.IO) { Ceviri.cevir(b.text.replace("\n", " ")) }
        }.awaitAll()
        val c = Canvas(bmp)
        bloklar.forEachIndexed { i, b -> ceviriler[i]?.let { ciz(c, bmp, b.boundingBox!!, it) } }
        bmp
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

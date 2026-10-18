package com.dogukan.cizgi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class OcrBlok(val kutu: Rect, val metin: String)

/** Japonca için Tesseract (dikey yazı modeli). Modeller ilk kullanımda indirilir (~5 MB). */
object Tess {
    lateinit var ctx: Context
    private val MODELLER = listOf("jpn_vert", "jpn")
    private const val ADRES = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
    private val SEVIYE = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE

    @Synchronized
    private fun hazirla(): File {
        val kok = File(ctx.filesDir, "tess")
        val td = File(kok, "tessdata").apply { mkdirs() }
        for (m in MODELLER) {
            val f = File(td, "$m.traineddata")
            if (f.exists() && f.length() > 100_000) continue
            val gecici = File(td, "$m.tmp")
            Net.http.newCall(Request.Builder().url("$ADRES$m.traineddata").build()).execute().use { r ->
                if (!r.isSuccessful) error("Japonca model indirilemedi (HTTP ${r.code})")
                r.body!!.byteStream().use { i -> gecici.outputStream().use { o -> i.copyTo(o) } }
            }
            if (!gecici.renameTo(f)) error("Model kaydedilemedi")
        }
        return kok
    }

    private fun cjk(s: String) = s.count { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }

    suspend fun oku(bmp: Bitmap): List<OcrBlok> {
        val kok = withContext(Dispatchers.IO) { hazirla() }
        return withContext(Dispatchers.Default) {
            val api = TessBaseAPI() // iş parçacığı güvenli değil: her sayfa için ayrı örnek
            try {
                if (!api.init(kok.absolutePath, "jpn_vert+jpn")) error("Tesseract başlatılamadı")
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                api.setImage(bmp)
                api.getUTF8Text() // tanımayı çalıştırır
                val iter = api.getResultIterator() ?: return@withContext emptyList<OcrBlok>()
                val satirlar = ArrayList<OcrBlok>()
                iter.begin()
                do {
                    val t = (iter.getUTF8Text(SEVIYE) ?: "").trim()
                    val r = iter.getBoundingRect(SEVIYE)
                    if (r != null && iter.confidence(SEVIYE) >= 50f && cjk(t) >= 2) satirlar.add(OcrBlok(r, t))
                } while (iter.next(SEVIYE))
                iter.delete()
                grupla(satirlar)
            } finally {
                api.recycle()
            }
        }
    }

    /** Yakın satırları (dikey yazıda sütunları) tek balon olarak birleştirir. */
    private fun grupla(l: List<OcrBlok>): List<OcrBlok> {
        val p = IntArray(l.size) { it }
        fun kok(x: Int): Int { var a = x; while (p[a] != a) { p[a] = p[p[a]]; a = p[a] }; return a }
        for (i in l.indices) for (j in i + 1 until l.size) {
            val a = Rect(l[i].kutu).apply { inset(-28, -28) }
            if (Rect.intersects(a, l[j].kutu)) p[kok(i)] = kok(j)
        }
        return l.indices.groupBy { kok(it) }.values.map { idx ->
            val u = Rect(l[idx[0]].kutu)
            idx.forEach { u.union(l[it].kutu) }
            val g = idx.map { l[it] }
            val dikey = g.count { it.kutu.height() > it.kutu.width() } * 2 > g.size
            val sirali = if (dikey) g.sortedByDescending { it.kutu.left } else g.sortedBy { it.kutu.top }
            OcrBlok(u, sirali.joinToString("") { it.metin.replace("\n", "").replace(" ", "") })
        }
    }
}

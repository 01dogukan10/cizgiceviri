package com.dogukan.cizgi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class OcrBlok(val kutu: Rect, val metin: String, val balon: Balon? = null)

/** Japonca: önce balonlar bulunur, sonra her balon ayrı ayrı Tesseract ile okunur (dikey yazı modeli). */
object Tess {
    lateinit var ctx: Context
    private val MODELLER = listOf("jpn_vert", "jpn")
    private const val ADRES = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"

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

    /** Balonun içini keser; balonun dışında kalan her şeyi beyaza çevirir (balon çizgisi okunmasın). */
    private fun kirp(bmp: Bitmap, b: Balon): Bitmap {
        val w = b.kutu.width()
        val h = b.kutu.height()
        val k = Bitmap.createBitmap(bmp, b.kutu.left, b.kutu.top, w, h).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        k.getPixels(px, 0, w, 0, 0, w, h)
        for (j in 0 until h) for (i in 0 until w) if (i < b.sol[j] || i > b.sag[j]) px[j * w + i] = Color.WHITE
        k.setPixels(px, 0, w, 0, 0, w, h)
        return k
    }

    private fun tessAc(kok: File, dil: String, psm: Int): TessBaseAPI? {
        val a = TessBaseAPI() // iş parçacığı güvenli değil: bu sayfa için ayrı örnekler
        if (!a.init(kok.absolutePath, dil)) {
            a.recycle()
            Islem.sonHata = "Tesseract başlatılamadı ($dil)"
            return null
        }
        a.setPageSegMode(psm)
        return a
    }

    /**
     * Her balonu hem Tesseract hem ML Kit ile okur, daha çok Japonca karakter bulanı seçer.
     * Tesseract modeli inmezse yalnızca ML Kit ile devam eder.
     */
    suspend fun oku(bmp: Bitmap, mlkit: suspend (Bitmap) -> String): List<OcrBlok> {
        val baloncuklar = withContext(Dispatchers.Default) { BalonBul.bul(bmp) }
        if (baloncuklar.isEmpty()) return emptyList()
        val kok = try {
            withContext(Dispatchers.IO) { hazirla() }
        } catch (e: Exception) {
            Islem.sonHata = "Japonca modeli indirilemedi: ${e.message}"
            null
        }
        val dik = kok?.let { tessAc(it, "jpn_vert", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK_VERT_TEXT) }
        val yat = kok?.let { tessAc(it, "jpn", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK) }
        try {
            val sonuc = ArrayList<OcrBlok>()
            for (b in baloncuklar) {
                val k = kirp(bmp, b)
                val adaylar = ArrayList<String>()
                val api = if (b.kutu.height() > b.kutu.width() * 0.9) dik else yat
                if (api != null) {
                    adaylar.add(withContext(Dispatchers.Default) {
                        api.setImage(k)
                        api.getUTF8Text() ?: ""
                    })
                }
                try { adaylar.add(mlkit(k)) } catch (_: Exception) { }
                k.recycle()
                val en = adaylar.map { it.replace(Regex("\\s+"), "") }.maxByOrNull { cjk(it) } ?: ""
                val c = cjk(en)
                if (c >= 2 && c * 2 >= en.length) sonuc.add(OcrBlok(b.kutu, en, b))
            }
            return sonuc
        } finally {
            dik?.recycle()
            yat?.recycle()
        }
    }
}

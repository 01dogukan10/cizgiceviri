package com.dogukan.cizgi

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/** Konuşma balonu: sınır kutusu + her satırda balonun sol/sağ kenarı (kutunun soluna göre). */
class Balon(val kutu: Rect, val sol: IntArray, val sag: IntArray)

object BalonBul {
    /**
     * Sayfadaki balonları bulur: açık renkli, kapalı, içinde koyu yazı bulunan bölgeler.
     * Sayfa gerekirse küçültülerek (~600 px) taranır, sonuç tam çözünürlüğe çevrilir.
     */
    fun bul(bmp: Bitmap): List<Balon> {
        val s = maxOf(1, bmp.width / 600)
        val sw = bmp.width / s
        val sh = bmp.height / s
        val kucuk = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        kucuk.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (kucuk !== bmp) kucuk.recycle()
        val beyaz = BooleanArray(px.size) {
            val p = px[it]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000 >= 200
        }
        val goruldu = BooleanArray(px.size)
        val yigin = IntArray(px.size)
        val liste = IntArray(px.size)
        val sonuc = ArrayList<Balon>()

        for (bas in px.indices) {
            if (!beyaz[bas] || goruldu[bas]) continue
            var sp = 0
            var n = 0
            yigin[sp++] = bas
            goruldu[bas] = true
            var x0 = sw; var x1 = 0; var y0 = sh; var y1 = 0
            while (sp > 0) {
                val i = yigin[--sp]
                liste[n++] = i
                val x = i % sw
                val y = i / sw
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && beyaz[i - 1] && !goruldu[i - 1]) { goruldu[i - 1] = true; yigin[sp++] = i - 1 }
                if (x < sw - 1 && beyaz[i + 1] && !goruldu[i + 1]) { goruldu[i + 1] = true; yigin[sp++] = i + 1 }
                if (y > 0 && beyaz[i - sw] && !goruldu[i - sw]) { goruldu[i - sw] = true; yigin[sp++] = i - sw }
                if (y < sh - 1 && beyaz[i + sw] && !goruldu[i + sw]) { goruldu[i + sw] = true; yigin[sp++] = i + sw }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (n < 0.003 * sw * sh || bw * bh > 0.30 * sw * sh || bw < 20 || bh < 20) continue

            // her satırda sol-sağ kenar (içindeki koyu yazı da balonun parçası sayılır)
            val mn = IntArray(bh) { sw }
            val mx = IntArray(bh) { -1 }
            for (k in 0 until n) {
                val i = liste[k]
                val y = i / sw - y0
                val x = i % sw
                if (x < mn[y]) mn[y] = x
                if (x > mx[y]) mx[y] = x
            }
            var aralik = 0L
            for (r in 0 until bh) if (mx[r] >= mn[r]) aralik += mx[r] - mn[r] + 1
            val ic = (aralik - n).toDouble() / aralik   // aralığın içindeki koyu (yazı) oranı
            val dolu = n.toDouble() / (bw * bh)
            if (ic < 0.04 || ic > 0.5 || dolu < 0.45) continue

            val fx = x0 * s
            val fy = y0 * s
            val fw = minOf(bw * s, bmp.width - fx)
            val fh = minOf(bh * s, bmp.height - fy)
            val sol = IntArray(fh)
            val sag = IntArray(fh)
            for (j in 0 until fh) {
                val r = minOf(j / s, bh - 1)
                if (mx[r] < mn[r]) { sol[j] = 0; sag[j] = -1 }
                else { sol[j] = (mn[r] - x0) * s; sag[j] = minOf((mx[r] - x0 + 1) * s - 1, fw - 1) }
            }
            sonuc.add(Balon(Rect(fx, fy, fx + fw, fy + fh), sol, sag))
        }
        return sonuc
    }
}

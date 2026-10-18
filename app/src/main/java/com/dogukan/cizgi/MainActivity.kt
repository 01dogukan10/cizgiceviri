package com.dogukan.cizgi

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class E { KUTUPHANE, OKUYUCU, AYAR }

class Sayfa(val url: String) {
    var bmp by mutableStateOf<Bitmap?>(null)
    var hata by mutableStateOf(false)
}

class Vm(private val app: Application) : AndroidViewModel(app) {
    private val depo = Depo(app)
    init { Tess.ctx = app.applicationContext }

    val kayitlar = mutableStateListOf<Kayit>().apply { addAll(depo.kayitlar()) }
    val kategoriler = mutableStateListOf<String>().apply { addAll(depo.kategoriler()) }
    var tema by mutableStateOf(depo.tema)
    var renk by mutableStateOf(depo.renk)
    private fun adBul(): String? = depo.klasorUri?.let { DocumentFile.fromTreeUri(app, Uri.parse(it))?.name }
    var klasorAd by mutableStateOf(adBul())

    var ekran by mutableStateOf(E.KUTUPHANE)
    var filtre by mutableStateOf<String?>(null) // null=Tümü, ""=Kategorisiz
    private fun dilBul(s: String, v: Dil) = runCatching { Dil.valueOf(s) }.getOrDefault(v)
    var kaynak by mutableStateOf(dilBul(depo.kaynak, Dil.AUTO))
    var hedef by mutableStateOf(dilBul(depo.hedef, Dil.TR))
    fun kaynakSec(d: Dil) { kaynak = d; depo.kaynak = d.name }
    fun hedefSec(d: Dil) { hedef = d; depo.hedef = d.name }
    fun degis() { if (kaynak == Dil.AUTO) return; val k = kaynak; kaynakSec(hedef); hedefSec(k) }

    val sayfalar = mutableStateListOf<Sayfa>()
    var durum by mutableStateOf("")
    var calisiyor by mutableStateOf(false)
    private var is_: Job? = null

    // --- Güncelleme ---
    var yeniSurum by mutableStateOf<Surum?>(null)
    var guncelDurum by mutableStateOf("")
    private var reddedilen = 0

    init { guncellemeKontrol() }

    fun guncellemeKontrol(manuel: Boolean = false) {
        viewModelScope.launch {
            if (manuel) guncelDurum = "Kontrol ediliyor…"
            try {
                val s = withContext(Dispatchers.IO) { Guncelle.sonSurum() }
                when {
                    s == null -> if (manuel) guncelDurum = "Sürüm bilgisi alınamadı (internet yok ya da GitHub deposu herkese açık değil)."
                    Guncelle.yeniMi(app, s) -> { if (manuel || s.kod != reddedilen) yeniSurum = s; if (manuel) guncelDurum = "" }
                    else -> if (manuel) guncelDurum = "Uygulama güncel."
                }
            } catch (e: Exception) { if (manuel) guncelDurum = "Hata: ${e.message}" }
        }
    }

    fun sonra() { reddedilen = yeniSurum?.kod ?: 0; yeniSurum = null; guncelDurum = "" }

    fun guncelle() {
        val s = yeniSurum ?: return
        if (!Guncelle.kurabilir(app)) {
            Guncelle.izinAc(app)
            guncelDurum = "Açılan ekranda bu uygulama için yüklemeye izin ver, sonra geri dönüp Güncelle'ye bas."
            return
        }
        viewModelScope.launch {
            guncelDurum = "İndiriliyor…"
            try {
                val f = withContext(Dispatchers.IO) { Guncelle.indir(app, s) }
                Guncelle.kur(app, f); guncelDurum = ""; yeniSurum = null
            } catch (e: Exception) { guncelDurum = "Hata: ${e.message}" }
        }
    }

    // --- Ayarlar ---
    fun temaSec(t: Int) { tema = t; depo.tema = t }
    fun renkSec(r: Int) { renk = r; depo.renk = r }
    fun klasorSec(uri: Uri) { depo.klasorUri = uri.toString(); klasorAd = adBul() }
    fun klasorSifirla() { depo.klasorUri = null; klasorAd = null }

    // --- Kitaplık ---
    private fun kayitlariYaz() = depo.kayitlariKaydet(kayitlar)

    fun linkKaydet(url: String, baslik: String) {
        val u = url.trim()
        if (u.isEmpty() || kayitlar.any { it.url == u }) return
        val b = baslik.trim().ifEmpty { u.substringAfter("//").take(50) }
        kayitlar.add(0, Kayit(u, b, filtre ?: "", kaynak.name, hedef.name))
        kayitlariYaz()
    }
    fun kategoriDegistir(k: Kayit, yeni: String) {
        val i = kayitlar.indexOf(k); if (i < 0) return
        kayitlar[i] = k.copy(kategori = yeni); kayitlariYaz()
    }
    fun sil(k: Kayit) { kayitlar.remove(k); kayitlariYaz() }
    fun kategoriEkle(ad: String) {
        val a = ad.trim()
        if (a.isEmpty() || kategoriler.contains(a)) return
        kategoriler.add(a); depo.kategorileriKaydet(kategoriler); filtre = a
    }
    fun kategoriSil(ad: String) {
        kategoriler.remove(ad); depo.kategorileriKaydet(kategoriler)
        for (i in kayitlar.indices) if (kayitlar[i].kategori == ad) kayitlar[i] = kayitlar[i].copy(kategori = "")
        kayitlariYaz(); filtre = null
    }

    // --- Okuyucu ---
    fun ac(k: Kayit) {
        kaynakSec(dilBul(k.dil, Dil.EN)); hedefSec(dilBul(k.hedef, Dil.TR))
        ekran = E.OKUYUCU
        basla(k.url, k.kategori)
    }
    fun hizliCevir(url: String) { ekran = E.OKUYUCU; basla(url.trim(), filtre ?: "") }
    fun geri() { is_?.cancel(); ekran = E.KUTUPHANE }
    fun durdur() { is_?.cancel() }

    private fun basla(url: String, kat: String) {
        is_?.cancel()
        is_ = viewModelScope.launch {
            calisiyor = true
            sayfalar.clear()
            durum = "Sayfa alınıyor…"
            val t0 = System.currentTimeMillis()
            val kn = kaynak
            val hd = hedef
            Islem.bulunan.set(0); Islem.basarisiz.set(0); Islem.tespit.set(0); Islem.detay = null; Islem.sonHata = null; Islem.otoDil = null
            try {
                val urls = withContext(Dispatchers.IO) { Net.resimBul(url) }
                if (urls.isEmpty()) { durum = "Resim bulunamadı."; return@launch }
                urls.forEach { sayfalar.add(Sayfa(it)) }
                val sem = Semaphore(2)
                val bitti = AtomicInteger(0)
                coroutineScope {
                    sayfalar.map { s ->
                        async(Dispatchers.Default) {
                            sem.withPermit {
                                try { s.bmp = Islem.sayfa(s.url, kn, hd, url) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Throwable) { s.hata = true; Islem.sonHata = "${e::class.simpleName}: ${e.message}" }
                                durum = "Çevriliyor ${bitti.incrementAndGet()}/${sayfalar.size}"
                            }
                        }
                    }.awaitAll()
                }
                val ad = withContext(Dispatchers.IO) { cbzYaz(url, kat) }
                val sn = (System.currentTimeMillis() - t0) / 1000.0
                durum = "Bitti: %.1f sn (sayfa başı %.1f sn). Kayıt: $ad. Balon: ${Islem.bulunan.get()}, çevrilemeyen: ${Islem.basarisiz.get()}".format(sn, sn / sayfalar.size) + (Islem.otoDil?.let { " Algılanan dil: ${if (it == Dil.AUTO) "Latin harfli" else it.ad}." } ?: "") + (if (Islem.tespit.get() > 0) " Bulunan balon: ${Islem.tespit.get()}." else "") + (Islem.detay?.let { " [$it]" } ?: "") + (Islem.sonHata?.let { " Hata: $it" } ?: "")
            } catch (e: CancellationException) {
                durum = "Durduruldu."
            } catch (e: Exception) {
                durum = "Hata: ${e.message}"
            } finally { calisiyor = false }
        }
    }

    private fun zipYaz(out: OutputStream) {
        ZipOutputStream(out).use { z ->
            sayfalar.mapNotNull { it.bmp }.forEachIndexed { i, b ->
                z.putNextEntry(ZipEntry("%03d.jpg".format(i + 1)))
                b.compress(Bitmap.CompressFormat.JPEG, 88, z)
                z.closeEntry()
            }
        }
    }

    /** Seçilen klasöre (kategori varsa alt klasörüne), seçilmediyse uygulama klasörüne yazar. */
    private fun cbzYaz(url: String, kat: String): String {
        val ad = url.substringAfter("//").replace(Regex("[^A-Za-z0-9]+"), "_").takeLast(70).trim('_') + ".cbz"
        val uri = depo.klasorUri
        if (uri != null) {
            try {
                val kok = DocumentFile.fromTreeUri(app, Uri.parse(uri))
                if (kok != null && kok.canWrite()) {
                    var hedef: DocumentFile = kok
                    if (kat.isNotEmpty()) {
                        hedef = kok.findFile(kat)?.takeIf { it.isDirectory } ?: kok.createDirectory(kat) ?: kok
                    }
                    hedef.findFile(ad)?.delete()
                    val f = hedef.createFile("application/octet-stream", ad)
                    if (f != null) {
                        app.contentResolver.openOutputStream(f.uri)!!.buffered().use { zipYaz(it) }
                        return (if (hedef != kok) "${hedef.name}/" else "") + ad
                    }
                }
            } catch (_: Exception) { }
        }
        val d = File(app.getExternalFilesDir(null), "CizgiCevirici").apply { mkdirs() }
        File(d, ad).outputStream().buffered().use { zipYaz(it) }
        return "$ad (uygulama klasörü)"
    }
}

class MainActivity : ComponentActivity() {
    private val vm by viewModels<Vm>()
    private val klasorSec = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.klasorSec(uri)
        }
    }

    private val bildirimIzni = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) bildirimIzni.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "guncelle", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<GuncelleIsci>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        setContent {
            Tema(vm.tema, vm.renk) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (vm.ekran) {
                        E.KUTUPHANE -> Kutuphane(vm)
                        E.OKUYUCU -> Okuyucu(vm)
                        E.AYAR -> Ayar(vm) { klasorSec.launch(null) }
                    }
                    vm.yeniSurum?.let { s ->
                        AlertDialog(
                            onDismissRequest = { vm.sonra() },
                            title = { Text("Yeni sürüm var") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(s.ad)
                                    if (s.not.isNotBlank()) Text(s.not.take(300), style = MaterialTheme.typography.bodySmall)
                                    if (vm.guncelDurum.isNotEmpty()) Text(vm.guncelDurum)
                                }
                            },
                            confirmButton = { TextButton({ vm.guncelle() }) { Text("Güncelle") } },
                            dismissButton = { TextButton({ vm.sonra() }) { Text("Sonra") } }
                        )
                    }
                }
            }
        }
    }
}

val RENKLER = listOf(Color(0xFF7C4DFF), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFFE53935))
val TEMALAR = listOf("Sistem", "Açık", "Koyu", "AMOLED")

@Composable
fun Tema(t: Int, r: Int, icerik: @Composable () -> Unit) {
    val sistemKoyu = isSystemInDarkTheme()
    val c = RENKLER[r.coerceIn(0, RENKLER.lastIndex)]
    val cs = when (t) {
        1 -> lightColorScheme(primary = c)
        2 -> darkColorScheme(primary = c)
        3 -> darkColorScheme(primary = c, background = Color.Black, surface = Color.Black)
        else -> if (sistemKoyu) darkColorScheme(primary = c) else lightColorScheme(primary = c)
    }
    MaterialTheme(colorScheme = cs, content = icerik)
}

@Composable
fun Kutuphane(vm: Vm) {
    var url by remember { mutableStateOf("") }
    var baslik by remember { mutableStateOf("") }
    var yeniKat by remember { mutableStateOf(false) }
    val gorunen = vm.kayitlar.filter { vm.filtre == null || it.kategori == vm.filtre }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Kitaplık", style = MaterialTheme.typography.headlineSmall)
            TextButton({ vm.ekran = E.AYAR }) { Text("⚙ Ayarlar") }
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Bölüm / seri linki") }, singleLine = true)
            OutlinedTextField(baslik, { baslik = it }, Modifier.fillMaxWidth(), label = { Text("Başlık (isteğe bağlı)") }, singleLine = true)
            Text("Kaynak dil (sayfadaki yazı)", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dil.values().forEach { d -> FilterChip(vm.kaynak == d, { vm.kaynakSec(d) }, { Text(d.ad) }) }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Hedef dil (çeviri)", style = MaterialTheme.typography.labelMedium)
                TextButton({ vm.degis() }) { Text("⇄ Değiştir") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dil.values().filter { it != Dil.AUTO }.forEach { d -> FilterChip(vm.hedef == d, { vm.hedefSec(d) }, { Text(d.ad) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.linkKaydet(url, baslik); url = ""; baslik = "" }, enabled = url.isNotBlank()) { Text("Kaydet") }
                OutlinedButton({ vm.hizliCevir(url) }, enabled = url.isNotBlank()) { Text("Kaydetmeden çevir") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(vm.filtre == null, { vm.filtre = null }, { Text("Tümü") })
            FilterChip(vm.filtre == "", { vm.filtre = "" }, { Text("Kategorisiz") })
            vm.kategoriler.forEach { k -> FilterChip(vm.filtre == k, { vm.filtre = k }, { Text(k) }) }
            AssistChip({ yeniKat = true }, { Text("+ Kategori") })
            val f = vm.filtre
            if (f != null && f.isNotEmpty()) TextButton({ vm.kategoriSil(f) }) { Text("🗑 $f") }
        }
        if (gorunen.isEmpty()) {
            Text("Bu kategoride kayıtlı link yok.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(gorunen) { k -> KayitKarti(vm, k) }
        }
    }

    if (yeniKat) {
        var ad by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { yeniKat = false },
            title = { Text("Yeni kategori") },
            text = { OutlinedTextField(ad, { ad = it }, singleLine = true, label = { Text("Kategori adı") }) },
            confirmButton = { TextButton({ vm.kategoriEkle(ad); yeniKat = false }) { Text("Ekle") } },
            dismissButton = { TextButton({ yeniKat = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
fun KayitKarti(vm: Vm, k: Kayit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { vm.ac(k) }) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(k.baslik, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (k.kategori.isEmpty()) "Kategorisiz" else k.kategori,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(k.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                TextButton({ menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("→ Kategorisiz") }, onClick = { vm.kategoriDegistir(k, ""); menu = false })
                    vm.kategoriler.forEach { c ->
                        DropdownMenuItem(text = { Text("→ $c") }, onClick = { vm.kategoriDegistir(k, c); menu = false })
                    }
                    DropdownMenuItem(text = { Text("Sil") }, onClick = { vm.sil(k); menu = false })
                }
            }
        }
    }
}

@Composable
fun Okuyucu(vm: Vm) {
    BackHandler { vm.geri() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.geri() }) { Text("← Geri") }
            OutlinedButton({ vm.durdur() }, enabled = vm.calisiyor) { Text("Durdur") }
        }
        Text(vm.durum, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.sayfalar) { s ->
                val b = s.bmp
                if (b != null) {
                    val img = remember(b) { b.asImageBitmap() }
                    Image(img, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                        if (s.hata) Text("Sayfa yüklenemedi") else CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun Ayar(vm: Vm, klasorSec: () -> Unit) {
    BackHandler { vm.ekran = E.KUTUPHANE }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ vm.ekran = E.KUTUPHANE }) { Text("← Geri") }
        Text("Ayarlar", style = MaterialTheme.typography.headlineSmall)

        Text("Kayıt klasörü", style = MaterialTheme.typography.titleMedium)
        Text(vm.klasorAd ?: "Varsayılan (uygulama klasörü)")
        Text("Kategorisi olan bölümler, klasörün içinde kategori adıyla bir alt klasöre kaydedilir.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(klasorSec) { Text("Klasör seç") }
            if (vm.klasorAd != null) OutlinedButton({ vm.klasorSifirla() }) { Text("Varsayılana dön") }
        }

        Text("Tema", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TEMALAR.forEachIndexed { i, ad -> FilterChip(vm.tema == i, { vm.temaSec(i) }, { Text(ad) }) }
        }
        Text("Vurgu rengi", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RENKLER.forEachIndexed { i, c ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(c)
                    .then(if (vm.renk == i) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                    .clickable { vm.renkSec(i) })
            }
        }

        Text("Güncelleme", style = MaterialTheme.typography.titleMedium)
        Text("Sürüm: ${Guncelle.mevcutAd(LocalContext.current)}")
        Text("Uygulama internete bağlıyken yaklaşık saat başı yeni sürümü kontrol eder. Yeni sürüm varsa bildirim gösterir ve yüklemeden önce sana sorar.",
            style = MaterialTheme.typography.bodySmall)
        Button({ vm.guncellemeKontrol(true) }) { Text("Şimdi kontrol et") }
        if (vm.guncelDurum.isNotEmpty()) Text(vm.guncelDurum)
    }
}

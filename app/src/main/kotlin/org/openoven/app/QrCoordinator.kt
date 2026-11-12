package org.openoven.app

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.openoven.core.ConnectionProfileCodec
import java.io.File

/** QR contents never enter logs or analytics. Import requires a visible confirmation. */
class QrCoordinator(private val host:MainActivity) {
    private val cameraPermission=host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(allowed) launchScanner() else cameraUnavailable()
    }
    private val scan=host.registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { preview(it) }
    }
    private val image=host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) host.lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { decodeImage(uri) } }
            result.onSuccess(::preview).onFailure { host.error("Nie udało się odczytać QR. Wybierz wyraźny obraz z kodem Open Oven.") }
        }
    }
    fun scan() {
        if(!host.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) cameraUnavailable()
        else if(ContextCompat.checkSelfPermission(host,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) launchScanner()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun cameraUnavailable() {
        MaterialAlertDialogBuilder(host).setTitle("Aparat jest wyłączony")
            .setMessage("Możesz wczytać zapisany obraz z kodem QR. Dostęp do aparatu włączysz też w ustawieniach aplikacji.")
            .setNegativeButton("Wróć",null).setPositiveButton("Wczytaj obraz QR") { _,_-> importImage() }.show()
    }
    private fun launchScanner() { scan.launch(ScanOptions().setCaptureActivity(QrScanActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Zeskanuj kod z: Więcej → Udostępnij piekarnik")
        .setBeepEnabled(false).setOrientationLocked(false)) }
    fun importImage() { image.launch("image/*") }
    fun preview(value:String) {
        val profile=runCatching { ConnectionProfileCodec.decode(value) }.getOrElse {
            host.error(it.message ?: "To nie jest kod konfiguracji Open Oven.");return
        }
        MaterialAlertDialogBuilder(host).setTitle("Połączyć z „${profile.name}”?")
            .setMessage("Profil zastąpi ustawienia połączenia na tym telefonie. Hasło domowego Wi-Fi nie jest potrzebne.\n\nTelefon musi być w tej samej sieci co piekarnik. Najpierw rozłącz piekarnik na poprzednim telefonie.")
            .setNegativeButton("Anuluj",null).setPositiveButton("Zastosuj profil i połącz") { _,_->
                host.controller.importConnectionProfile(profile);host.navigate("home","Piekarnik")
            }.show()
    }
    fun showProfile() {
        val profile=host.controller.connectionProfile()
        val value=ConnectionProfileCodec.encode(profile)
        val bitmap=BarcodeEncoder().encodeBitmap(value,BarcodeFormat.QR_CODE,800,800,mapOf(EncodeHintType.MARGIN to 4))
        fun dp(value:Int)=(value*host.resources.displayMetrics.density).toInt()
        val layout=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8)) }
        layout.addView(ImageView(host).apply { setImageBitmap(bitmap);adjustViewBounds=true;contentDescription="Kod QR profilu piekarnika ${profile.name}" },LinearLayout.LayoutParams(-1,-2))
        layout.addView(TextView(host).apply { text="Na drugim telefonie wybierz Skanuj kod QR. Profil umożliwia lokalne sterowanie — udostępniaj go tylko domownikom. Używaj jednego telefonu naraz.";textSize=14f;setTextColor(ContextCompat.getColor(host,R.color.oven_muted));setPadding(dp(4),dp(12),dp(4),dp(8)) })
        MaterialAlertDialogBuilder(host).setTitle("Udostępnij „${profile.name}”").setView(ScrollView(host).apply { addView(layout) })
            .setPositiveButton("Gotowe",null).setNeutralButton("Wyślij obraz QR") { _,_-> share(bitmap) }.show()
    }
    private fun share(bitmap:Bitmap) {
        val folder=File(host.cacheDir,"connection-qr").apply { mkdirs() }
        val file=File(folder,"open-oven-qr.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        val uri=FileProvider.getUriForFile(host,host.packageName+".files",file)
        host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="image/png";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },"Udostępnij profil piekarnika"))
    }
    private fun decodeImage(uri:Uri):String {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        host.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,options) }
        require(options.outWidth>0 && options.outHeight>0)
        var sample=1
        while(maxOf(options.outWidth,options.outHeight)/sample>1800) sample*=2
        val bitmap=host.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample })
        } ?: error("Image unavailable")
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
            return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
        } finally { bitmap.recycle() }
    }
}

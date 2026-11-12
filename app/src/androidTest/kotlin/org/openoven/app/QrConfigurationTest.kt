package org.openoven.app

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openoven.core.*

@RunWith(AndroidJUnit4::class)
class QrConfigurationTest {
    @Test fun cameraDenialOffersImageImportWithoutChangingProfile() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val controller=AppController.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync { controller.disconnect() }
        val before=controller.settings.identity
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.scanConnectionQr() }
            var denied=false
            repeat(4) {
                Thread.sleep(400)
                val nodes=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button").orEmpty()
                if(nodes.isNotEmpty()){nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);denied=true}
            }
            assertTrue("Camera permission dialog missing",denied)
            instrumentation.waitForIdleSync()
            val root=instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(root.findAccessibilityNodeInfosByText("Aparat jest wyłączony").isNotEmpty())
            assertTrue(root.findAccessibilityNodeInfosByText("Wczytaj obraz QR").isNotEmpty())
            assertEquals(before,controller.settings.identity)
            root.findAccessibilityNodeInfosByText("Wróć").first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    @Test fun generatedQrActuallyDecodesToTheSameLocalProfile() {
        val profile=ConnectionProfile("Kuchnia — test","1234567890123456",ovenAddress="192.0.2.105")
        val encoded=ConnectionProfileCodec.encode(profile)
        val bitmap=BarcodeEncoder().encodeBitmap(encoded,BarcodeFormat.QR_CODE,800,800)
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
            assertEquals(profile,ConnectionProfileCodec.decode(decoded))
        } finally { bitmap.recycle() }
    }
    @Test fun profilePreviewDoesNotChangeIdentityOrStartConnectionBeforeConfirmation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val controller=AppController.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync { controller.disconnect() }
        val old=controller.settings.identity
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.previewConnectionQr(ConnectionProfileCodec.encode(ConnectionProfile("Drugi telefon","1234567890123456"))) }
            instrumentation.waitForIdleSync()
            assertEquals(old,controller.settings.identity)
            assertEquals(ConnectionState.DISCONNECTED,controller.client.connection.value.state)
            val cancel=instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Anuluj")
            assertTrue(cancel.isNotEmpty());cancel.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            assertEquals(old,controller.settings.identity)
        }
    }
}

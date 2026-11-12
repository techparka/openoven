package org.openoven.app

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TimePicker
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.openoven.core.*

/** Tests connect exclusively to the in-process loopback simulator, never real discovery. */
@RunWith(AndroidJUnit4::class)
class OfflineApplicationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val controller get() = AppController.get(context)
    private fun main(action:()->Unit) = instrumentation.runOnMainSync(action)
    private fun waitUntil(message:String,timeout:Long=15000,condition:()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) {
            if(condition()) return
            Thread.sleep(80)
        }
        fail(message)
    }
    private fun screenshot(name:String) {
        val shot=instrumentation.uiAutomation.takeScreenshot()
        File(File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() },name+".png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
    }
    @After fun disconnect() {
        main { controller.disconnect() }
        waitUntil("Disconnect did not complete") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
    }

    @Test fun allScreensRenderAndDemoControlsSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { assertFalse(it.canControl);it.controller.connectDemo() }
            waitUntil("Demo handshake failed") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            waitUntil("Fresh status not received") { (controller.client.status.value?.observedAtMillis ?: 0L) > 0 }
            for(screen in listOf("home","manual","monitor","stages","ready","users","history","settings","connection","connection_advanced","first_setup","more","diagnostics","help")) {
                scenario.onActivity { it.navigate(screen,screen) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue("Missing fragment $screen",it.supportFragmentManager.findFragmentById(org.openoven.app.R.id.content) is ScreenFragment)
                    assertTrue(it.canControl)
                }
                // IdleSync waits for transactions, but SurfaceFlinger can still display the previous frame.
                Thread.sleep(350)
                val shot=instrumentation.uiAutomation.takeScreenshot()
                val folder=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
                File(folder,"$screen.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                shot.recycle()
            }
            main { controller.execute(OvenCommands.common(light=100)) }
            waitUntil("Light ON did not roundtrip") { controller.client.status.value?.light==1 }
            main { controller.execute(OvenCommands.common(childLock=100)) }
            waitUntil("Child lock did not roundtrip") { controller.client.status.value?.childLock==1 }
            main { controller.execute(OvenCommands.common(light=0)) }
            waitUntil("Light OFF did not roundtrip") { controller.client.status.value?.light==0 }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(it.canControl);assertTrue(it.demo) }
            // Repeated equal readings must remain fresh, even when the oven is idle.
            Thread.sleep(17000)
            scenario.onActivity { assertTrue("Unchanging status incorrectly became stale",it.canControl) }
            main { controller.disconnect() }
            waitUntil("Disconnect failed") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { assertFalse(it.canControl) }
        }
    }

    @Test fun homeRapidHeatingTogglePreservesCookingSettingsAndDisablesWithoutConnection() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo() }
            waitUntil("Demo handshake failed") { controller.client.connection.value.state==ConnectionState.CONNECTED && controller.client.status.value!=null }
            scenario.onActivity { it.navigate("home","Piekarnik") }
            instrumentation.waitForIdleSync()
            val before=controller.client.status.value!!
            scenario.onActivity { activity -> descendants(activity.findViewById(android.R.id.content)).filterIsInstance<com.google.android.material.button.MaterialButton>().first { it.text.toString()=="Szybki nagrzew\nwyłączony" }.performClick() }
            waitUntil("Rapid heating ON did not roundtrip") { controller.client.status.value?.rapidHeating==100 }
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.content) as ScreenFragment).updateLive()
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<com.google.android.material.button.MaterialButton>().first { it.text.toString()=="Szybki nagrzew\nwłączony" }.also { assertTrue(it.isChecked) }.performClick()
            }
            waitUntil("Rapid heating OFF did not roundtrip") { controller.client.status.value?.rapidHeating==0 }
            val after=controller.client.status.value!!
            assertEquals(before.power,after.power)
            assertEquals(before.function,after.function)
            assertEquals(before.targetTemperature,after.targetTemperature)
            assertEquals(before.remainingMinutes,after.remainingMinutes)
            main { controller.disconnect() }
            waitUntil("Disconnect failed") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.content) as ScreenFragment).updateLive()
                val button=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<com.google.android.material.button.MaterialButton>().first { it.text.toString()=="Szybki nagrzew\nbrak odczytu" }
                assertFalse(button.isEnabled)
            }
        }
    }

    @Test fun manualRapidAndEndTimePickerPreserveDraftAndAllowClearingWithoutCommands() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { it.navigate("manual","Sterowanie ręczne") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                views.filterIsInstance<SwitchMaterial>().first { it.text.toString()=="Szybki nagrzew" }.isChecked=true
                assertFalse(views.filterIsInstance<Button>().any { it.text=="Więcej opcji pieczenia" })
            }
            onView(withText("Godzina zakończenia · nie ustawiono")).perform(scrollTo(),click())
            onView(isAssignableFrom(TimePicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=isAssignableFrom(TimePicker::class.java)
                override fun getDescription()="Select 18:45 using the native time picker"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) {
                    (view as TimePicker).apply { hour=18;minute=45 };ui.loopMainThreadUntilIdle()
                }
            })
            onView(withText("Ustaw")).perform(click())
            scenario.recreate();instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                assertTrue(views.filterIsInstance<SwitchMaterial>().first { it.text.toString()=="Szybki nagrzew" }.isChecked)
            }
            onView(withText("Godzina zakończenia · 18:45")).perform(scrollTo(),click())
            onView(withText("Anuluj")).perform(click())
            onView(withText("Godzina zakończenia · 18:45")).perform(scrollTo(),click())
            onView(withText("Bez godziny")).perform(click())
            scenario.onActivity { activity -> assertTrue(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().any { it.text=="Godzina zakończenia · nie ustawiono" }) }
            assertEquals(ConnectionState.DISCONNECTED,controller.client.connection.value.state)
        }
    }

    @Test fun firstConnectionEntryPreservesWorkingProfileAndDoesNotStartProvisioning() {
        val identity=controller.settings.identity
        val profile=controller.connectionProfile()
        val automatic=controller.settings.autoConnect
        val state=controller.client.connection.value.state
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.navigate("connection","Połączenie") }
            instrumentation.waitForIdleSync()
            onView(withText("Dodaj piekarnik")).perform(scrollTo(),click())
            onView(withText("Piekarnik już jest w domowym Wi-Fi")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(SetupStep.IDLE,controller.firstSetup.progress.value.step)
            assertEquals(identity,controller.settings.identity)
            assertEquals(profile,controller.connectionProfile())
            scenario.recreate();instrumentation.waitForIdleSync()
            onView(withText("Przerwij dodawanie")).perform(scrollTo(),click())
            assertEquals(automatic,controller.settings.autoConnect)
            assertEquals(state,controller.client.connection.value.state)
            assertEquals(profile,controller.connectionProfile())
        }
    }

    @Test fun rapidDisconnectAndReconnectCannotLetOldCleanupCloseNewSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo();controller.disconnect();controller.connectDemo() }
            waitUntil("New demo connection was canceled by old cleanup") { controller.client.connection.value.state==ConnectionState.CONNECTED && controller.client.status.value!=null }
            Thread.sleep(600)
            main { controller.refresh() }
            scenario.onActivity { assertTrue(it.canControl);assertTrue(it.demo) }
            assertEquals(31,controller.client.status.value!!.temperature)
        }
    }

    @Test fun homeTimeCarouselCancelsWithoutSendingAndAddsSelectedMinutes() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        fun selectMinutes(minutes:Int) {
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=isAssignableFrom(OvenWheelPicker::class.java)
                override fun getDescription()="Select additional minutes"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) {
                    val picker=view as OvenWheelPicker
                    picker.value=minutes
                    ui.loopMainThreadUntilIdle()
                }
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo() }
            waitUntil("Demo did not connect") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            main { controller.executeBatch(listOf(OvenCommands.common(power=100),OvenCommands.stages(listOf(CookingStage(6,185,UNCHANGED,20))))) }
            waitUntil("Demo did not start") { controller.client.status.value?.let { it.power==1 && it.function==6 && it.targetTemperature==185 }==true }
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(swipeUp())
            var swipedValue=5
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=isAssignableFrom(OvenWheelPicker::class.java)
                override fun getDescription()="Wait for native wheel to settle"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) {
                    ui.loopMainThreadForAtLeast(1200);swipedValue=(view as OvenWheelPicker).value
                }
            })
            assertNotEquals(5,swipedValue)
            onView(withText("Dodaj $swipedValue min")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            val wheelShot=instrumentation.uiAutomation.takeScreenshot()
            File(File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() },"home_time_extension.png").outputStream().use { wheelShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };wheelShot.recycle()
            selectMinutes(12)
            onView(withText("Anuluj")).perform(click())
            assertEquals(20,controller.client.status.value!!.remainingMinutes)
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            selectMinutes(12)
            onView(withId(android.R.id.button1)).perform(click())
            waitUntil("20 to 32 minutes was not confirmed") { controller.client.status.value?.remainingMinutes==32 && !controller.isAddingTime.value }
            assertEquals(1,controller.client.status.value!!.power)
            assertEquals(6,controller.client.status.value!!.function)
            assertEquals(185,controller.client.status.value!!.targetTemperature)
            scenario.recreate();instrumentation.waitForIdleSync()
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            selectMinutes(7)
            onView(withId(android.R.id.button1)).perform(click())
            waitUntil("32 to 39 minutes was not confirmed") { controller.client.status.value?.remainingMinutes==39 && !controller.isAddingTime.value }
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            main { controller.execute(OvenCommands.common(power=0)) }
            waitUntil("Demo did not stop") { controller.client.status.value?.power==0 }
            onView(withId(android.R.id.button1)).perform(click())
            onView(withText("Nie trwa pieczenie.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Anuluj")).perform(click())
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.content) as ScreenFragment).updateLive()
                assertEquals(View.GONE,descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().first { it.text=="Dodaj czas" }.visibility)
            }
            assertThrows(IllegalArgumentException::class.java) { controller.addMinutes(12) }
        }
    }

    @Test fun homeTemperatureSelectionCancelsChangesAndRejectsChangedStageOrStoppedOven() {
        fun chooseTemperature(value:Int) {
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=isAssignableFrom(OvenWheelPicker::class.java)
                override fun getDescription()="Select cooking temperature"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) { (view as OvenWheelPicker).value=value;ui.loopMainThreadUntilIdle() }
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo() }
            waitUntil("Demo did not connect") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            main { controller.executeBatch(listOf(OvenCommands.common(power=100),OvenCommands.stages(listOf(CookingStage(6,185,UNCHANGED,20))))) }
            waitUntil("Demo did not start") { controller.client.status.value?.let { it.power==1 && it.function==6 && it.targetTemperature==185 }==true }
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            onView(withText("Zmień temperaturę")).perform(scrollTo(),click())
            chooseTemperature(205);onView(withText("Anuluj")).perform(click())
            assertEquals(185,controller.client.status.value!!.targetTemperature)
            onView(withText("Zmień temperaturę")).perform(scrollTo(),click())
            // Native interaction must update both preview and action label.
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(swipeUp())
            var selected=185
            onView(isAssignableFrom(OvenWheelPicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=isAssignableFrom(OvenWheelPicker::class.java)
                override fun getDescription()="Wait for native temperature wheel"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) { ui.loopMainThreadForAtLeast(1200);selected=(view as OvenWheelPicker).value }
            })
            assertNotEquals(185,selected)
            onView(withText("Ustaw $selected °C")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            val shot=instrumentation.uiAutomation.takeScreenshot()
            File(File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() },"home_temperature_change.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
            chooseTemperature(205);onView(withId(android.R.id.button1)).perform(click())
            waitUntil("205 degrees not confirmed") { controller.client.status.value?.targetTemperature==205 && !controller.isChangingTemperature.value }
            assertEquals(20,controller.client.status.value!!.remainingMinutes);assertEquals(6,controller.client.status.value!!.function);assertEquals(1,controller.client.status.value!!.power)
            scenario.recreate();instrumentation.waitForIdleSync()
            onView(withText("Zmień temperaturę")).perform(scrollTo(),click())
            chooseTemperature(160);onView(withId(android.R.id.button1)).perform(click())
            waitUntil("160 degrees not confirmed") { controller.client.status.value?.targetTemperature==160 && !controller.isChangingTemperature.value }
            // Expected stage/function belongs to the dialog, not a later status.
            main { assertThrows(IllegalStateException::class.java) { controller.changeTemperature(180,2,6) } }
            onView(withText("Zmień temperaturę")).perform(scrollTo(),click())
            main { controller.execute(OvenCommands.common(power=0)) }
            waitUntil("Demo did not stop") { controller.client.status.value?.power==0 }
            onView(withId(android.R.id.button1)).perform(click())
            onView(withText("Nie trwa pieczenie.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Anuluj")).perform(click())
            assertEquals(0,controller.client.status.value!!.power)
        }
    }

    @Test fun screenshotTimingRemainsEditableAndOldDialogCannotWriteToAnotherSession() {
        val initial=OvenStatus(power=1,function=1,elapsedMinutes=47,remainingMinutes=7,temperature=180,targetTemperature=180)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo(SimulationOptions(initialStatus=initial)) }
            waitUntil("Demo not connected") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            onView(withText("7 min")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText(org.hamcrest.Matchers.containsString("Upłynęło 47 min"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("7 min")).perform(scrollTo())
            screenshot("remaining_time_47_7")
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            onView(withId(android.R.id.button1)).perform(click())
            waitUntil("Remaining 7 to 12 was not confirmed") { controller.client.status.value?.remainingMinutes==12 && !controller.isAddingTime.value }
            assertEquals(47,controller.client.status.value!!.elapsedMinutes)
            instrumentation.waitForIdleSync();Thread.sleep(300);screenshot("remaining_time_47_12")
            onView(withText("Dodaj czas")).perform(scrollTo(),click())
            val old=controller.sessionToken
            main { controller.connectDemo(SimulationOptions(initialStatus=initial)) }
            waitUntil("New session not connected") { controller.sessionToken!=old && controller.client.connection.value.state==ConnectionState.CONNECTED && controller.client.status.value?.remainingMinutes==7 }
            onView(withId(android.R.id.button1)).perform(click())
            onView(withText("Połączenie zmieniło się. Otwórz nastawy ponownie.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(7,controller.client.status.value!!.remainingMinutes)
            onView(withText("Anuluj")).perform(click())
            onView(withText("Zmień temperaturę")).perform(scrollTo(),click())
            main { controller.connectDemo(SimulationOptions(initialStatus=initial)) }
            waitUntil("Replacement demo not connected") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            onView(withId(android.R.id.button1)).perform(click())
            onView(withText("Połączenie zmieniło się. Otwórz nastawy ponownie.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Anuluj")).perform(click())
        }
    }

    @Test fun recoveredConnectionDismissesOnlyConnectionErrorsAndStaleValuesDisappear() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { it.error("Brak połączenia",connectionRelated=true) }
            onView(withText("Brak połączenia")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            main { controller.connectDemo(SimulationOptions(initialStatus=OvenStatus(power=1,function=1,temperature=180,remainingMinutes=15,elapsedMinutes=25))) }
            waitUntil("Demo not connected") { controller.client.connection.value.state==ConnectionState.CONNECTED }
            Thread.sleep(700);instrumentation.waitForIdleSync()
            onView(withText("Brak połączenia")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            scenario.onActivity { it.error("Nie potwierdzono nowej nastawy.") }
            Thread.sleep(1300)
            onView(withText("Nie potwierdzono nowej nastawy.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Zamknij")).perform(click())
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            instrumentation.waitForIdleSync()
            onView(withText("180°")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText("Pieczenie w toku")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test fun readOnlyModelAndStopOnlyModeHaveConsistentAvailableActions() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo(SimulationOptions(deviceId=1264)) }
            waitUntil("Read only demo not connected") { controller.hasFreshStatus }
            scenario.onActivity { assertFalse(it.canControl);it.navigate("home","Piekarnik") }
            instrumentation.waitForIdleSync()
            onView(withContentDescription("Połączono · tylko odczyt")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            scenario.onActivity { it.navigate("diagnostics","Diagnostyka") };instrumentation.waitForIdleSync()
            onView(withText(org.hamcrest.Matchers.containsString("DEMONSTRACJA • aktualny odczyt"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            main { controller.connectDemo(SimulationOptions(initialStatus=OvenStatus(power=1,controlMode=1,function=1))) }
            waitUntil("Stop only demo not connected") { controller.hasFreshStatus && controller.client.status.value?.controlMode==1 }
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentById(R.id.content) as ScreenFragment).updateLive()
                val views=descendants(activity.findViewById(android.R.id.content))
                assertFalse(views.filterIsInstance<Button>().first { it.contentDescription=="Włącz światło" }.isEnabled)
                assertFalse(views.filterIsInstance<com.google.android.material.button.MaterialButton>().first { it.text.startsWith("Szybki nagrzew") }.isEnabled)
                assertTrue(views.filterIsInstance<Button>().first { it.text=="Zakończ pieczenie" }.isEnabled)
            }
        }
    }

    @Test fun newCookingDefaultsAreOnlyAppliedOnStartAndUserOverridesSurviveRecreation() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo(SimulationOptions()) }
            waitUntil("Demo did not connect") { controller.hasFreshStatus }
            val before=controller.client.status.value!!
            scenario.onActivity { it.navigate("manual","Ustaw pieczenie") };instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val switches=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchMaterial>()
                assertTrue(switches.first { it.text.toString()=="Szybki nagrzew" }.isChecked)
                assertTrue(switches.first { it.text=="Światło podczas pieczenia" }.isChecked)
            }
            assertEquals(before.light,controller.client.status.value!!.light)
            assertEquals(before.rapidHeating,controller.client.status.value!!.rapidHeating)
            assertEquals(0,controller.client.status.value!!.power)
            onView(withText("Uruchom pieczenie")).perform(click())
            waitUntil("Default light/rapid were not applied") { controller.client.status.value?.let { it.power==1 && it.light==1 && it.rapidHeating==100 }==true }
            main { controller.execute(OvenCommands.common(power=0)) }
            waitUntil("Demo did not stop") { controller.client.status.value?.power==0 }
            scenario.onActivity { it.navigate("manual","Ustaw pieczenie") };instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchMaterial>().forEach { it.isChecked=false }
            }
            scenario.recreate();instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchMaterial>().all { !it.isChecked })
            }
            onView(withText("Uruchom pieczenie")).perform(click())
            waitUntil("Explicit OFF choices were not respected") { controller.client.status.value?.let { it.power==1 && it.light==0 && it.rapidHeating==0 }==true }
            // Prepared program defaults are preserved, rather than replaced by the manual default.
            val recipe=controller.recipes.readyPrograms().first { it.rapidHeating==0 }
            scenario.onActivity { it.prepare(recipe) };instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val rapid=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchMaterial>().first { it.text.toString()=="Szybki nagrzew" }
                assertFalse(rapid.isChecked);assertTrue(rapid.isEnabled)
            }
            scenario.onActivity { it.navigate("manual","Ustaw pieczenie") };instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> descendants(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Spinner>().first().setSelection(9) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val rapid=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchMaterial>().first { it.text.toString()=="Szybki nagrzew" }
                assertFalse(rapid.isChecked);assertFalse(rapid.isEnabled)
            }
        }
    }

    @Test fun cookingWheelsCancelCommitAndRestoreWithoutSendingCommands() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        fun select(value:Int, label:String = "Temperatura do ustawienia") {
            onView(org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription(label))).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription(label))
                override fun getDescription()="Select draft wheel value"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) { (view as OvenWheelPicker).value=value;ui.loopMainThreadUntilIdle() }
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo(SimulationOptions()) }
            waitUntil("Demo not connected") { controller.hasFreshStatus }
            val before=controller.client.status.value!!
            scenario.onActivity { it.navigate("manual","Ustaw pieczenie") };instrumentation.waitForIdleSync()
            onView(withContentDescription("Temperatura komory (°C)")).perform(scrollTo(),click())
            select(205);onView(withText("Anuluj")).perform(click())
            onView(withContentDescription("Temperatura komory (°C)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("180")))
            onView(withContentDescription("Temperatura komory (°C)")).perform(scrollTo(),click())
            onView(org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription("Temperatura do ustawienia"))).perform(swipeUp())
            select(205);onView(withId(android.R.id.button1)).perform(click())
            onView(withContentDescription("Czas (min)")).perform(scrollTo(),click())
            select(35, "Czas do ustawienia");onView(withId(android.R.id.button1)).perform(click())
            scenario.recreate();instrumentation.waitForIdleSync()
            onView(withContentDescription("Temperatura komory (°C)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("205")))
            onView(withContentDescription("Czas (min)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("35")))
            assertEquals(before.copy(observedAtMillis=controller.client.status.value!!.observedAtMillis),controller.client.status.value)
            scenario.onActivity { activity ->
                val inputs=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertTrue(inputs.filter { it.contentDescription in listOf("Temperatura komory (°C)","Czas (min)") }.all { !it.isFocusable })
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Spinner>().first().setSelection(7)
            }
            instrumentation.waitForIdleSync()
            onView(withContentDescription("Temperatura komory (°C)")).perform(scrollTo(),click())
            onView(org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription("Temperatura do ustawienia"))).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints()=org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription("Temperatura do ustawienia"))
                override fun getDescription()="Check function-specific temperature limit"
                override fun perform(ui:androidx.test.espresso.UiController,view:View) { assertEquals(230,(view as OvenWheelPicker).maxValue) }
            })
            screenshot("cooking_temperature_wheel");onView(withText("Anuluj")).perform(click())
            onView(withContentDescription("Czas (min)")).perform(scrollTo(),click())
            screenshot("cooking_time_wheel");onView(withText("Anuluj")).perform(click())
        }
    }

    @Test fun cookingSettingsAlwaysOpenAndLostConnectionIsExplainedWithoutLosingDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            onView(withText("Ustaw pieczenie")).perform(scrollTo(),click())
            onView(withContentDescription("Temperatura komory (°C)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Uruchom pieczenie")).perform(click())
            onView(withText("Brak aktualnego połączenia. Nastawy nie zostały wysłane. Wybierz Połącz ponownie.")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("Zamknij")).perform(click())
            main { controller.connectDemo(SimulationOptions(initialStatus=OvenStatus(power=1,function=1))) }
            waitUntil("Demo not connected") { controller.hasFreshStatus }
            scenario.onActivity { it.navigate("home","Piekarnik") };instrumentation.waitForIdleSync()
            androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(context)
            onView(withText("Ustaw pieczenie")).perform(click())
            onView(withContentDescription("Temperatura komory (°C)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(1,controller.client.status.value!!.power) // Settings is never a hidden STOP action.
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            onView(withText("Połącz ponownie")).perform(scrollTo()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withContentDescription("Temperatura komory (°C)")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("180")))
        }
    }

    @Test fun duplicateStartsAreRejectedAndPartialFailureIsReportedWithoutRetry() {
        val batch=listOf(OvenCommands.common(power=100,light=100),OvenCommands.stages(listOf(CookingStage(1,180,UNCHANGED,20))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.connectDemo(SimulationOptions(responseDelayMillis=20)) }
            waitUntil("Demo not connected") { controller.hasFreshStatus }
            main {
                controller.executeBatch(batch)
                assertTrue(controller.isStarting.value)
                assertThrows(IllegalStateException::class.java) { controller.executeBatch(batch) }
            }
            waitUntil("Start not finished") { !controller.isStarting.value && controller.client.status.value?.power==1 }
            main { controller.connectDemo(SimulationOptions(dropAfterRequests=9)) }
            waitUntil("Failure scenario not connected") { controller.hasFreshStatus && controller.client.status.value?.power==0 }
            main { controller.executeBatch(batch) }
            waitUntil("Partial failure not reported") { !controller.isStarting.value && controller.client.connection.value.state==ConnectionState.ERROR }
            assertTrue(controller.diagnosticEvents().contains("Część poleceń mogła zostać przyjęta"))
        }
    }

    @Test fun stopIsAcceptedDespiteFullQueueAndCancelsPendingStartCommands() {
        ActivityScenario.launch(MainActivity::class.java).use {
            main { controller.connectDemo(SimulationOptions(responseDelayMillis=15,initialStatus=OvenStatus(power=1,function=1))) }
            waitUntil("Demo not connected") { controller.hasFreshStatus }
            main {
                repeat(40) { runCatching { controller.execute(OvenCommands.common(light=100)) } }
                controller.execute(OvenCommands.common(power=0)) // Dedicated STOP is not rejected by the full normal queue.
            }
            waitUntil("STOP did not execute") { controller.client.status.value?.power==0 }
            main {
                controller.executeBatch(listOf(OvenCommands.common(power=100),OvenCommands.stages(listOf(CookingStage(6,200,UNCHANGED,40)))))
                controller.execute(OvenCommands.common(power=0))
            }
            waitUntil("Pending start was not cleared") { !controller.isStarting.value && controller.client.status.value?.power==0 }
            Thread.sleep(400)
            assertEquals(0,controller.client.status.value!!.power)
        }
    }

    @Test fun editingRecipePreservesItsRapidHeatingSetting() {
        val id=UUID.randomUUID().toString()
        val recipe=Recipe(id,"Audyt rapid $id","Opis","",listOf(CookingStage()),rapidHeating=100)
        controller.recipes.save(recipe)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.navigate("users","Przepisy") };instrumentation.waitForIdleSync()
                onView(withText(recipe.title)).perform(scrollTo(),click())
                onView(withText("Edytuj")).perform(scrollTo(),click())
                onView(withContentDescription("Opis")).perform(androidx.test.espresso.action.ViewActions.replaceText("Nowy opis"))
                onView(withText("Zapisz przepis")).perform(click())
                val saved=controller.recipes.userPrograms().first { it.id==id }
                assertEquals("Nowy opis",saved.description);assertEquals(100,saved.rapidHeating)
            }
        } finally { controller.recipes.delete(id) }
    }

    @Test fun identitySettingsAndHistoryPersistWithoutExecutingRecipes() {
        val settings=AppSettings(context)
        val identity=settings.identity
        assertEquals(16,identity.length)
        assertTrue(identity.all { it.code in 32..126 })
        settings.saveHistory(emptyList())
        for(i in 0..6) main { controller.remember(listOf(CookingStage(1,180+i,UNCHANGED,20+i))) }
        val recreated=AppSettings(context)
        assertEquals(identity,recreated.identity)
        assertEquals(5,recreated.history().size)
        assertEquals(186,recreated.history().first().first().temperature)
        main { controller.disconnect() }
        waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
        assertThrows(IllegalStateException::class.java) { controller.execute(OvenCommands.common(light=100)) }
    }

    @Test fun recipesCrudZipValidationAndPackagedProgramCatalog() {
        val store=RecipeStore(context)
        val ready=store.readyPrograms()
        assertTrue(ready.isNotEmpty())
        assertEquals(ready.size, ready.map { it.id }.distinct().size)
        val id=UUID.randomUUID().toString()
        val recipe=Recipe(id,"Test offline","Opis","Mąka",listOf(CookingStage(1,180,UNCHANGED,20)),instructions="Bez uruchomienia urządzenia")
        store.save(recipe)
        assertEquals(recipe.title,RecipeStore(context).userPrograms().first { it.id==id }.title)
        store.save(recipe.copy(title="Zmieniony test"))
        assertEquals("Zmieniony test",store.userPrograms().first { it.id==id }.title)
        val zip=File(context.cacheDir,"offline-${UUID.randomUUID()}.zip")
        store.exportZip(Uri.fromFile(zip))
        val before=store.userPrograms().size
        store.importZip(Uri.fromFile(zip))
        assertTrue(store.userPrograms().size>=before+1)
        val dangerous=File(context.cacheDir,"bad-${UUID.randomUUID()}.zip")
        ZipOutputStream(dangerous.outputStream()).use { out -> out.putNextEntry(ZipEntry("../escape"));out.write(byteArrayOf(1));out.closeEntry() }
        val unchanged=store.userPrograms().size
        // API34+ rejects the path inside ZipInputStream before our own validation.
        assertThrows(Exception::class.java) { store.importZip(Uri.fromFile(dangerous)) }
        assertEquals(unchanged,store.userPrograms().size)
        store.delete(id)
        assertFalse(store.userPrograms().any { it.id==id })
        zip.delete();dangerous.delete()
    }

    @Test fun diagnosticExportOmitsPrivateConnectionIdentity() {
        main { controller.connectDemo() }
        waitUntil("Demo handshake failed") { controller.client.connection.value.state==ConnectionState.CONNECTED }
        var export=""
        main { controller.exportDiagnostics { export=it } }
        assertTrue(export.contains("Open Oven"))
        assertFalse(export.contains(controller.settings.identity))
        assertFalse(export.contains("127.0.0.1"))
        assertFalse(export.contains("192.168."))
        assertFalse(export.contains("12345678"))
        assertFalse(export.contains("clientIdentity"))
    }

    @Test fun invalidSettingsAndDeniedPermissionDoNotStartConnection() {
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main { controller.disconnect() }
            waitUntil("Not disconnected") { controller.client.connection.value.state==ConnectionState.DISCONNECTED }
            scenario.onActivity { it.navigate("connection_advanced","Ustawienia zaawansowane") }
            instrumentation.waitForIdleSync()
            val previous=controller.settings.tcpPort
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                views.filterIsInstance<EditText>()[1].setText("70000")
                views.filterIsInstance<Button>().first { it.text.toString()=="Zapisz ustawienia" }.performClick()
                assertEquals(previous,controller.settings.tcpPort)
                views.filterIsInstance<Button>().first { it.text.toString()=="Połącz z piekarnikiem" }.performClick()
            }
            // Fresh emulator app data has no Wi-Fi permissions. Deny each system request.
            var denied=false
            repeat(3) {
                Thread.sleep(500)
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val nodes=root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button").orEmpty()
                if(nodes.isNotEmpty()) { nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);denied=true }
            }
            assertTrue("Expected runtime permission denial dialog",denied)
            assertEquals(ConnectionState.DISCONNECTED,controller.client.connection.value.state)
            scenario.onActivity { assertFalse(it.canControl) }
        }
    }
    @Test fun wheelAcceleratesAndCancelPreservesPreparedValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.navigate("manual", "Ustaw pieczenie") }
            instrumentation.waitForIdleSync()
            onView(withContentDescription("Temperatura komory (°C)")).perform(scrollTo(), click())
            onView(org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription("Temperatura do ustawienia"))).perform(object : androidx.test.espresso.ViewAction {
                override fun getConstraints() = org.hamcrest.Matchers.allOf(isAssignableFrom(OvenWheelPicker::class.java), withContentDescription("Temperatura do ustawienia"))
                override fun getDescription() = "Compare precise and accelerated gestures on the actual wheel"
                override fun perform(ui: androidx.test.espresso.UiController, view: View) {
                    val picker = view as OvenWheelPicker
                    val density = view.resources.displayMetrics.density
                    fun drag(distanceDp: Float, pause: Long, hold: Boolean): Int {
                        picker.value = 180
                        val down = android.os.SystemClock.uptimeMillis()
                        val startY = view.height - 12 * density
                        fun send(action: Int, y: Float) {
                            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                                action, view.width / 2f, y, 0)
                            picker.dispatchTouchEvent(event)
                            event.recycle()
                        }
                        send(android.view.MotionEvent.ACTION_DOWN, startY)
                        for (step in 1..6) {
                            ui.loopMainThreadForAtLeast(pause)
                            send(android.view.MotionEvent.ACTION_MOVE, startY - distanceDp * density * step / 6)
                        }
                        if (hold) {
                            val before = picker.value
                            ui.loopMainThreadForAtLeast(200)
                            assertEquals("Holding still must not advance the wheel", before, picker.value)
                        }
                        send(android.view.MotionEvent.ACTION_UP, startY - distanceDp * density)
                        ui.loopMainThreadForAtLeast(1400)
                        picker.finish()
                        return picker.value
                    }
                    val precise = drag(44f, 100, true)
                    val accelerated = drag(132f, 10, false)
                    assertTrue("Slow drag must retain single-degree precision: $precise", precise in 181..182)
                    assertTrue("Quick swipe must travel further: $accelerated", accelerated > precise + 3)
                }
            })
            onView(withText("Anuluj")).perform(click())
            onView(withContentDescription("Temperatura komory (°C)"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("180")))
        }
    }

    @Test fun wheelSupportsExactAccessibilityValuesAndRespectsLimits() {
        main {
            val picker = OvenWheelPicker(context).apply { minValue = 30; maxValue = 230; unit = "°C"; value = 180 }
            picker.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
            assertEquals(181, picker.value)
            picker.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
            assertEquals(180, picker.value)
            picker.value = 500
            assertEquals(230, picker.value)
            picker.value = -10
            assertEquals(30, picker.value)
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                val arguments = android.os.Bundle().apply {
                    putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 187f)
                }
                assertTrue(picker.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
                assertEquals(187, picker.value)
            }
        }
    }

}

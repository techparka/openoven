package org.openoven.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openoven.app.databinding.ActivityMainBinding
import org.openoven.core.ConnectionState
import org.openoven.core.CookingStage
import org.openoven.core.OvenCommand
import org.openoven.core.OvenCommands
import org.openoven.core.UNCHANGED

class MainActivity : AppCompatActivity() {
    lateinit var controller: AppController; private set
    private lateinit var binding: ActivityMainBinding
    private val qr=QrCoordinator(this)
    private var currentScreen="home"
    private var currentTitle="Piekarnik"
    var preparedRecipe: Recipe? = null
    var preparedStages: List<CookingStage>? = null

    val canControl:Boolean get()=controller.canControl
    val hasFreshStatus:Boolean get()=controller.hasFreshStatus
    private var errorSnackbar:com.google.android.material.snackbar.Snackbar?=null
    private var displayedError:AppError?=null
    val demo: Boolean get() = controller.client.connection.value.demo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AppController.get(applicationContext)
        savedInstanceState?.getIntArray("preparedStages")?.let { data ->
            preparedStages=data.toList().chunked(4).filter { it.size == 4 }.map { CookingStage(it[0],it[1],it[2],it[3]) }
        }
        savedInstanceState?.getString("preparedRecipe")?.let { id ->
            preparedRecipe=(controller.recipes.userPrograms()+controller.recipes.readyPrograms()).find { it.id == id }
        }
        val desired = controller.settings.themeMode
        if (AppCompatDelegate.getDefaultNightMode() != desired) AppCompatDelegate.setDefaultNightMode(desired)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val lightBars = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSupportActionBar(binding.toolbar)
        binding.bottomNavigation.isItemActiveIndicatorEnabled = false
        if (resources.configuration.fontScale <= 1.3f) binding.bottomNavigation.layoutParams.height = (64 * resources.displayMetrics.density).toInt()
        binding.homeConnection.setOnClickListener { binding.connectionStatus.performClick() }
        binding.bottomNavigation.setOnItemSelectedListener {
            when(it.itemId) {
                R.id.nav_home -> navigate("home","Piekarnik")
                R.id.nav_programs -> navigate("ready","Programy")
                R.id.nav_recipes -> navigate("users","Przepisy")
                R.id.nav_more -> navigate("more","Więcej")
            };true
        }
        binding.connectionStatus.setOnClickListener {
            if(controller.firstSetup.progress.value.step in setOf(org.openoven.core.SetupStep.CONNECTING_AP,org.openoven.core.SetupStep.AP_READY,org.openoven.core.SetupStep.SENDING_WIFI)) navigate("first_setup","Dodaj piekarnik")
            else navigate("connection","Połączenie")
        }
        currentScreen=savedInstanceState?.getString("currentScreen") ?: "home"
        currentTitle=savedInstanceState?.getString("currentTitle") ?: "Piekarnik"
        if (savedInstanceState == null) navigate("home", "Piekarnik") else renderNavigation()
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            if(currentScreen=="first_setup" && controller.firstSetup.progress.value.step !in setOf(org.openoven.core.SetupStep.IDLE,org.openoven.core.SetupStep.COMPLETE)) {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Przerwać dodawanie?")
                    .setMessage("Zakończymy bieżące połączenie. Wysłane ustawienia Wi-Fi pozostają na piekarniku; później możesz sprawdzić połączenie bez ponownego wysyłania hasła.")
                    .setNegativeButton("Zostań",null).setPositiveButton("Przerwij") { _,_->controller.cancelFirstSetup();navigate("connection","Połączenie") }.show()
            } else if ((supportFragmentManager.findFragmentById(R.id.content) as? ScreenFragment)?.returnToRecipeList() == true) {
                return@addCallback
            } else if(currentScreen!="home") navigate("home","Piekarnik")
            else { isEnabled=false;onBackPressedDispatcher.onBackPressed() }
        }
        controller.resumeConfiguredConnection()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.client.connection.collect { info ->
                    render()
                } }
                launch { controller.client.status.collect { state ->
                    render()
                } }
                launch { controller.client.identity.collect { render() } }
                launch { controller.firstSetup.progress.collect { render() } }
                launch { controller.isAddingTime.collect { render() } }
                launch { controller.isStarting.collect { render() } }
                launch { controller.isChangingTemperature.collect { render() } }
                launch { controller.error.collect { error -> if (error != null) { if(error.session==controller.sessionToken) this@MainActivity.error(error.message,error.connectionRelated,error.session); controller.clearError() } } }
                launch { while (true) { delay(1000); render() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.resumeConfiguredConnection()
    }

    fun navigate(screen: String, title: String) {
        if(screen=="manual" || screen=="stages") controller.noteCookingNavigation()
        currentScreen=screen
        currentTitle=title
        supportFragmentManager.beginTransaction().replace(R.id.content, ScreenFragment.create(screen)).commit()
        renderNavigation()
    }

    private fun renderNavigation() {
        val screen = currentScreen
        val topLevel = screen in setOf("home", "ready", "users", "more")
        supportActionBar?.title = if (topLevel) null else "OPENOVEN"
        supportActionBar?.subtitle = null
        binding.homeHeader.visibility = if (topLevel) View.VISIBLE else View.GONE
        binding.connectionStatus.visibility = if (screen in setOf("connection", "connection_advanced", "first_setup", "diagnostics")) View.VISIBLE else View.GONE
        binding.homeOvenName.text = controller.settings.ovenName
        binding.toolbar.setContentInsetsRelative((16 * resources.displayMetrics.density).toInt(), 0)
        binding.toolbar.minimumHeight = ((if (screen == "home") 64 else 52) * resources.displayMetrics.density).toInt()
        binding.toolbar.navigationIcon = if (topLevel) null else ContextCompat.getDrawable(this, R.drawable.ic_back)
        if (!topLevel) binding.toolbar.setNavigationContentDescription(R.string.navigate_back)
        val id=when(screen){"home","manual","stages","monitor"->R.id.nav_home;"ready"->R.id.nav_programs;"users"->R.id.nav_recipes;else->R.id.nav_more}
        binding.bottomNavigation.menu.findItem(id)?.isChecked=true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("preparedRecipe",preparedRecipe?.id)
        preparedStages?.let { stages -> outState.putIntArray("preparedStages", stages.flatMap { listOf(it.function,it.temperature,it.probeTemperature,it.durationMinutes) }.toIntArray()) }
        super.onSaveInstanceState(outState)
        outState.putString("currentScreen",currentScreen)
        outState.putString("currentTitle",currentTitle)
    }

    private fun render() {
        displayedError?.let { error ->
            if(error.session!=controller.sessionToken || (error.connectionRelated && hasFreshStatus)) {
                errorSnackbar?.dismiss();errorSnackbar=null;displayedError=null
            }
        }
        val info = controller.client.connection.value
        binding.demoBanner.visibility = if (info.demo) View.VISIBLE else View.GONE
        val setup=controller.firstSetup.progress.value.step
        val setupLabel=if(currentScreen=="first_setup") when(setup) {
            org.openoven.core.SetupStep.CONNECTING_AP -> "Łączę z siecią SmartIN…"
            org.openoven.core.SetupStep.AP_READY -> "SmartIN · gotowy do konfiguracji"
            org.openoven.core.SetupStep.SENDING_WIFI -> "Przekazuję ustawienia Wi-Fi…"
            org.openoven.core.SetupStep.HOME_REQUIRED -> "Dodawanie · sprawdź domowe Wi-Fi"
            org.openoven.core.SetupStep.VERIFYING_HOME -> "Dodawanie · sprawdzam piekarnik…"
            org.openoven.core.SetupStep.ERROR -> "Dodawanie · spróbuj ponownie"
            else -> null
        } else null
        val state = setupLabel ?: when (info.state) {
            ConnectionState.DISCONNECTED -> "Nie połączono · skonfiguruj piekarnik"
            ConnectionState.LISTENING -> "Łączę z piekarnikiem…"
            ConnectionState.AUTHENTICATING -> "Potwierdzam połączenie…"
            ConnectionState.CONNECTED -> if (hasFreshStatus) { if(canControl) "Połączono · aktualny odczyt" else "Połączono · tylko odczyt" } else "Odczyt nieaktualny · odśwież połączenie"
            ConnectionState.ERROR -> "Brak połączenia · sprawdź Wi-Fi"
        }
        binding.homeConnection.apply {
            text = when {
                info.state == ConnectionState.CONNECTED && hasFreshStatus -> if (canControl) "Połączono" else "Tylko odczyt"
                info.state in setOf(ConnectionState.LISTENING, ConnectionState.AUTHENTICATING) -> "Łączenie…"
                else -> "Brak odczytu"
            }
            contentDescription = state
            val ink = ContextCompat.getColor(this@MainActivity, if (hasFreshStatus) R.color.oven_success else R.color.oven_muted)
            setTextColor(ink)
            iconTint = ColorStateList.valueOf(ink)
        }
        val statusButton = binding.connectionStatus
        if (statusButton.text != state || statusButton.backgroundTintList == null) {
            val (ink, fill) = when {
                setupLabel != null -> R.color.oven_muted to R.color.oven_neutral
                info.state == ConnectionState.CONNECTED && hasFreshStatus -> R.color.oven_success to R.color.oven_success_soft
                info.state == ConnectionState.ERROR -> R.color.oven_danger to R.color.oven_danger_soft
                else -> R.color.oven_muted to R.color.oven_neutral
            }
            statusButton.text = state
            statusButton.setTextColor(ContextCompat.getColor(this, ink))
            statusButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, ink))
            statusButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, fill))
        }
        (supportFragmentManager.findFragmentById(R.id.content) as? ScreenFragment)?.updateLive()
    }

    fun message(text: String) { com.google.android.material.snackbar.Snackbar.make(binding.root,text,com.google.android.material.snackbar.Snackbar.LENGTH_LONG).setAnchorView(binding.bottomNavigation).show() }
    fun error(text:String,connectionRelated:Boolean=false,session:Int=controller.sessionToken) {
        if(session!=controller.sessionToken || (connectionRelated && hasFreshStatus)) return
        errorSnackbar?.dismiss()
        displayedError=AppError(text,connectionRelated,session)
        val friendly=if(connectionRelated && (text.contains("EPERM") || text.contains("Socket") || text.contains("piekarnik nie odpowiedział",true)))
            "Nie udało się połączyć. Sprawdź domowe Wi-Fi i rozłącz piekarnik na drugim telefonie." else text
        errorSnackbar=com.google.android.material.snackbar.Snackbar.make(binding.root,friendly,com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(binding.bottomNavigation).setAction("Zamknij") {}.apply {
                view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text).maxLines=8
            }.also { it.show() }
    }
    fun scanConnectionQr()=qr.scan()
    fun importConnectionQrImage()=qr.importImage()
    fun showConnectionQr()=qr.showProfile()
    fun previewConnectionQr(value:String)=qr.preview(value)

    fun send(command: OvenCommand, confirmation: String? = null, confirmationAction:String="Potwierdź") = sendAll(listOf(command), confirmation,confirmationAction)

    fun sendAll(commands: List<OvenCommand>, confirmation: String? = null, confirmationAction:String="Potwierdź") {
        if (!canControl) { error(if(hasFreshStatus) "Ten model udostępnia tylko odczyt. Sterowanie nie jest jeszcze obsługiwane." else "Brak aktualnego połączenia. Nastawy nie zostały wysłane. Wybierz Połącz ponownie."); return }
        val session=controller.sessionToken
        val commit = {
            if(session!=controller.sessionToken) message("Połączenie zmieniło się. Polecenie pominięte; wybierz działanie ponownie.")
            else if (!canControl) message("Połączenie utraciło aktualność. Polecenie nie zostało wysłane.")
            else runCatching { controller.executeBatch(commands) }.onFailure { error(it.message ?: "Nie wysłano polecenia. Odśwież połączenie i spróbuj ponownie.") }.let { Unit }
        }
        if (confirmation != null && !demo) MaterialAlertDialogBuilder(this).setTitle(confirmation.substringBefore('?')+"?")
            .setMessage(confirmation.substringAfter('?',"").trim().ifBlank { null })
            .setNegativeButton("Anuluj", null).setPositiveButton(confirmationAction) { _, _ -> commit() }.show()
        else commit()
    }

    fun startCooking(stages: List<CookingStage>, end: Int = UNCHANGED, rapid: Int = 0, recipe: Recipe? = null, light: Boolean = true) {
        val summary = stages.mapIndexed { i, s -> "Etap ${i + 1}: ${Functions.name(s.function)}, ${if (s.temperature == UNCHANGED) "z programu" else "${s.temperature} °C"}, ${if (s.durationMinutes == UNCHANGED) "czas z programu" else "${s.durationMinutes} min"}" }.joinToString("\n")
        val commands = listOf(OvenCommands.common(power = 100,light=if(light)100 else 0), OvenCommands.stages(stages,
            weight = UNCHANGED, endMinutes = end, rapidHeating = rapid))
        sendAll(commands, "Rozpocząć pieczenie?\n\n${recipe?.title.orEmpty()}\n$summary\nŚwiatło: ${if(light) "włączone" else "wyłączone"}\nSzybki nagrzew: ${when(rapid) { 100 -> "włączony";0 -> "wyłączony";else -> "według programu" }}\n\nSprawdź wnętrze piekarnika i przygotowanie potrawy.","Rozpocznij")
    }

    fun prepare(recipe: Recipe) {
        preparedRecipe = recipe; preparedStages = recipe.stages
        navigate("stages", recipe.title)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Odśwież stan"); menu.add(0, 2, 1, "Połączenie"); menu.add(0, 3, 2, "Diagnostyka"); menu.add(0, 4, 3, "Ustaw pieczenie")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> controller.refresh()
            2 -> navigate("connection", "Połączenie i Wi-Fi")
            3 -> navigate("diagnostics", "Diagnostyka")
            4 -> navigate("manual", "Ustaw pieczenie")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}

object Functions {
    val labels = listOf("Konwencjonalne", "Grzałka dolna + górna + wentylator", "Grill", "Turbo grill", "Super grill", "Termoobieg", "Termoobieg + dolna grzałka", "Zarumienienie", "Przypiekanie", "Rozmrażanie", "Grill + rożen", "Super grill + rożen")
    fun name(number: Int) = labels.getOrNull(number - 1) ?: "Funkcja $number"
}

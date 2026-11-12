package org.openoven.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import androidx.core.view.ViewCompat
import org.openoven.app.databinding.FragmentScreenBinding
import org.openoven.core.*
import java.util.UUID

/** Local screens. Draft fields are not overwritten by polling device state. */
class ScreenFragment : Fragment() {
    private var binding: FragmentScreenBinding? = null
    private val host get() = requireActivity() as MainActivity
    private val control get() = host.controller
    private val screen get() = arguments?.getString("screen") ?: "manual"
    private var live: TextView? = null
    private var events: TextView? = null
    private var homeHeatingLabel: TextView? = null
    private var homeReadyTemperature: TextView? = null
    private var homeReadings: View? = null
    private var homeRemaining: TextView? = null
    private var homeTimerRow: View? = null
    private var homeTarget: TextView? = null
    private var homeSetup: MaterialButton? = null
    private var homeReconnect: MaterialButton? = null
    private var homeReadyLinks: View? = null
    private var homeTools: View? = null
    private var homeTemperature:TextView?=null
    private var homeState:TextView?=null
    private var homeDetail:TextView?=null
    private var lightAction:MaterialButton?=null
    private var rapidAction:MaterialButton?=null
    private var draftRapid:SwitchMaterial?=null
    private var draftLight:SwitchMaterial?=null
    private var endTimeMinutes=UNCHANGED
    private var endTimeAction:MaterialButton?=null
    private var setupViewStep:SetupStep?=null
    private var setupMessage:TextView?=null
    private var homeStop:MaterialButton?=null
    private var cookingStart:MaterialButton?=null
    private var cookingConnect:MaterialButton?=null
    private var homeChangeTemperature:MaterialButton?=null
    private var homeTemperatureHint:TextView?=null
    private var homeAddTime:MaterialButton?=null
    private var homeTimeHint:TextView?=null
    private val thumbnails=android.util.LruCache<String,android.graphics.Bitmap>(32)
    private var editor: Recipe? = null
    private var editing = false
    private var showingRecipeDetails = false
    private var imagePath = ""
    private var cameraUri: android.net.Uri? = null
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { uri -> attempt { imagePath=control.recipes.importImage(uri); host.message("Zdjęcie dołączone") } }
    }
    private val guarded = mutableListOf<View>()
    private val rows = mutableListOf<StageFields>()
    private var draftContainer: LinearLayout? = null
    private var permissionAction: (() -> Unit)? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) permissionAction?.invoke() else host.message("Zezwolenie Wi-Fi jest potrzebne do połączenia lokalnego. Możesz je zmienić w ustawieniach aplikacji.")
        permissionAction = null
    }
    private val image = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attempt { imagePath = control.recipes.importImage(uri); host.message("Zdjęcie dołączone do przepisu") }
    }
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt { control.recipes.importZip(uri); host.message("Zaimportowano przepisy"); rebuild() }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) attempt { control.recipes.exportZip(uri); host.message("Zapisano ZIP") }
    }
    private var diagnosticsExport = ""

    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) attempt { requireContext().contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(diagnosticsExport) }; host.message("Zapisano diagnostykę") }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        binding = FragmentScreenBinding.inflate(inflater, container, false)
        return binding!!.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                binding?.body?.let { body ->
                    val width = minOf(right - left, dp(600))
                    if (width > 0 && body.layoutParams.width != width) {
                        body.layoutParams = body.layoutParams.apply { this.width = width }
                    }
                }
            }
        }
        rebuild()
        if(savedInstanceState != null) {
            if(savedInstanceState.getBoolean("editing")) {
                val previous=control.recipes.userPrograms().find { it.id == savedInstanceState.getString("editorId") }
                editRecipe(previous)
            }
            val count=savedInstanceState.getInt("stageCount",rows.size)
            if(screen in listOf("manual","stages") || editing) {
                while(rows.size < count.coerceIn(1,3)) addStage(CookingStage())
                while(rows.size > count.coerceIn(1,3)) { rows.removeAt(rows.lastIndex); draftContainer?.removeViewAt(draftContainer!!.childCount-1) }
            }
            imagePath=savedInstanceState.getString("imagePath").orEmpty()
            cameraUri=savedInstanceState.getString("cameraUri")?.let(android.net.Uri::parse)
            val fields=savedInstanceState.getStringArrayList("fields")
            val checks=savedInstanceState.getBooleanArray("checks")
            val selections=savedInstanceState.getIntArray("selections")
            if(fields != null) savedFields().forEachIndexed { i,f -> fields.getOrNull(i)?.let { f.setText(it) } }
            if(checks != null) controls().filterIsInstance<CheckBox>().forEachIndexed { i,c -> if(i < checks.size) c.isChecked=checks[i] }
            if(selections != null) controls().filterIsInstance<Spinner>().forEachIndexed { i,s -> if(i < selections.size) s.setSelection(selections[i]) }
            draftRapid?.isChecked=savedInstanceState.getBoolean("draftRapid",draftRapid?.isChecked ?: false)
            draftLight?.isChecked=savedInstanceState.getBoolean("draftLight",draftLight?.isChecked ?: true)
            endTimeMinutes=savedInstanceState.getInt("endTimeMinutes",UNCHANGED)
            updateEndTimeLabel()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("editing",editing); outState.putString("editorId",editor?.id)
        outState.putBoolean("draftRapid",draftRapid?.isChecked ?: false)
        outState.putBoolean("draftLight",draftLight?.isChecked ?: true)
        outState.putInt("endTimeMinutes",endTimeMinutes)
        outState.putInt("stageCount",rows.size); outState.putString("imagePath",imagePath); outState.putString("cameraUri",cameraUri?.toString())
        outState.putStringArrayList("fields",ArrayList(savedFields().map { it.text.toString() }))
        outState.putBooleanArray("checks",controls().filterIsInstance<CheckBox>().map { it.isChecked }.toBooleanArray())
        outState.putIntArray("selections",controls().filterIsInstance<Spinner>().map { it.selectedItemPosition }.toIntArray())
    }
    private fun controls(): List<View> {
        val result=mutableListOf<View>()
        fun walk(view: View) { result += view; if(view is ViewGroup) for(i in 0 until view.childCount) walk(view.getChildAt(i)) }
        binding?.body?.let(::walk)
        return result
    }
    private fun savedFields()=controls().filterIsInstance<EditText>().filter { it.inputType and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_PASSWORD }
    override fun onDestroyView() { binding = null; live = null; events=null; guarded.clear(); rows.clear(); super.onDestroyView() }

    private fun rebuild() {
        val body = binding?.body ?: return
        binding!!.actionDock.removeAllViews()
        binding!!.actionDock.visibility = View.GONE
        body.removeAllViews(); guarded.clear(); rows.clear(); live = null; editing=false; showingRecipeDetails=false
        events=null
        homeHeatingLabel=null;homeReadyTemperature=null;homeReadings=null;homeRemaining=null;homeTimerRow=null;homeTarget=null;homeSetup=null;homeReconnect=null;homeReadyLinks=null;homeTools=null;homeTemperature=null;homeState=null;homeDetail=null;lightAction=null;rapidAction=null;draftRapid=null;draftLight=null;endTimeAction=null;homeStop=null;cookingStart=null;cookingConnect=null;homeAddTime=null;homeTimeHint=null;homeChangeTemperature=null;homeTemperatureHint=null
        when (screen) {
            "home" -> home()
            "first_setup" -> firstSetup()
            "more" -> more()
            "manual", "stages" -> cooking()
            "monitor", "settings" -> monitor()
            "ready", "users" -> recipes()
            "history" -> history()
            "connection" -> connection()
            "connection_advanced" -> advancedConnection()
            "diagnostics" -> diagnostics()
            else -> help()
        }
        updateLive()
    }

    private fun text(value: String, parent: LinearLayout = binding!!.body, heading: Boolean = false): TextView =
        TextView(requireContext()).apply {
            text = value
            textSize = if (heading) 17f else 14f
            setTextColor(color(if (heading) R.color.oven_text else R.color.oven_muted))
            if (heading) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(if (heading) 6 else 2), 0, dp(if (heading) 5 else 10))
            parent.addView(this)
        }

    private fun header(title: String, intro: String? = null) {
        text(title, heading = true).apply {
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -.025f
            setPadding(0, dp(4), 0, dp(if (intro == null) 16 else 6))
        }
        intro?.let { text(it).setPadding(0, 0, 0, dp(16)) }
    }

    private fun actionIcon(label: String): Int = when {
        label.contains("QR", true) || label.contains("profil", true) -> R.drawable.ic_qr
        label.startsWith("Usuń") -> R.drawable.ic_trash
        label.startsWith("Zapisz") || label.startsWith("Zachowaj") -> R.drawable.ic_save
        label.contains("zdjęci", true) -> R.drawable.ic_photo
        label.startsWith("Dodaj etap") || label == "Dodaj przepis" -> R.drawable.ic_plus
        label.contains("histori", true) || label.contains("nastaw", true) && label.startsWith("Wczytaj") -> R.drawable.ic_history
        label.contains("Światło", true) -> R.drawable.ic_light
        label.contains("nagrzew", true) || label.contains("rozgrzew", true) -> R.drawable.ic_zap
        label.contains("blokad", true) -> R.drawable.ic_shield
        label.contains("godzin", true) || label.contains("zegar", true) -> R.drawable.ic_clock
        label.contains("Import") || label.contains("Eksport") -> R.drawable.ic_transfer
        label.contains("Wi-Fi") || label.contains("Połącz") || label.contains("połączen") || label.contains("SmartIN") -> R.drawable.ic_wifi
        label == "Anuluj" || label.startsWith("Wróć") -> R.drawable.ic_back
        label.contains("Diagnostyka") || label.contains("aplikacji") || label.contains("alarm", true) -> R.drawable.ic_info
        label.contains("etap", true) -> R.drawable.ic_layers
        label.contains("pieczeni", true) || label.contains("piekarnik", true) -> R.drawable.ic_heat
        else -> R.drawable.ic_nav_programs
    }

    private fun section(title: String): LinearLayout {
        val group=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }
        }
        binding!!.body.addView(group)
        text(title,group,true).apply {
            textSize=13f
            setTextColor(color(R.color.oven_accent))
            setPadding(0,dp(4),0,dp(8))
        }
        return group
    }

    private fun actionRow(label: String, description: String, parent: LinearLayout, action: () -> Unit): MaterialButton =
        button(label, parent = parent, action = action).apply {
            homeButtonStyle(this, actionIcon(label))
            text = homeLabel(label, description)
            contentDescription = label
            homeTrailing(this, R.drawable.ic_chevron)
        }

    private fun dockAction(label: String, action: () -> Unit): MaterialButton {
        binding!!.actionDock.removeAllViews()
        binding!!.actionDock.visibility = View.VISIBLE
        return button(label, parent = binding!!.actionDock, primary = true, action = action)
    }

    private fun button(
        label: String, needsDevice: Boolean = false, parent: LinearLayout = binding!!.body,
        primary: Boolean = false, danger: Boolean = false, action: () -> Unit
    ): MaterialButton = MaterialButton(requireContext()).apply {
        text = label
        isAllCaps = false
        letterSpacing = 0f
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(52)
        cornerRadius = dp(10)
        insetTop = 0; insetBottom = 0
        setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
        gravity = if (primary) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        icon = ContextCompat.getDrawable(requireContext(), actionIcon(label))
        iconSize = dp(18); iconPadding = dp(10)
        iconGravity = if (primary) MaterialButton.ICON_GRAVITY_TEXT_START else MaterialButton.ICON_GRAVITY_START
        val (fill, ink) = when {
            danger -> R.color.oven_danger_soft to R.color.oven_danger
            primary -> R.color.oven_accent to R.color.oven_on_accent
            else -> R.color.oven_neutral to R.color.oven_text
        }
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(color(R.color.oven_disabled_fill), color(fill)))
        val inkStates = ColorStateList(states, intArrayOf(color(R.color.oven_disabled_text), color(ink)))
        setTextColor(inkStates)
        iconTint = inkStates
        rippleColor = ColorStateList.valueOf(color(ink) and 0x00ffffff or 0x24000000)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
        setOnClickListener { attempt(action) }
        parent.addView(this)
        if (needsDevice) guarded += this
    }

    private fun field(label: String, value: String, numeric: Boolean = false, parent: LinearLayout = binding!!.body): EditText {
        val wrapper=TextInputLayout(requireContext()).apply {
            hint=label;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(10).toFloat(),dp(10).toFloat(),dp(10).toFloat(),dp(10).toFloat())
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf())
            setBoxStrokeColorStateList(ColorStateList(states, intArrayOf(
                color(R.color.oven_border), color(R.color.oven_accent), color(R.color.oven_input_border)
            )))
            setBoxBackgroundColorResource(R.color.oven_surface)
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(8) }
            parent.addView(this)
        }
        return TextInputEditText(wrapper.context).apply {
            setText(value); contentDescription = label; isSingleLine = true
            textSize=16f
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            wrapper.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    /** Form value: tapping opens the same native wheel used by live edits; no keyboard or device write. */
    private fun wheelField(label:String,value:String,valueUnit:String,range:()->IntRange,parent:LinearLayout):EditText {
        val input=field(label,value,true,parent)
        val wrapper=input.parent.parent as TextInputLayout
        input.isFocusable=false;input.isCursorVisible=false;input.showSoftInputOnFocus=false
        wrapper.suffixText=valueUnit
        fun choose() {
            if(!input.isEnabled) return
            val limits=range()
            val layout=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8)) }
            val picker=OvenWheelPicker(requireContext()).apply {
                minValue=limits.first;maxValue=limits.last;this.value=(input.text.toString().toIntOrNull() ?: limits.first).coerceIn(limits)
                this.unit=valueUnit
                contentDescription=label
                layoutParams=LinearLayout.LayoutParams(dp(160),dp(224)).apply { gravity=Gravity.CENTER_HORIZONTAL }
            }
            layout.addView(picker)
            val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle(label).setView(layout)
                .setNegativeButton("Anuluj",null).setPositiveButton("Ustaw ${picker.value} $valueUnit",null).create()
            picker.setOnValueChangedListener { _,_,_->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text="Ustaw ${picker.value} $valueUnit" }
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    picker.finish();input.setText(picker.value.toString());dialog.dismiss()
                }
            }
            dialog.setOnDismissListener { picker.finish() }
            dialog.show()
        }
        input.setOnClickListener { choose() }
        wrapper.endIconMode=TextInputLayout.END_ICON_CUSTOM
        wrapper.setEndIconDrawable(R.drawable.ic_chevron)
        wrapper.endIconContentDescription="Wybierz: $label"
        wrapper.setEndIconOnClickListener { choose() }
        return input
    }
    private fun check(label: String, checked: Boolean = false, parent: LinearLayout = binding!!.body): CheckBox = CheckBox(requireContext()).apply {
        text = label; isChecked = checked; minHeight = dp(48); textSize = 15f
        setTextColor(color(R.color.oven_text)); parent.addView(this)
    }
    private fun attempt(action: () -> Unit) { runCatching(action).onFailure { host.error(it.message ?: "Nie udało się wykonać operacji. Spróbuj ponownie.") } }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun color(id:Int)=ContextCompat.getColor(requireContext(),id)
    private fun card(parent: LinearLayout = binding!!.body, instrument: Boolean = false): LinearLayout {
        val shell = MaterialCardView(requireContext()).apply {
            radius = dp(if (instrument) 17 else 15).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.oven_surface))
            strokeColor = color(R.color.oven_border)
            strokeWidth = if (instrument) 0 else dp(1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        val inner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(14), dp(13), dp(14))
        }
        shell.addView(inner)
        parent.addView(shell)
        return inner
    }

    private fun homeLabel(title: String, subtitle: String): CharSequence {
        val value = android.text.SpannableString("$title\n$subtitle")
        value.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, 0)
        value.setSpan(android.text.style.RelativeSizeSpan(11f / 13f), title.length + 1, value.length, 0)
        value.setSpan(android.text.style.ForegroundColorSpan(color(R.color.oven_muted)), title.length + 1, value.length, 0)
        return value
    }

    private fun homeTrailing(view: MaterialButton, drawableRes: Int) {
        val arrow = ContextCompat.getDrawable(requireContext(), drawableRes)!!.mutate().apply {
            setBounds(0, 0, dp(16), dp(16))
            setTint(color(if (view === homeSetup) R.color.oven_on_accent else R.color.oven_muted))
        }
        view.setCompoundDrawablesRelative(view.compoundDrawablesRelative[0], null, arrow, null)
    }

    private fun homeButtonStyle(view: MaterialButton, iconRes: Int, primary: Boolean = false) {
        view.apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(if (primary) 52 else 53)
            minimumHeight = minHeight
            cornerRadius = dp(10)
            insetTop = 0; insetBottom = 0
            setPaddingRelative(dp(12), dp(9), dp(12), dp(9))
            icon = ContextCompat.getDrawable(requireContext(), iconRes)
            iconSize = dp(18)
            iconPadding = dp(9)
            iconGravity = if (primary) MaterialButton.ICON_GRAVITY_TEXT_START else MaterialButton.ICON_GRAVITY_START
            iconTint = ColorStateList.valueOf(color(if (primary) R.color.oven_on_accent else R.color.oven_muted))
            if (!primary) gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = dp(8) }
        }
    }

    private fun home() {
        val panel = card()
        (panel.parent as MaterialCardView).apply {
            radius = dp(15).toFloat()
            setCardBackgroundColor(color(R.color.oven_panel))
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = dp(10) }
        }
        panel.setPadding(dp(13), dp(16), dp(13), dp(16))
        homeHeatingLabel = text("Grzanie wyłączone", panel).apply {
            textSize = 12f; setTextColor(color(R.color.oven_success))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(7))
            setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(requireContext(), R.drawable.ic_check_circle)?.apply {
                setBounds(0, 0, dp(15), dp(15)); setTint(color(R.color.oven_success))
            }, null, null, null)
            compoundDrawablePadding = dp(6)
            compoundDrawablesRelative[0]?.setBounds(0, 0, dp(15), dp(15))
            setCompoundDrawablesRelative(compoundDrawablesRelative[0], null, null, null)
        }
        homeState = text("", panel, true).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 25f; letterSpacing = -.035f; setPadding(0, 0, 0, dp(5)) }
        homeReadyTemperature = text("", panel).apply { textSize = 13f; setPadding(0, 0, 0, 0) }
        val timerRow = LinearLayout(requireContext()).apply {
            orientation = if (resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            panel.addView(this)
        }
        homeTimerRow = timerRow
        val clock = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            timerRow.addView(this, if (timerRow.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        text("Pozostało", clock).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
        homeRemaining = text("—", clock).apply {
            textSize = 56f
            setTextColor(color(R.color.oven_accent))
            fontFeatureSettings = "tnum"
            setPadding(0, 0, 0, dp(4))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        homeAddTime = button("Dodaj czas", true, timerRow, primary = true) { chooseAdditionalTime() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        }
        val temperatures = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            panel.addView(this)
        }
        homeReadings = temperatures
        val chamber = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            temperatures.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        text("Komora", chamber).apply { textSize = 12f; setPadding(0, 0, 0, 0) }
        homeTemperature = text("—", chamber).apply {
            textSize = 23f; fontFeatureSettings = "tnum"; setTextColor(color(R.color.oven_text)); setPadding(0,0,0,dp(4))
        }
        val target = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            temperatures.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        homeTarget = text("", target).apply { textSize = 13f; fontFeatureSettings = "tnum";setPadding(0,0,0,dp(4)) }
        val largeText = resources.configuration.fontScale > 1.3f
        homeChangeTemperature = button("Zmień temperaturę", true, if (largeText) panel else temperatures) { chooseCookingTemperature() }.apply {
            textSize = 13f
            icon = null
            gravity = Gravity.CENTER
            setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(if (largeText) -1 else dp(106), -2).apply {
                if (!largeText) marginStart = dp(6)
            }
        }
        homeDetail = text("", panel).apply { textSize = 12f; setPadding(0, dp(5), 0, 0) }
        homeTemperatureHint = text("", panel)
        homeTimeHint = text("", panel)

        homeReconnect = button("Sprawdź połączenie", primary = true) { host.navigate("connection", "Połączenie") }
        homeSetup = button("Ustaw pieczenie", primary = true) { host.navigate("manual", "Ustaw pieczenie") }
        homeButtonStyle(homeSetup!!, R.drawable.ic_nav_programs, primary = true)
        homeButtonStyle(homeReconnect!!, R.drawable.ic_arrow_right, primary = true)
        val links = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            binding!!.body.addView(this)
        }
        homeReadyLinks = links
        val last = control.settings.history().firstOrNull()?.firstOrNull()
        val lastSummary = last?.let { "${Functions.name(it.function)} · ${if (it.temperature == UNCHANGED) "z programu" else "${it.temperature}°C"} · ${it.durationMinutes} min" }
            ?: "Brak zapisanych nastaw"
        val history = button("Ostatnie nastawy", parent = links) { host.navigate("history", "Ostatnie nastawy") }
        homeButtonStyle(history, R.drawable.ic_history)
        history.text = homeLabel("Ostatnie nastawy", lastSummary)
        homeTrailing(history, R.drawable.ic_chevron)
        val stages = button("Pieczenie w etapach", parent = links) {
            host.preparedRecipe = null
            host.preparedStages = null
            host.navigate("stages", "Pieczenie w etapach")
        }
        homeButtonStyle(stages, R.drawable.ic_layers)
        stages.text = homeLabel("Pieczenie w etapach", "Przygotuj do trzech etapów")
        homeTrailing(stages, R.drawable.ic_chevron)
        homeSetup!!.iconGravity = MaterialButton.ICON_GRAVITY_START
        homeTrailing(homeSetup!!, R.drawable.ic_arrow_right)
        val tools = LinearLayout(requireContext()).apply {
            isBaselineAligned = false
            orientation = if (resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            binding!!.body.addView(this)
        }
        homeTools = tools
        lightAction = button("Światło", true, tools) {
            val on = control.client.status.value?.light == 1
            lightAction?.isChecked = on
            host.send(OvenCommands.common(light = if (on) 0 else 100))
        }
        rapidAction = button("Szybki nagrzew", true, tools) {
            val rapid = control.client.status.value?.rapidHeating
            if (rapid != null && rapid != UNCHANGED) {
                val desired = rapid == 0
                rapidAction?.isChecked = !desired
                host.send(OvenCommands.stages(listOf(CookingStage(UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED)),
                    rapidHeating = if (desired) 100 else 0),
                    if (desired) "Włączyć szybki nagrzew? Piekarnik będzie nagrzewał się szybciej." else null, "Włącz")
            }
        }
        listOfNotNull(lightAction, rapidAction).forEachIndexed { index, tile ->
            tile.isCheckable = true
            homeButtonStyle(tile, if (index == 0) R.drawable.ic_light else R.drawable.ic_zap)
            tile.minHeight = dp(56)
            tile.minimumHeight = dp(56)
            tile.iconPadding = dp(8)
            tile.setPaddingRelative(dp(10), dp(8), dp(10), dp(8))
            tile.strokeWidth = dp(1)
            tile.strokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(R.color.oven_accent), android.graphics.Color.TRANSPARENT))
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
            tile.backgroundTintList = ColorStateList(states, intArrayOf(
                color(R.color.oven_disabled_fill), color(R.color.oven_neutral), color(R.color.oven_neutral)
            ))
            tile.setTextColor(ColorStateList(states, intArrayOf(
                color(R.color.oven_disabled_text), color(R.color.oven_accent), color(R.color.oven_text)
            )))
            tile.iconTint = ColorStateList(states, intArrayOf(
                color(R.color.oven_disabled_text), color(R.color.oven_accent), color(R.color.oven_muted)
            ))
            tile.layoutParams = if (tools.orientation == LinearLayout.HORIZONTAL) {
                LinearLayout.LayoutParams(0, -1, 1f).apply { if (index == 1) marginStart = dp(8) }
            } else LinearLayout.LayoutParams(-1, -2)
        }
        homeStop = button("Zakończ pieczenie", parent = binding!!.actionDock, danger = true) {
            host.send(OvenCommands.common(power = 0), "Zakończyć pieczenie? Grzanie zostanie wyłączone.", "Zakończ")
        }
    }
    private fun more() {
        header("Więcej", "Twój piekarnik, połączenie i ustawienia.")
        val profile=section("Piekarnik")
        actionRow("Połączenie z piekarnikiem", "Sieć domowa i profil urządzenia", profile) { host.navigate("connection","Połączenie") }
        actionRow("Udostępnij piekarnik przez QR", "Przenieś profil na drugi telefon", profile) { host.showConnectionQr() }
        actionRow("Dodaj piekarnik z kodu QR", "Zeskanuj profil działającego telefonu", profile) { host.scanConnectionQr() }
        val options=section("Preferencje")
        actionRow("Funkcje piekarnika", "Światło, blokada dzieci i zegar", options) { host.navigate("settings","Funkcje piekarnika") }
        actionRow("Ostatnie nastawy", "Wczytaj zapisane ustawienia pieczenia", options) { host.navigate("history","Ostatnie nastawy") }
        actionRow("Wygląd aplikacji", "Motyw systemowy, jasny lub ciemny", options) {
            val modes=listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,AppCompatDelegate.MODE_NIGHT_NO,AppCompatDelegate.MODE_NIGHT_YES)
            MaterialAlertDialogBuilder(requireContext()).setTitle("Wygląd aplikacji").setSingleChoiceItems(arrayOf("Jak w telefonie","Jasny","Ciemny"),modes.indexOf(control.settings.themeMode)) { dialog,index ->
                control.settings.themeMode=modes[index];AppCompatDelegate.setDefaultNightMode(modes[index]);dialog.dismiss()
            }.setNegativeButton("Zamknij",null).show()
        }
        val support=section("Pomoc i informacje")
        actionRow("Diagnostyka", "Stan urządzenia i historia połączenia", support) { host.navigate("diagnostics","Diagnostyka") }
        actionRow("O aplikacji", "Open Oven i wskazówki użytkowania", support) { host.navigate("help","O aplikacji") }
    }
    private fun number(f: EditText, range: IntRange, name: String): Int = (f.text.toString().toIntOrNull() ?: error("$name: wpisz liczbę")).also { require(it in range) { "$name: zakres ${range.first}–${range.last}" } }
    private fun chooseCookingTemperature() {
        check(host.canControl) { "Najpierw połącz piekarnik i odśwież jego stan." }
        val status=checkNotNull(control.client.status.value)
        val session=control.sessionToken
        require(CookingTemperatureChange.unavailableReason(status)==null) { CookingTemperatureChange.unavailableReason(status).orEmpty() }
        val range=CookingTemperatureChange.range(status)
        val layout=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val picker=OvenWheelPicker(requireContext()).apply {
            minValue=range.first;maxValue=range.last;value=status.targetTemperature
            unit="°C";contentDescription="Nowa temperatura pieczenia"
            layoutParams=LinearLayout.LayoutParams(dp(160),dp(224)).apply { gravity=Gravity.CENTER_HORIZONTAL }
        }
        val summary=TextView(requireContext()).apply { textSize=16f;gravity=Gravity.CENTER;setTextColor(color(R.color.oven_text));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(picker);layout.addView(summary)
        fun updateSummary() { summary.setTextColor(color(R.color.oven_text));summary.text="${if(status.currentStage>1) "Etap ${status.currentStage} · " else ""}${status.targetTemperature} → ${picker.value} °C\nCzas i funkcja grzania pozostaną bez zmian." }
        val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle("Zmień temperaturę").setView(layout)
            .setNegativeButton("Anuluj",null).setPositiveButton("Ustaw ${picker.value} °C",null).create()
        picker.setOnValueChangedListener { _,_,_->updateSummary();dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text="Ustaw ${picker.value} °C" }
        updateSummary()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.finish()
                runCatching { control.changeTemperature(picker.value,status.currentStage,status.function,session) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { summary.text=it.message ?: "Odśwież odczyt i wybierz temperaturę ponownie.";summary.setTextColor(color(R.color.oven_danger)) }
            }
        }
        dialog.setOnDismissListener { picker.finish() }
        dialog.show()
    }
    private fun chooseAdditionalTime() {
        check(host.canControl) { "Najpierw połącz piekarnik i odśwież jego stan." }
        val status=checkNotNull(control.client.status.value)
        val session=control.sessionToken
        require(CookingTimeExtension.unavailableReason(status,1)==null) { CookingTimeExtension.unavailableReason(status,1).orEmpty() }
        val layout=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val picker=OvenWheelPicker(requireContext()).apply {
            minValue=1;maxValue=360-status.remainingMinutes;value=5.coerceAtMost(maxValue)
            unit="min"
            contentDescription="Liczba dodatkowych minut"
            layoutParams=LinearLayout.LayoutParams(dp(160),dp(224)).apply { gravity=Gravity.CENTER_HORIZONTAL }
        }
        val summary=TextView(requireContext()).apply { textSize=16f;gravity=Gravity.CENTER;setTextColor(color(R.color.oven_text));setPadding(0,dp(8),0,dp(8));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(picker);layout.addView(summary)
        fun updateSummary() { summary.setTextColor(color(R.color.oven_text));summary.text="${if(status.currentStage>1) "Etap ${status.currentStage} · " else ""}Pozostało ${status.remainingMinutes} → ${status.remainingMinutes+picker.value} min" }
        val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle("Ile czasu dodać?").setView(layout)
            .setNegativeButton("Anuluj",null).setPositiveButton("Dodaj ${picker.value} min",null).create()
        picker.setOnValueChangedListener { _,_,_->updateSummary();dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text="Dodaj ${picker.value} min" }
        updateSummary()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.finish()
                runCatching { control.addMinutes(picker.value,status.currentStage,session) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { summary.text=it.message ?: "Odśwież odczyt i wybierz czas ponownie.";summary.setTextColor(color(R.color.oven_danger)) }
            }
        }
        dialog.setOnDismissListener { picker.finish() }
        dialog.show()
    }
    private fun rapidSwitch(parent:LinearLayout)=SwitchMaterial(requireContext()).apply {
        text="Szybki nagrzew";textSize=16f;minHeight=dp(56)
        setTextColor(color(R.color.oven_text));setPadding(dp(8),dp(8),dp(8),dp(8))
        layoutParams=LinearLayout.LayoutParams(-1,-2);parent.addView(this)
    }
    private fun updateEndTimeLabel() {
        endTimeAction?.text=if(endTimeMinutes==UNCHANGED)"Godzina zakończenia · nie ustawiono" else
            "Godzina zakończenia · %02d:%02d".format(java.util.Locale.ROOT,endTimeMinutes/60,endTimeMinutes%60)
    }
    private fun chooseEndTime() {
        val now=java.util.Calendar.getInstance()
        val picker=layoutInflater.inflate(R.layout.dialog_end_time,null) as TimePicker
        picker.setIs24HourView(true)
        picker.hour=if(endTimeMinutes==UNCHANGED)now.get(java.util.Calendar.HOUR_OF_DAY) else endTimeMinutes/60
        picker.minute=if(endTimeMinutes==UNCHANGED)now.get(java.util.Calendar.MINUTE) else endTimeMinutes%60
        MaterialAlertDialogBuilder(requireContext()).setTitle("Godzina zakończenia").setView(picker)
            .setNegativeButton("Anuluj",null).setNeutralButton("Bez godziny") { _,_->endTimeMinutes=UNCHANGED;updateEndTimeLabel() }
            .setPositiveButton("Ustaw") { _,_->picker.clearFocus();endTimeMinutes=picker.hour*60+picker.minute;updateEndTimeLabel() }.show()
    }
    private fun confirm(title: String, action: () -> Unit) { MaterialAlertDialogBuilder(requireContext()).setTitle(title).setNegativeButton("Anuluj", null).setPositiveButton("Potwierdzam") { _, _ -> attempt(action) }.show() }

    private inner class StageFields(val initial: CookingStage, parent: LinearLayout) {
        private val functionLabel = text("Funkcja grzania", parent).apply { textSize=12f; setPadding(0,0,0,dp(4)) }
        val function = Spinner(requireContext()).apply {
            minimumHeight = dp(52)
            setPadding(dp(8),0,dp(32),0)
            background = null
            contentDescription = "Funkcja grzania"
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, Functions.labels)
            setSelection((initial.function - 1).coerceIn(0, 11))
            val frame=FrameLayout(requireContext()).apply {
                background=android.graphics.drawable.GradientDrawable().apply { setColor(color(R.color.oven_neutral));cornerRadius=dp(10).toFloat() }
            }
            parent.addView(frame,LinearLayout.LayoutParams(-1,-2))
            frame.addView(this,FrameLayout.LayoutParams(-1,-2))
            frame.addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList=ColorStateList.valueOf(color(R.color.oven_muted))
                rotation=90f
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(dp(18),dp(18),Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd=dp(12) })
        }
        private val valueRow = LinearLayout(requireContext()).apply {
            orientation = if (resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isBaselineAligned = false
            parent.addView(this)
        }
        private fun valueColumn(last: Boolean): LinearLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            valueRow.addView(this, if (valueRow.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0,-2,1f).apply {
                if (last) marginStart=dp(8)
            } else LinearLayout.LayoutParams(-1,-2))
        }
        private val temperatureColumn=valueColumn(false)
        private val timeColumn=valueColumn(true)
        val temperature = wheelField("Temperatura komory (°C)", if (initial.temperature == UNCHANGED) "" else initial.temperature.toString(), "°C", { 30..when(selectedFunction()) { 8 -> 230;9 -> 240;else -> 280 } },temperatureColumn)
        val minutes = wheelField("Czas (min)", if (initial.durationMinutes == UNCHANGED) "" else initial.durationMinutes.toString(), "min", { 1..600 },timeColumn)
        val probe = check("Zakończ według termosondy", initial.probeTemperature != UNCHANGED, parent)
        val probeTemperature = wheelField("Temperatura termosondy (°C)", if (initial.probeTemperature == UNCHANGED) "80" else initial.probeTemperature.toString(), "°C", { 30..90 },parent)
        private fun chooseValues() {
            val layout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            fun column(label: String, description: String, input: EditText, limits: IntRange, unit: String): OvenWheelPicker? {
                if (!input.isEnabled) return null
                val group = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layout.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                }
                text(label, group).apply { textSize = 13f; gravity = Gravity.CENTER; setPadding(0,0,0,dp(4)) }
                return OvenWheelPicker(requireContext()).apply {
                    minValue = limits.first
                    maxValue = limits.last
                    value = (input.text.toString().toIntOrNull() ?: limits.first).coerceIn(limits)
                    contentDescription = description
                    group.addView(this, LinearLayout.LayoutParams(-1, dp(224)))
                }.also { it.unit = unit }
            }
            val temperatureWheel = column("Temperatura · °C", "Temperatura do ustawienia", temperature,
                30..when(selectedFunction()) { 8 -> 230; 9 -> 240; else -> 280 }, "°C")
            val timeWheel = column("Czas · min", "Czas do ustawienia", minutes, 1..600, "min")
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Temperatura i czas")
                .setView(ScrollView(requireContext()).apply { addView(layout) })
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Zapisz nastawy") { _, _ ->
                    temperatureWheel?.let { it.finish(); temperature.setText(it.value.toString()) }
                    timeWheel?.let { it.finish(); minutes.setText(it.value.toString()) }
                }.create()
            dialog.setOnDismissListener { temperatureWheel?.finish(); timeWheel?.finish() }
            dialog.show()
        }
        var functionChanged:(()->Unit)?=null
        private fun selectedFunction()=function.selectedItemPosition+1
        fun updateLimits() {
            val f=selectedFunction()
            val max=when(f) { 8 -> 230;9 -> 240;else -> 280 }
            temperature.isEnabled=f!=10
            (temperature.parent.parent as TextInputLayout).apply {
                isEndIconVisible=temperature.isEnabled
                helperText=if(f==10)"Rozmrażanie nie wymaga nastawy temperatury." else "30–$max °C"
            }
            temperature.text.toString().toIntOrNull()?.takeIf { it>max && it!=UNCHANGED }?.let { temperature.setText(max.toString()) }
            (minutes.parent.parent as TextInputLayout).isEndIconVisible=minutes.isEnabled
            (probeTemperature.parent.parent as TextInputLayout).isEndIconVisible=probeTemperature.isEnabled
        }
        init {
            (temperature.parent.parent as TextInputLayout).hint = "Temperatura"
            (minutes.parent.parent as TextInputLayout).hint = "Czas"
            (minutes.parent.parent as TextInputLayout).helperText = "1–600 min"
            listOf(temperature, minutes).forEach { field ->
                field.textSize=24f
                field.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                field.setOnClickListener { chooseValues() }
                (field.parent.parent as TextInputLayout).setEndIconOnClickListener { chooseValues() }
            }
            val probeBox=probeTemperature.parent.parent as View
            probeBox.visibility=if(probe.isChecked)View.VISIBLE else View.GONE
            probe.setOnCheckedChangeListener { _, checked -> probeBox.visibility=if(checked)View.VISIBLE else View.GONE }
            function.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { updateLimits();functionChanged?.invoke() }
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            }
            updateLimits()
        }
        fun read(checkHardware: Boolean = true): CookingStage {
            val f = function.selectedItemPosition + 1
            val t = if (initial.temperature == UNCHANGED && temperature.text.isBlank() || f == 10) UNCHANGED else number(temperature, 30..when(f) { 8 -> 230; 9 -> 240; else -> 280 }, "Temperatura")
            val duration = if (initial.durationMinutes == UNCHANGED && minutes.text.isBlank()) UNCHANGED else number(minutes, 1..600, "Czas")
            val p = if (probe.isChecked) number(probeTemperature, 30..90, "Termosonda") else UNCHANGED
            require(f != 10 || p == UNCHANGED) { "Rozmrażanie nie używa termosondy" }
            if (checkHardware && p != UNCHANGED) require((control.client.status.value?.sensorStatus ?: 0) and 16 != 0) { "Termosonda nie jest wykryta w aktualnym odczycie" }
            return CookingStage(f, t, p, duration)
        }
    }

    private fun cooking() {
        val recipe = if (screen == "stages") host.preparedRecipe else null
        header(recipe?.title ?: if (screen == "manual") "Ustaw pieczenie" else "Pieczenie w etapach",
            if (screen == "manual") "Wybierz funkcję, temperaturę i czas." else "Ułóż do trzech kolejnych etapów pieczenia.")
        val status = card()
        text("Połączenie z piekarnikiem", status, true).apply { textSize=13f; setTextColor(color(R.color.oven_accent)) }
        live = text("", status).apply { setPadding(0, dp(2), 0, dp(2)) }
        cookingConnect=button("Połącz ponownie", parent = status) { wifiPermission { control.settings.autoConnect=true;control.connectReal() } }
        draftContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; binding!!.body.addView(this) }
        val initial = if (screen == "stages") host.preparedStages ?: listOf(CookingStage()) else listOf(CookingStage())
        initial.forEach { addStage(it) }
        if (screen == "stages") {
            button("Dodaj etap (maks. 3)") { require(rows.size < 3) { "Maksymalnie trzy etapy" }; addStage(CookingStage()) }
            button("Usuń ostatni etap") { require(rows.size > 1) { "Co najmniej jeden etap" }; rows.removeAt(rows.lastIndex); draftContainer!!.removeViewAt(draftContainer!!.childCount - 1) }
        }
        val options = card()
        text("Opcje pieczenia", options, true)
        val rapid=rapidSwitch(options).apply {
            isChecked=recipe?.rapidHeating?.let { it==100 } ?: true
        }
        draftRapid=rapid
        fun updateRapidAvailability() {
            val defrost = rows.firstOrNull()?.function?.selectedItemPosition == 9
            rapid.isEnabled = !defrost
            if (defrost) rapid.isChecked = false
        }
        rows.firstOrNull()?.functionChanged = { updateRapidAvailability() }
        updateRapidAvailability()
        text("Szybki nagrzew zastosujemy po rozpoczęciu pieczenia. Nie dotyczy rozmrażania.", options)
        val light=SwitchMaterial(requireContext()).apply {
            text="Światło podczas pieczenia";textSize=16f;minHeight=dp(56);isChecked=true
            setTextColor(color(R.color.oven_text));setPadding(dp(8),dp(8),dp(8),dp(8))
            layoutParams=LinearLayout.LayoutParams(-1,-2);options.addView(this)
        }
        draftLight=light
        text("Światło włączymy dopiero po rozpoczęciu pieczenia.", options)
        endTimeAction=button("Godzina zakończenia · nie ustawiono", parent = options) { chooseEndTime() }
        updateEndTimeLabel()
        cookingStart = dockAction("Uruchom pieczenie") {
            val stages = rows.map { it.read() }
            val rapidHeating = if (rapid.isChecked && stages.first().function != 10) 100 else 0
            host.startCooking(stages, endTimeMinutes, rapidHeating, recipe, light.isChecked)
        }
        val save = card()
        text("Zachowaj nastawy", save, true)
        button("Zapisz nastawy do historii", parent = save) { control.remember(rows.map { it.read(false) }); host.message("Zapisano nastawy") }
        button("Zapisz jako program własny", parent = save) {
            val input = EditText(requireContext()).apply { hint="Nazwa programu" }
            MaterialAlertDialogBuilder(requireContext()).setTitle("Program własny").setView(input).setNegativeButton("Anuluj", null).setPositiveButton("Zapisz") { _, _ -> attempt { control.recipes.save(Recipe(UUID.randomUUID().toString(), input.text.toString(), "", "", rows.map { it.read(false) })); host.message("Zapisano program") } }.show()
        }
        auxiliary()
        control.noteCookingFormReady()
    }
    private fun addStage(stage: CookingStage) {
        val group = card(checkNotNull(draftContainer))
        text(if (screen == "manual") "Nastawy pieczenia" else "Etap ${rows.size + 1}", group, true)
        rows += StageFields(stage, group)
    }
    private fun auxiliary() {
        val actions = card()
        text("Sterowanie piekarnikiem", actions, true)
        lightAction=button("Włącz światło", true, actions) { host.send(OvenCommands.common(light=if(control.client.status.value?.light==1)0 else 100)) }
        button("Wyłącz piekarnik", true, actions, danger = true) { host.send(OvenCommands.common(power=0, clearAlarm=1), "Wyłączyć piekarnik i skasować alarm?","Wyłącz") }
        button("Skasuj alarm", true, actions) { host.send(OvenCommands.common(clearAlarm=1)) }
    }
    private fun monitor() {
        header(if(screen == "settings") "Funkcje i stan" else "Monitorowanie")
        val status = card()
        text("Aktualny stan", status, true)
        live = text("", status)
        button("Odśwież odczyt", parent = status, primary = true) { control.refresh() }
        auxiliary()
        val security = card()
        text("Blokada dzieci", security, true)
        button("Włącz blokadę dzieci", true, security) { host.send(OvenCommands.common(childLock=100)) }
        button("Wyłącz blokadę dzieci", true, security) { host.send(OvenCommands.common(childLock=0), "Wyłączyć blokadę dzieci?","Wyłącz") }
        fun settingsCommand(rapid: Int = UNCHANGED, catalyst: Int = UNCHANGED) {
            val s = control.client.status.value ?: error("Brak odczytu")
            host.send(OvenCommands.stages(listOf(CookingStage(UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED)), rapidHeating=rapid, catalyst=catalyst))
        }
        val features = card()
        text("Dodatkowe funkcje", features, true)
        button("Włącz szybki rozgrzew", true, features) { settingsCommand(rapid=100) }
        button("Wyłącz szybki rozgrzew", true, features) { settingsCommand(rapid=0) }
        button("Włącz katalizator", true, features) { settingsCommand(catalyst=100) }
        button("Wyłącz katalizator", true, features) { settingsCommand(catalyst=0) }
        button("Ustaw zegar telefonu w piekarniku", true, features) { val now = java.util.Calendar.getInstance(); host.send(OvenCommands.clock(now.get(java.util.Calendar.HOUR_OF_DAY)*60+now.get(java.util.Calendar.MINUTE)), "Ustawić aktualną godzinę telefonu?","Ustaw") }
    }

    private fun recipes() {
        val ready = screen == "ready"
        if (ready) header("Programy", "Wybierz program i sprawdź jego nastawy przed pieczeniem.")
        else header("Przepisy", "Twoje receptury i zapisane nastawy.")
        if (!ready) {
            button("Dodaj przepis",primary=true) { editRecipe(null) }
            button("Import i eksport przepisów") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Przenieś przepisy").setItems(arrayOf("Importuj plik ZIP","Eksportuj przepisy OpenOven")) { _, item ->
                    when(item){0->import.launch(arrayOf("application/zip","application/octet-stream"));1->export.launch("open-oven-recipes.zip")}
                }.show()
            }
        }
        val list = if(screen == "ready") control.recipes.readyPrograms() else control.recipes.userPrograms()
        if (list.isEmpty()) {
            val empty = card()
            text(if (ready) "Brak programów" else "Brak własnych przepisów", empty, true)
            if (!ready) text("Dodaj własny przepis lub wczytaj go z archiwum ZIP.", empty)
            return
        }
        val search=field(if (ready) "Szukaj programu" else "Szukaj przepisu", "")
        (search.parent.parent as TextInputLayout).apply {
            setStartIconDrawable(R.drawable.ic_search)
            endIconMode=TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconContentDescription("Wyczyść wyszukiwanie")
        }
        val results=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;binding!!.body.addView(this) }
        fun show(query:String) {
            results.removeAllViews()
            val matches=list.filter { it.title.contains(query,true) }
            if(matches.isEmpty() && list.isNotEmpty()) text("Brak pasujących wyników. Zmień lub usuń wpisany tekst.",results)
            matches.forEach { recipe -> recipeCard(recipe,results) }
        }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){show(s?.toString().orEmpty())}
            override fun afterTextChanged(s:android.text.Editable?){}
        });show("")
    }
    private fun recipeCard(recipe:Recipe,parent:LinearLayout) {
        val shell=MaterialCardView(requireContext()).apply {
            radius=dp(10).toFloat();cardElevation=0f;setCardBackgroundColor(color(R.color.oven_neutral))
            strokeColor=color(R.color.oven_border);strokeWidth=0
            isClickable=true;isFocusable=true;contentDescription="${recipe.title}. Otwórz szczegóły."
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6);bottomMargin=dp(6) }
            setOnClickListener { recipeDetails(recipe) }
        }
        val row=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12)) }
        val image=ImageView(requireContext()).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        var bitmap=if(recipe.ready)null else thumbnails.get(recipe.imagePath)
        if(bitmap==null && !recipe.ready && recipe.imagePath.isNotBlank()) {
            bitmap=runCatching {
                val input=if(recipe.imagePath.startsWith("asset:")) requireContext().assets.open(recipe.imagePath.removePrefix("asset:")) else java.io.File(recipe.imagePath).inputStream()
                input.use { android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply { inSampleSize=8 }) }
            }.getOrNull()
            if(bitmap!=null) thumbnails.put(recipe.imagePath,bitmap)
        }
        if(bitmap!=null) image.setImageBitmap(bitmap) else { image.setImageResource(if(recipe.ready)R.drawable.ic_nav_oven else R.drawable.ic_nav_recipes);image.imageTintList=ColorStateList.valueOf(color(R.color.oven_accent));image.setPadding(dp(12),dp(12),dp(12),dp(12)) }
        image.background=android.graphics.drawable.GradientDrawable().apply { setColor(color(R.color.oven_accent_soft));cornerRadius=dp(12).toFloat() };image.clipToOutline=true
        row.addView(image,LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginEnd=dp(12) })
        val description=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
        row.addView(description,LinearLayout.LayoutParams(0,-2,1f))
        text(recipe.title,description).apply { textSize=15f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(color(R.color.oven_text));setPadding(0,0,0,dp(2)) }
        val first = recipe.stages.firstOrNull()
        val summary = listOfNotNull(
            if(recipe.ready) "Program gotowy" else "${recipe.stages.size} ${if(recipe.stages.size==1) "etap" else "etapy"}",
            first?.temperature?.takeIf { it != UNCHANGED }?.let { "$it°C" },
            first?.durationMinutes?.takeIf { it != UNCHANGED }?.let { "$it min" }
        ).joinToString(" · ")
        text(summary,description).apply { textSize=13f;setPadding(0,0,0,0) }
        row.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(color(R.color.oven_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
        shell.addView(row);parent.addView(shell)
        ViewCompat.setAccessibilityDelegate(shell,object:androidx.core.view.AccessibilityDelegateCompat(){
            override fun onInitializeAccessibilityNodeInfo(hostView:View,info:androidx.core.view.accessibility.AccessibilityNodeInfoCompat){super.onInitializeAccessibilityNodeInfo(hostView,info);info.className=Button::class.java.name}
        })
    }
    fun returnToRecipeList(): Boolean {
        if (!showingRecipeDetails && !editing) return false
        rebuild()
        binding?.screenScroll?.scrollTo(0, 0)
        return true
    }

    private fun recipeDetails(recipe: Recipe) {
        val body = binding!!.body; body.removeAllViews(); binding!!.actionDock.removeAllViews(); binding!!.actionDock.visibility=View.GONE; guarded.clear(); live=null
        showingRecipeDetails = true
        binding!!.screenScroll.scrollTo(0, 0)
        header(recipe.title, recipe.description.takeIf { it.isNotBlank() })
        if(!recipe.ready && recipe.imagePath.isNotBlank()) {
            val bitmap = runCatching { if(recipe.imagePath.startsWith("asset:")) requireContext().assets.open(recipe.imagePath.removePrefix("asset:")).use { android.graphics.BitmapFactory.decodeStream(it) } else android.graphics.BitmapFactory.decodeFile(recipe.imagePath) }.getOrNull()
            if (bitmap != null) card().addView(ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Zdjęcie przepisu"
            }, LinearLayout.LayoutParams(-1, dp(176)))
        }
        if (recipe.ingredients.isNotBlank() || recipe.instructions.isNotBlank()) {
            val preparation = card()
            if (recipe.ingredients.isNotBlank()) {
                text("Składniki", preparation, true)
                text(recipe.ingredients, preparation)
            }
            if (recipe.instructions.isNotBlank()) {
                text("Przygotowanie", preparation, true)
                text(recipe.instructions, preparation)
            }
        }
        val settings = card()
        text("Nastawy pieczenia", settings, true)
        recipe.stages.forEachIndexed { i,s -> text("Etap ${i+1}: ${Functions.name(s.function)}, ${if(s.temperature == UNCHANGED) "wg programu" else "${s.temperature} °C"}, ${if(s.durationMinutes == UNCHANGED) "wg programu" else "${s.durationMinutes} min"}", settings) }
        dockAction("Przygotuj nastawy") { host.prepare(recipe) }
        button(if(recipe.ready) "Utwórz własną kopię" else "Edytuj") { editRecipe(if(recipe.ready) recipe.copy(id=UUID.randomUUID().toString(), ready=false, imagePath="") else recipe) }
        if(!recipe.ready) button("Usuń przepis", danger = true) { confirm("Usunąć ${recipe.title}?") { control.recipes.delete(recipe.id); rebuild() } }
        button("Wróć do listy") { rebuild() }
    }
    private fun editRecipe(recipe: Recipe?) {
        val body = binding!!.body; body.removeAllViews(); binding!!.actionDock.removeAllViews(); binding!!.actionDock.visibility=View.GONE; guarded.clear(); rows.clear(); live=null
        editing=true; editor=recipe; imagePath=recipe?.imagePath.orEmpty()
        binding!!.screenScroll.scrollTo(0, 0)
        header(if (recipe == null) "Nowy przepis" else "Edytuj przepis")
        val recipeFields = card()
        text("Opis i przygotowanie", recipeFields, true)
        val title = field("Nazwa", recipe?.title.orEmpty(), parent = recipeFields)
        val description = field("Opis", recipe?.description.orEmpty(), parent = recipeFields)
        val ingredients = field("Składniki", recipe?.ingredients.orEmpty(), parent = recipeFields).apply { isSingleLine=false; minLines=2 }
        val instructions = field("Przygotowanie", recipe?.instructions.orEmpty(), parent = recipeFields).apply { isSingleLine=false; minLines=3 }
        draftContainer=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
        (recipe?.stages ?: listOf(CookingStage())).forEach { addStage(it) }
        button("Dodaj etap") { require(rows.size < 3) { "Maksymalnie trzy etapy" }; addStage(CookingStage()) }
        button("Usuń ostatni etap") { require(rows.size > 1); rows.removeAt(rows.lastIndex); draftContainer!!.removeViewAt(draftContainer!!.childCount-1) }
        val photo = card()
        text("Zdjęcie przepisu", photo, true)
        button("Wybierz zdjęcie", parent = photo) { image.launch("image/*") }
        button("Zrób zdjęcie", parent = photo) {
            val directory=java.io.File(requireContext().cacheDir,"photos").apply { mkdirs() }
            val file=java.io.File.createTempFile("recipe-", ".jpg", directory)
            cameraUri=FileProvider.getUriForFile(requireContext(),requireContext().packageName+".files",file)
            camera.launch(cameraUri!!)
        }
        dockAction("Zapisz przepis") { control.recipes.save(Recipe(recipe?.id ?: UUID.randomUUID().toString(), title.text.toString(), description.text.toString(), ingredients.text.toString(), rows.map { it.read(false) }, instructions=instructions.text.toString(), imagePath=imagePath,rapidHeating=recipe?.rapidHeating ?:0)); rebuild() }
        button("Anuluj") { rebuild() }
    }
    private fun history() {
        header("Ostatnie nastawy", "Wróć do zapisanych ustawień pieczenia.")
        val history = control.settings.history()
        history.forEachIndexed { i, stages ->
            val entry = card()
            text("Nastawy ${i + 1}", entry, true)
            text(stages.joinToString("\n") { "${Functions.name(it.function)} · ${it.temperature} °C · ${it.durationMinutes} min" }, entry)
            button("Wczytaj nastaw ${i+1}", parent = entry, primary = true) { host.preparedRecipe=null; host.preparedStages=stages; host.navigate("stages", "Nastawy z historii") }
            button("Usuń nastaw ${i+1}", parent = entry) { control.settings.saveHistory(history.filterIndexed { index,_ -> index != i }); rebuild() }
        }
        if (history.isEmpty()) {
            val empty = card()
            text("Historia jest pusta", empty, true)
            text("Zapisz nastawy na ekranie przygotowania pieczenia.", empty)
            return
        }
        button("Usuń całą historię", danger = true) { confirm("Usunąć historię?") { control.settings.saveHistory(emptyList()); rebuild() } }
    }

    private fun wifiPermission(action: () -> Unit) {
        val needed = if(Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isEmpty()) action() else { permissionAction=action; permission.launch(missing.toTypedArray()) }
    }
    private fun connection() {
        header("Połącz piekarnik", "Telefon i piekarnik muszą być w tej samej sieci Wi-Fi.")
        val current = card()
        text(control.settings.ovenName, current, true)
        button("Połącz z piekarnikiem", parent = current, primary = true) {
            wifiPermission { control.settings.autoConnect = true; control.connectReal() }
        }
        val automatic = check("Łącz automatycznie", control.settings.autoConnect, current)
        automatic.setOnCheckedChangeListener { _, checked -> control.settings.autoConnect = checked }
        text("Przy utracie sieci aplikacja może wznowić połączenie. Polecenia pieczenia nie są powtarzane.", current)
        button("Rozłącz ten telefon", parent = current) { control.disconnect(); automatic.isChecked = false }

        val setup = card()
        text("Dodaj urządzenie lub telefon", setup, true)
        text("Masz już działający telefon? Użyj jego kodu QR, aby przenieść profil.", setup)
        actionRow("Skanuj kod QR", "Kod z telefonu, który już działa", setup) { host.scanConnectionQr() }
        actionRow("Wczytaj QR z obrazu", "Wybierz zapisany obraz z kodem", setup) { host.importConnectionQrImage() }
        actionRow("Udostępnij profil przez QR", "Dodaj drugi telefon domownika", setup) { host.showConnectionQr() }
        button(
            if (control.settings.setupAwaitingHome) "Dokończ dodawanie piekarnika" else "Dodaj piekarnik",
            parent = setup
        ) { host.navigate("first_setup", "Dodaj piekarnik") }

        val tools = card()
        text("Sieć i demonstracja", tools, true)
        actionRow("Wypróbuj demonstrację", "Poznaj aplikację bez łączenia z piekarnikiem", tools) { control.connectDemo(); host.navigate("home", "Piekarnik") }
        actionRow("Otwórz ustawienia Wi-Fi", "Sprawdź sieć tego telefonu", tools) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        actionRow("Zaawansowane ustawienia połączenia", "Adresy, porty i lokalny profil", tools) { host.navigate("connection_advanced", "Ustawienia zaawansowane") }
    }

    private fun firstSetup() {
        val progress=control.firstSetup.progress.value
        setupViewStep=progress.step
        header("Dodaj piekarnik")
        setupMessage = text(progress.message).apply {
            visibility = if (progress.message.isBlank()) View.GONE else View.VISIBLE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if(progress.busy) binding!!.body.addView(ProgressBar(requireContext()).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams=LinearLayout.LayoutParams(dp(40),dp(40)).apply { gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(12);bottomMargin=dp(12) }
        })
        when(progress.step) {
            SetupStep.IDLE,SetupStep.ERROR -> {
                val existing=card()
                text("Piekarnik już jest w domowym Wi-Fi",existing,true)
                text("Zamknij inne aplikacje sterujące piekarnikiem. Połącz ten telefon z domowym Wi-Fi. W menu Wi-Fi piekarnika wybierz ŁĄCZENIE → ON i zatwierdź OK. Gdy pojawi się licznik 05, rozpocznij sprawdzanie połączenia. W niektórych modelach okno parowania nazywa się WI.",existing)
                text("Ta droga nie zmienia sieci ani hasła piekarnika.",existing)
                button("Otworzyłem WI — połącz",parent=existing,primary=true) { wifiPermission { control.verifyFirstHomeConnection() } }
                val fresh=card()
                text("Pierwsza konfiguracja Wi-Fi",fresh,true)
                text("Włącz Wi-Fi piekarnika i otwórz WI / Łączenie-parowanie (instrukcja producenta: przytrzymaj ikonę termometru około 3 sekund, wybierz WI). Okno trwa około 5 minut. Gdy pojawi się sieć SmartIN, wybierz poniższy przycisk i zaakceptuj połączenie w Androidzie.",fresh)
                text("Jeśli piekarnik jest już w domowym Wi-Fi, użyj pierwszej ścieżki. Nie resetujemy ustawień urządzenia z aplikacji.",fresh)
                button("Połącz ze SmartIN",parent=fresh) { wifiPermission { control.connectSetupAccessPoint() } }
                button("Otwórz ustawienia Wi-Fi",parent=fresh) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            }
            SetupStep.AP_READY -> {
                val network = card()
                text("Domowa sieć Wi-Fi", network, true)
                text("Wpisz dane sieci, z której ma korzystać piekarnik. Hasła nie zapisujemy w aplikacji.", network)
                val ssid=field("Nazwa domowej sieci (SSID)", "", parent = network)
                val password=field("Hasło domowego Wi-Fi", "", parent = network).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                val awps=check("Używam trybu AWPS na piekarniku", parent = network)
                text("Wybierz AWPS tylko wtedy, gdy uruchomiłeś ten tryb na piekarniku. Transmisja UDP nie potwierdza odbioru.", network)
                button("Przekaż ustawienia do piekarnika", parent = network, primary = true) {
                    val method=if(awps.isChecked)SetupMethod.AWPS else SetupMethod.TCP
                    val credentials=WifiSetupCredentials(ssid.text.toString(),password.text.toString())
                    credentials.validate(method)
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Ustawić Wi-Fi piekarnika?")
                        .setMessage("Piekarnik otrzyma podane dane sieci. Po wysłaniu wrócimy do domowego Wi-Fi i sprawdzimy połączenie.")
                        .setNegativeButton("Anuluj",null).setPositiveButton("Przekaż ustawienia") { _,_->
                            control.configureFirstWifi(credentials.ssid,credentials.password,method);password.text.clear()
                        }.show()
                }
            }
            SetupStep.HOME_REQUIRED -> {
                val verification = card()
                text("Wróć do domowego Wi-Fi", verification, true)
                text("Połącz telefon z tą samą siecią co piekarnik. Sprawdzimy model i aktualny stan urządzenia.", verification)
                button("Otwórz domowe Wi-Fi", parent = verification) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                button("Sprawdź połączenie", parent = verification, primary = true) { wifiPermission { control.verifyFirstHomeConnection() } }
                button("Wróć do SmartIN", parent = verification) { wifiPermission { control.connectSetupAccessPoint() } }
            }
            SetupStep.COMPLETE -> {
                val success = card()
                text("Gotowe", success, true)
                text("Piekarnik odpowiada. Dla kolejnego telefonu udostępnij profil QR i korzystaj z jednego aktywnego telefonu naraz.", success)
                text("Jeśli działa odczyt, ale nie sterowanie, sprawdź ustawienia S i M w piekarniku. Instrukcja producenta wskazuje ON / 1 dla obu.", success)
                button("Przejdź do piekarnika", parent = success, primary = true) { host.navigate("home","Piekarnik") }
                button("Udostępnij profil przez QR", parent = success) { host.showConnectionQr() }
            }
            else -> text("Pozostaw aplikację otwartą. Jeśli Android zapyta o połączenie ze SmartIN bez internetu, zaakceptuj je.")
        }
        if(progress.step!=SetupStep.COMPLETE) button("Przerwij dodawanie") {
            if(progress.step !in setOf(SetupStep.IDLE,SetupStep.ERROR)) control.cancelFirstSetup()
            host.navigate("connection","Połączenie")
        }
    }
    private fun advancedConnection() {
        header("Połączenie lokalne", "Ustawienia zaawansowane dla domowej sieci Wi-Fi.")
        val settings=control.settings
        val addresses = card()
        text("Adresy i porty", addresses, true)
        text("Puste pola adresu telefonu i rozgłaszania pozwalają wykryć je automatycznie.", addresses)
        val automatic=check("Łącz przy otwarciu i ponawiaj po utracie sieci", settings.autoConnect, addresses)
        automatic.setOnCheckedChangeListener { _, checked -> settings.autoConnect=checked }
        val ip=field("Adres IPv4 telefonu (puste = wykryj Wi-Fi)", settings.ipAddress, parent = addresses)
        val tcp=field("Port nasłuchiwania TCP", settings.tcpPort.toString(), true, addresses)
        val udp=field("Port discovery UDP", settings.udpPort.toString(), true, addresses)
        val broadcast=field("Adres rozgłoszeniowy (puste = wykryj)", settings.broadcastAddress, parent = addresses)
        val device=field("Adres piekarnika (opcjonalnie)", settings.deviceAddress, parent = addresses)
        val identity=field("Tożsamość klienta", settings.identity, parent = addresses)
        button("Zapisz ustawienia", parent = addresses, primary = true) {
            val tcpValue=number(tcp,1..65535,"TCP"); val udpValue=number(udp,1..65535,"UDP")
            val clientId=identity.text.toString().trim()
            require(clientId.length==16 && clientId.all { it.code in 32..126 }) { "Tożsamość musi mieć 16 znaków ASCII." }
            fun address(value:String):String=value.trim().also { v ->
                require(v.isEmpty() || (v.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && v.split('.').all { it.toInt() in 0..255 })) { "Nieprawidłowy adres IPv4." }
            }
            val ipValue=address(ip.text.toString()); val broadcastValue=address(broadcast.text.toString()); val deviceValue=address(device.text.toString())
            settings.ipAddress=ipValue; settings.tcpPort=tcpValue; settings.udpPort=udpValue
            settings.broadcastAddress=broadcastValue; settings.deviceAddress=deviceValue; settings.identity=clientId; host.message("Zapisano ustawienia połączenia")
        }
        val session = card()
        text("Sesja i sieć", session, true)
        button("Połącz z piekarnikiem", parent = session) { wifiPermission { control.connectReal() } }
        button("Uruchom demonstrację offline", parent = session) { control.connectDemo() }
        button("Rozłącz", parent = session) { control.disconnect() }
        button("Otwórz ustawienia Wi-Fi", parent = session) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        val dark=check("Ciemny motyw", settings.darkTheme, session)
        dark.setOnCheckedChangeListener { _, checked -> settings.darkTheme=checked; AppCompatDelegate.setDefaultNightMode(if(checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
        text("Konfiguracja Wi-Fi urządzenia",heading=true)
        text("Kreator rozróżnia dodanie telefonu w istniejącej sieci i pierwszą konfigurację przez SmartIN. Samo dodanie telefonu nie wymaga zmiany hasła ani resetowania Wi-Fi.")
        button("Otwórz kreator dodawania") { host.navigate("first_setup","Dodaj piekarnik") }
    }
    private fun diagnostics() {
        header("Diagnostyka", "Status urządzenia i dziennik lokalnego połączenia.")
        button("Odśwież status", primary = true) { control.refresh() }
        button("Eksportuj diagnostykę bez danych prywatnych") { control.exportDiagnostics { diagnosticsExport=it; exportLog.launch("open-oven-diagnostics.txt") } }
        val status = card()
        text("Dane urządzenia", status, true)
        live = text("", status).apply { fontFeatureSettings = "tnum" }
        val log = card()
        text("Przebieg połączenia", log, true)
        events = text(control.diagnosticEvents(), log).apply {
            fontFeatureSettings = "tnum"; textSize=12f
            typeface=Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
    }
    private fun help() {
        header("O aplikacji", "Open Oven · lokalne sterowanie piekarnikiem")
        val local = card()
        text("Połączenie lokalne", local, true)
        text("Aplikacja działa w domowej sieci Wi-Fi i nie wymaga konta producenta. Demonstracja korzysta z symulatora.", local)
        text("Przy pierwszej sesji zamknij inne aplikacje sterujące piekarnikiem, połącz telefon z domowym Wi-Fi, a potem sprawdź odczyt i światło. Pierwszą próbę wykonaj bez włączania grzania.", local)
        val formats = card()
        text("Pliki i zgodność", formats, true)
        text("Importuj i eksportuj własne przepisy w formacie ZIP OpenOven.", formats)
    }
    fun updateLive() {
        if(binding == null) return
        if(screen=="first_setup") {
            val progress=control.firstSetup.progress.value
            if(setupViewStep!=progress.step) { rebuild();return }
            setupMessage?.apply {
                if (text != progress.message) text = progress.message
                visibility = if (progress.message.isBlank()) View.GONE else View.VISIBLE
            }
            return
        }
        events?.text=control.diagnosticEvents()
        guarded.forEach { it.isEnabled=host.canControl;it.alpha=if (it is MaterialButton || host.canControl) 1f else 0.6f }
        val s=control.client.status.value; val id=control.client.identity.value
        lightAction?.apply {
            text = if (screen == "home") homeLabel("Światło", if (s?.light == 1) "włączone" else "wyłączone")
                else if (s?.light == 1) "Wyłącz światło" else "Włącz światło"
            isChecked = host.canControl && s?.light == 1
            contentDescription = if (s?.light == 1) "Wyłącz światło" else "Włącz światło"
        }
        rapidAction?.isChecked=host.canControl && s!=null && s.rapidHeating!=0 && s.rapidHeating!=UNCHANGED
        rapidAction?.text=homeLabel("Szybki nagrzew", when {
            !host.canControl || s==null -> "brak odczytu"
            s.rapidHeating==UNCHANGED -> "stan nieznany"
            s.rapidHeating==0 -> "wyłączony"
            else -> "włączony"
        })
        if(s!=null && s.power>0 && s.controlMode==1) {
            lightAction?.isEnabled=false
            rapidAction?.apply { isEnabled=false;alpha=0.45f }
        }
        if(s?.rapidHeating==UNCHANGED) rapidAction?.apply { isEnabled=false;alpha=0.45f }
        if(screen=="home") {
            val reason=s?.let { CookingTimeExtension.unavailableReason(it,1) }
            homeAddTime?.apply {
                visibility=if(host.hasFreshStatus && s!=null && s.power>0)View.VISIBLE else View.GONE
                text=if(control.isAddingTime.value)"Dodaję czas…" else "Dodaj czas"
                isEnabled=host.canControl && reason==null && !control.isAddingTime.value && !control.isChangingTemperature.value
            }
            homeTimeHint?.apply {
                text=reason.orEmpty()
                visibility=if(s!=null && s.power>0 && host.canControl && reason!=null)View.VISIBLE else View.GONE
            }
            val temperatureReason=s?.let { CookingTemperatureChange.unavailableReason(it) }
            homeChangeTemperature?.apply {
                visibility=if(host.hasFreshStatus && s!=null && s.power>0)View.VISIBLE else View.GONE
                text=if(control.isChangingTemperature.value)"Zmieniam temperaturę…" else "Zmień temperaturę"
                isEnabled=host.canControl && temperatureReason==null && !control.isChangingTemperature.value && !control.isAddingTime.value
            }
            homeTemperatureHint?.apply {
                text=temperatureReason.orEmpty()
                visibility=if(s!=null && s.power>0 && host.canControl && temperatureReason!=null)View.VISIBLE else View.GONE
            }
            val fresh = host.hasFreshStatus
            val cooking = fresh && s != null && s.power > 0
            homeHeatingLabel?.visibility = if (fresh && !cooking) View.VISIBLE else View.GONE
            homeReadyTemperature?.apply {
                visibility = if (fresh && !cooking) View.VISIBLE else View.GONE
                text = "Temperatura komory ${s?.temperature?.takeIf { it != UNCHANGED }?.let { "$it°C" } ?: "nieznana"}"
            }
            homeReadings?.visibility = if (cooking) View.VISIBLE else View.GONE
            homeState?.textSize = if (cooking) 17f else 25f
            homeDetail?.visibility = if (!fresh || cooking || !host.canControl || (s?.temperature ?: 0) > 60) View.VISIBLE else View.GONE
            homeTimerRow?.visibility = if (cooking) View.VISIBLE else View.GONE
            homeRemaining?.text = if (s?.remainingTimeReliable == true && s.remainingMinutes in 0..360) {
                android.text.SpannableString("${s.remainingMinutes} min").apply {
                    setSpan(android.text.style.AbsoluteSizeSpan(20, true), indexOf(' ') + 1, length, 0)
                }
            } else "—"
            homeRemaining?.contentDescription = if (cooking && s != null) CookingTimeDisplay.describe(s) else null
            homeTarget?.apply {
                visibility = if (cooking) View.VISIBLE else View.GONE
                text = if (cooking && s!!.targetTemperature != UNCHANGED) "Nastawa\n${s.targetTemperature} °C" else "Nastawa nieznana"
            }
            homeSetup?.visibility = if (cooking) View.GONE else View.VISIBLE
            homeReconnect?.visibility = if (fresh) View.GONE else View.VISIBLE
            homeReadyLinks?.visibility = if (fresh && !cooking) View.VISIBLE else View.GONE
            homeTools?.visibility = if (fresh) View.VISIBLE else View.GONE
            binding!!.actionDock.visibility = if (cooking) View.VISIBLE else View.GONE
            homeTemperature?.text=s?.takeIf { host.hasFreshStatus }?.temperature?.takeIf { it!=UNCHANGED }?.let { "$it°" } ?: "—"
            homeState?.text=when {
                !host.hasFreshStatus -> if(control.client.connection.value.state in listOf(ConnectionState.LISTENING,ConnectionState.AUTHENTICATING)) "Łączę z piekarnikiem…" else "Stan piekarnika nieznany"
                !host.canControl -> "Piekarnik · tryb odczytu"
                s!=null && s.power>0 -> "Pieczenie w toku"
                else -> if(s!=null && s.temperature>60) "Piekarnik nadal gorący" else "Gotowy do ustawienia"
            }
            homeDetail?.text=when {
                !host.hasFreshStatus -> "Brak aktualnego odczytu. Sprawdź połączenie z piekarnikiem."
                !host.canControl -> "Ten model nie ma jeszcze obsługi sterowania. Stan urządzenia znajdziesz w diagnostyce."
                s!=null && s.power>0 -> Functions.name(s.function) + "\n" + (if (s.elapsedMinutes in 0 until UNCHANGED) "Upłynęło ${s.elapsedMinutes} min" else "Czas upływu nieznany")
                s!=null && s.temperature>60 -> "Grzanie wyłączone. Piekarnik nadal jest gorący."
                else -> "Grzanie wyłączone"
            }
            homeStop?.apply {
                visibility=if(host.hasFreshStatus && s!=null && s.power>0)View.VISIBLE else View.GONE
                isEnabled=host.canControl
            }
            return
        }
        if(screen in listOf("manual","stages","monitor","settings")) {
            cookingConnect?.apply {
                visibility=if(host.hasFreshStatus)View.GONE else View.VISIBLE
                val connecting=control.client.connection.value.state in setOf(ConnectionState.LISTENING,ConnectionState.AUTHENTICATING)
                isEnabled=!connecting;text=if(connecting)"Łączenie z piekarnikiem…" else "Połącz ponownie"
            }
            cookingStart?.apply { isEnabled=!control.isStarting.value;text=if(control.isStarting.value)"Rozpoczynam pieczenie…" else "Uruchom pieczenie" }
            live?.text=if(s==null || !host.hasFreshStatus) "Brak aktualnego odczytu. Możesz przygotować nastawy; przed rozpoczęciem połącz piekarnik." else if(!host.canControl) "Połączono · ten model udostępnia tylko odczyt. Nastawy nie mogą zostać wysłane." else
                "Komora ${s.temperature} °C · ${if(s.power>0) "pieczenie w toku" else "grzanie wyłączone"}\n${if(s.light==1) "Światło włączone" else "Światło wyłączone"}"
            return
        }
        live?.text=if(s == null) "Brak odczytu. Połącz urządzenie lub uruchom demonstrację." else buildString {
            append("${if(host.demo) "DEMONSTRACJA" else "Piekarnik"} • ${if(host.hasFreshStatus) "aktualny odczyt" else "nieaktualny odczyt"}\n")
            append("Komora: ${s.temperature} °C • cel: ${s.targetTemperature} °C\nTermosonda: ${s.probeTemperature} °C • cel: ${s.targetProbeTemperature} °C\n")
            append("${Functions.name(s.function)} • etap ${s.currentStage}\n${CookingTimeDisplay.describe(s)} • koniec: ${s.endMinutes}\n")
            append("Zasilanie: ${s.power}, program: ${s.program}, światło: ${s.light}\nDrzwi: ${s.door}, blokada drzwi: ${s.doorLock}, dzieci: ${s.childLock}\nWoda: ${s.water}, katalizator: ${s.catalyst}, rozgrzew: ${s.rapidHeating}\nBłędy: ${s.errors}, alarmy: ${s.alarms}\n")
            if(screen == "diagnostics") append("Model: ${id?.deviceId}, firmware: ${id?.softwareVersion}, hardware: ${id?.hardwareVersion}\nPanel: ${id?.panel}, moduł: ${id?.module}, konfiguracja: ${id?.configuration}\nSensory: ${s.sensorStatus}, sonda: ${s.probeStatus}, AOD: ${s.aod}\nWi-Fi: ${s.wifiControl}, błędy Wi-Fi: ${s.wifiErrors}, zapis: ${s.writeErrors}\nPółka: ${s.shelf}, tryb: ${s.controlMode}, para: ${s.steamCounter}\nAktualizacja: ${s.updateStatus}/${s.updateProgress}\nNieznane wartości są surowymi kodami, nie interpretacją możliwości urządzenia.")
        }
    }
    companion object { fun create(screen: String) = ScreenFragment().apply { arguments=Bundle().apply { putString("screen",screen) } } }
}

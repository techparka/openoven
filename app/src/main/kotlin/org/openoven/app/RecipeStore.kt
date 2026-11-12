package org.openoven.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.openoven.core.CookingStage
import org.openoven.core.UNCHANGED
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A local recipe or a built-in starting point for editable settings. */
data class Recipe(
    val id: String, val title: String, val description: String, val ingredients: String,
    val stages: List<CookingStage>, val ready: Boolean = false,
    val instructions: String = "", val imagePath: String = "",
    val rapidHeating: Int = 0
)

class RecipeStore(private val context: Context) {
    private val directory = File(context.filesDir, "recipes").apply { mkdirs() }
    private val photos = File(context.filesDir, "recipe-photos").apply { mkdirs() }
    private val database = File(directory, "recipes.json")
    private val lock = Any()

    fun readyPrograms(): List<Recipe> = listOf(
        preset("traditional", "Pieczenie tradycyjne", CookingStage(1, 180, UNCHANGED, 25)),
        preset("fan", "Pieczenie z termoobiegiem", CookingStage(6, 165, UNCHANGED, 30)),
        preset("crisp", "Zapiekanie z dolną grzałką", CookingStage(7, 195, UNCHANGED, 15)),
        preset("two-stage", "Pieczenie i dopiekanie", CookingStage(1, 175, UNCHANGED, 25),
            CookingStage(6, 190, UNCHANGED, 5))
    )

    private fun preset(id: String, title: String, vararg stages: CookingStage): Recipe =
        Recipe("preset-$id", title, "Dopasuj temperaturę i czas do potrawy przed startem.", "",
            stages.toList(), ready = true)

    fun userPrograms(): List<Recipe> = synchronized(lock) {
        if (!database.exists()) emptyList() else readArray(JSONArray(database.readText(Charsets.UTF_8)))
    }

    fun save(recipe: Recipe) = synchronized(lock) {
        require(!recipe.ready) { "Program gotowy jest tylko do odczytu; utwórz własną kopię." }
        validate(recipe)
        write(userPrograms().filterNot { it.id == recipe.id } + recipe)
    }

    fun delete(id: String) = synchronized(lock) { write(userPrograms().filterNot { it.id == id }) }

    fun importImage(uri: Uri): String {
        val target = File(photos, UUID.randomUUID().toString() + ".img")
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "Nie można otworzyć obrazu." }
                target.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        total += n; require(total <= 8 * 1024 * 1024) { "Zdjęcie przekracza 8 MB." }; out.write(buffer, 0, n)
                    }
                }
            }
            return target.absolutePath
        } catch (e: Exception) { target.delete(); throw e }
    }

    /** Versioned Open Oven ZIP. Validate completely before updating the local database. Never extract entry paths. */
    fun importZip(uri: Uri) = synchronized(lock) {
        val entries = mutableMapOf<String, ByteArray>()
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Nie można odczytać ZIP." }
        ZipInputStream(input).use { zip ->
            var total = 0
            while (true) {
                val e = zip.nextEntry ?: break
                require(entries.size < 256) { "Za dużo plików w ZIP." }
                require(!e.name.startsWith('/') && !e.name.contains("..") && !e.name.contains('\\')) { "Nieprawidłowa nazwa pliku ZIP." }
                if (!e.isDirectory) {
                    val data = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= 24 * 1024 * 1024) { "ZIP przekracza limit 24 MB po rozpakowaniu." }
                        data.write(buffer, 0, n)
                    }
                    require(e.name !in entries) { "Powtórzona nazwa w ZIP." }
                    entries[e.name] = data.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val raw = requireNotNull(entries["open-oven-recipes.json"]) { "Wybierz archiwum ZIP wyeksportowane z OpenOven." }
        val document = JSONObject(raw.toString(Charsets.UTF_8))
        require(document.getInt("version") == 1) { "Nieobsługiwana wersja eksportu." }
        val imported = readArray(document.getJSONArray("recipes"))
        require(imported.size <= 100) { "Maksymalnie 100 przepisów w jednym imporcie." }
        imported.forEach(::validate)
        val prepared = imported.map { recipe ->
            val photo = entries["images/${recipe.id}.img"]
            val path = if (photo != null) File(photos, UUID.randomUUID().toString() + ".img").also { it.writeBytes(photo) }.absolutePath else ""
            recipe.copy(id = UUID.randomUUID().toString(), ready = false, imagePath = path)
        }
        write(userPrograms() + prepared)
    }

    fun exportZip(uri: Uri) = synchronized(lock) {
        val recipes = userPrograms()
        val out = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Nie można zapisać ZIP." }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("open-oven-recipes.json"))
            val data = JSONObject().put("version", 1).put("recipes", array(recipes.map { it.copy(imagePath = "") }))
            zip.write(data.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            recipes.forEach { recipe ->
                val f = File(recipe.imagePath)
                if (f.isFile && f.canonicalPath.startsWith(photos.canonicalPath + File.separator)) {
                    zip.putNextEntry(ZipEntry("images/${recipe.id}.img")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }

    private fun validate(r: Recipe) {
        require(r.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Nieprawidłowy identyfikator przepisu." }
        require(r.title.isNotBlank() && r.title.length <= 120) { "Nazwa musi mieć od 1 do 120 znaków." }
        require(r.description.length + r.ingredients.length + r.instructions.length <= 100_000) { "Opis przepisu jest zbyt długi." }
        require(r.stages.size in 1..3) { "Przepis musi zawierać od 1 do 3 etapów." }
        r.stages.forEach {
            require(it.function in 1..12 && (it.temperature in 30..280 || it.temperature == UNCHANGED) && (it.durationMinutes in 1..600 || it.durationMinutes == UNCHANGED) && (it.probeTemperature == UNCHANGED || it.probeTemperature in 30..90)) { "Nieprawidłowe nastawy etapu." }
        }
    }

    private fun write(recipes: List<Recipe>) {
        val temporary = File(directory, "recipes.tmp")
        temporary.writeText(array(recipes).toString(2), Charsets.UTF_8)
        require(temporary.renameTo(database)) { "Nie można zapisać przepisów." }
    }

    private fun array(recipes: List<Recipe>) = JSONArray().apply { recipes.forEach { put(toJson(it)) } }
    private fun toJson(r: Recipe) = JSONObject().put("id", r.id).put("title", r.title).put("description", r.description)
        .put("ingredients", r.ingredients).put("instructions", r.instructions).put("imagePath", r.imagePath).put("rapid", r.rapidHeating)
        .put("stages", JSONArray().apply { r.stages.forEach { put(JSONObject().put("function", it.function).put("temperature", it.temperature).put("probe", it.probeTemperature).put("minutes", it.durationMinutes)) } })
    private fun readArray(a: JSONArray): List<Recipe> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val stages = o.getJSONArray("stages")
        Recipe(o.getString("id"), o.getString("title"), o.optString("description"), o.optString("ingredients"),
            (0 until stages.length()).map { j -> stages.getJSONObject(j).let { CookingStage(it.getInt("function"), it.getInt("temperature"), it.optInt("probe", UNCHANGED), it.getInt("minutes")) } },
            instructions = o.optString("instructions"), imagePath = o.optString("imagePath"), rapidHeating = o.optInt("rapid"))
    }
}

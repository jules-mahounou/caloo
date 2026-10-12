package com.caloo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_URL = "http://10.8.0.1:8053"
        const val PICK_ZIP = 1
        val HINTS = mapOf(
            "site" to "Sert ton site HTML/CSS/JS. Cible : vide. Envoie le site en .zip après l'enregistrement.\nOptions : {\"spa\": true} pour les applis React/Vue.",
            "proxy" to "Transmet à un autre serveur web.\nCible : http://192.168.1.20:3000",
            "redirect" to "Redirige le navigateur vers une autre adresse.\nCible : https://exemple.com\nOptions : {\"keepPath\": false, \"permanent\": true}",
            "ip" to "Le domaine pointe vers une machine.\nCible : 192.168.1.20",
            "dns" to "Demande à un autre serveur DNS.\nCible : 1.1.1.1, 8.8.8.8",
            "block" to "Bloque le domaine. Cible : vide.",
        )
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var actions = listOf("site", "proxy", "redirect", "ip", "dns", "block")
    private var pendingSite: String? = null

    // Palette resolue au demarrage plutot que figee, pour suivre le mode sombre du systeme.
    // NAVY designe la couleur du texte fort : sombre sur fond clair, clair sur fond sombre.
    private var NAVY = 0; private var TEAL = 0; private var GREY = 0; private var RED = 0
    private var BG = 0; private var SURFACE = 0; private var BORDER = 0

    private fun applyPalette() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        TEAL = Color.parseColor("#2BB3A3")
        NAVY = Color.parseColor(if (night) "#E8EDF5" else "#10203A")
        GREY = Color.parseColor(if (night) "#9AA4B2" else "#6B7280")
        RED = Color.parseColor(if (night) "#FF7A7A" else "#D9534F")
        BG = Color.parseColor(if (night) "#0D1726" else "#FFFFFF")
        SURFACE = Color.parseColor(if (night) "#16233A" else "#F5F7FA")
        BORDER = Color.parseColor(if (night) "#243249" else "#E3E8EF")
    }

    private val api get() = Api(prefs.getString("url", DEFAULT_URL)!!, prefs.getString("token", "")!!)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("caloo", MODE_PRIVATE)
        pendingSite = savedInstanceState?.getString("pendingSite")

        applyPalette()
        val root = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }
        status = text("", 14f).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        root.addView(card(status))
        root.addView(spacer(dp(16)))
        root.addView(row(primary("Actualiser") { refresh() }, primary("+ Ajouter") { edit(null) }))
        root.addView(spacer(dp(8)))
        root.addView(row(button("Journal DNS") { showLog() }, button("Réglages") { settings() }))
        root.addView(spacer(dp(8)))
        root.addView(row(button("WireGuard") { openWireGuard() }, button("Point d'accès") { openHotspot() }))
        root.addView(sectionTitle("EXTENSIONS"))
        list = vertical()
        root.addView(list)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(root)
        })

        if (prefs.getString("token", "").isNullOrEmpty()) settings() else refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingSite", pendingSite)
    }

    // ------------------------------------------------------------------ données

    private fun refresh() {
        status.text = "Connexion à Caloo…"
        status.setTextColor(GREY)
        bg({
            JSONObject(api.call("GET", "/api/status")) to JSONArray(api.call("GET", "/api/extensions"))
        }, onError = { e ->
            status.text = "● Hors ligne : ${e.message}\nVérifie que le tunnel WireGuard est activé et les réglages corrects."
            status.setTextColor(RED)
        }) { (s, exts) ->
            s.optJSONArray("actions")?.let { a -> actions = List(a.length()) { a.getString(it) } }
            val errors = s.optJSONArray("errors")
            val errText = if (errors != null && errors.length() > 0) {
                "\n⚠ " + List(errors.length()) { errors.getString(it) }.joinToString("\n⚠ ")
            } else ""
            status.text = "● Connecté · Caloo ${s.optString("version")} · ${s.optInt("extensions")} extension(s) · " +
                "${s.optLong("queries")} requêtes DNS$errText"
            status.setTextColor(if (errText.isEmpty()) TEAL else RED)
            render(exts)
        }
    }

    private fun render(exts: JSONArray) {
        list.removeAllViews()
        if (exts.length() == 0) list.addView(
            card(text("Aucune extension pour l'instant.\nAppuie sur « + Ajouter » pour en créer une.", 14f)
                .apply { setTextColor(GREY); setLineSpacing(dp(3).toFloat(), 1f) }),
        )
        for (i in 0 until exts.length()) {
            val e = exts.getJSONObject(i)
            val enabled = e.optBoolean("enabled", true)
            val domains = e.optJSONArray("domains")?.let { d -> List(d.length()) { d.getString(it) } } ?: emptyList()
            val target = e.optString("target")
            val card = vertical().apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(SURFACE)
                    setStroke(dp(1), if (enabled) TEAL else BORDER)
                }
                alpha = if (enabled) 1f else 0.6f
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
                isClickable = true
                setOnClickListener { edit(e) }
            }
            card.addView(text(e.getString("name") + if (enabled) "" else "  (désactivée)", 16f, bold = true).apply { setTextColor(NAVY) })
            card.addView(text(e.getString("action") + if (target.isNotEmpty()) " → $target" else "", 14f).apply { setTextColor(TEAL) })
            card.addView(text(domains.joinToString("  "), 13f).apply { setTextColor(GREY) })
            list.addView(card)
        }
    }

    // ------------------------------------------------------------------ édition

    private fun edit(ext: JSONObject?) {
        val isNew = ext == null
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val name = field(form, "Nom (minuscules, chiffres, - et _)", ext?.optString("name") ?: "").apply { isEnabled = isNew }
        val domains = field(
            form, "Domaines (séparés par des virgules, *.domaine pour les sous-domaines)",
            ext?.optJSONArray("domains")?.let { d -> List(d.length()) { d.getString(it) }.joinToString(", ") } ?: "",
        )
        form.addView(label("Action"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actions)
            setSelection(maxOf(0, actions.indexOf(ext?.optString("action") ?: "site")))
        }
        form.addView(spinner)
        val hint = text("", 12f).apply { setTextColor(GREY) }
        form.addView(hint)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                hint.text = HINTS[actions[pos]] ?: ""
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val target = field(form, "Cible", ext?.optString("target") ?: "")
        val priority = field(form, "Priorité (la plus haute gagne)", (ext?.optInt("priority") ?: 0).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val options = field(form, "Options (JSON)", ext?.optJSONObject("options")?.toString() ?: "{}")
        val enabled = CheckBox(this).apply { text = "Activée"; isChecked = ext?.optBoolean("enabled", true) ?: true }
        form.addView(enabled)
        if (!isNew && ext!!.optString("action") == "site") {
            val hasSite = ext.optBoolean("hasSite")
            form.addView(button(if (hasSite) "Remplacer le site (.zip)" else "Envoyer le site (.zip)") { pickZip(ext.getString("name")) })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Nouvelle extension" else ext!!.getString("name"))
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .apply { if (!isNew) setNeutralButton("Supprimer") { _, _ -> confirmDelete(ext!!.getString("name")) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val opts = try { JSONObject(options.text.toString().ifBlank { "{}" }) } catch (e: Exception) {
                    return@setOnClickListener toast("Options : JSON invalide")
                }
                val n = name.text.toString().trim().lowercase()
                val action = actions[spinner.selectedItemPosition]
                val body = JSONObject()
                    .put("enabled", enabled.isChecked)
                    .put("priority", priority.text.toString().toIntOrNull() ?: 0)
                    .put("domains", JSONArray(domains.text.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }))
                    .put("action", action)
                    .put("target", target.text.toString().trim())
                    .put("ttl", ext?.optInt("ttl", 60) ?: 60)
                    .put("options", opts)
                bg({ api.call("PUT", "/api/extensions/${Uri.encode(n)}", body) }) {
                    dialog.dismiss()
                    toast("Enregistré")
                    refresh()
                    if (isNew && action == "site") askUpload(n)
                }
            }
        }
        dialog.show()
    }

    private fun askUpload(name: String) {
        AlertDialog.Builder(this)
            .setMessage("Envoyer maintenant le site de « $name » ?\nChoisis un .zip contenant index.html.")
            .setPositiveButton("Choisir un .zip") { _, _ -> pickZip(name) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setMessage("Supprimer l'extension « $name » et son site ?")
            .setPositiveButton("Supprimer") { _, _ ->
                bg({ api.call("DELETE", "/api/extensions/${Uri.encode(name)}") }) { toast("Supprimée"); refresh() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickZip(name: String) {
        pendingSite = name
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        startActivityForResult(intent, PICK_ZIP)
    }

    @Deprecated("API Activity sans AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val name = pendingSite
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || uri == null || name == null) return
        toast("Envoi du site…")
        bg({
            contentResolver.openInputStream(uri)!!.use { api.upload("/api/extensions/${Uri.encode(name)}/site", it) }
        }) {
            toast("Site envoyé ✓")
            refresh()
        }
    }

    // ------------------------------------------------------------------ autres écrans

    private fun showLog() {
        bg({ JSONArray(api.call("GET", "/api/log")) }) { items ->
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
            val lines = (0 until items.length()).joinToString("\n") { i ->
                val q = items.getJSONObject(i)
                val ext = q.optString("ext").ifEmpty { "amont" }
                "${fmt.format(Date(q.optLong("t")))}  ${q.optString("client")}  ${q.optString("type")} ${q.optString("name")} → $ext"
            }.ifEmpty { "Aucune requête pour l'instant." }
            val view = text(lines, 12f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            AlertDialog.Builder(this)
                .setTitle("Dernières requêtes DNS")
                .setView(ScrollView(this).apply { addView(view) })
                .setPositiveButton("Fermer", null)
                .show()
        }
    }

    private fun settings() {
        val form = vertical().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val url = field(form, "Adresse de l'API Caloo", prefs.getString("url", DEFAULT_URL)!!)
        val token = field(form, "Token (affiché à la fin de install.sh)", prefs.getString("token", "")!!).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        form.addView(text("Sur le VPS : sudo grep token /etc/caloo/config.json", 12f).apply { setTextColor(GREY) })
        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(form)
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit()
                    .putString("url", url.text.toString().trim().ifEmpty { DEFAULT_URL })
                    .putString("token", token.text.toString().trim())
                    .apply()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openWireGuard() {
        val intent = packageManager.getLaunchIntentForPackage("com.wireguard.android")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.wireguard.android"))
        start(intent)
    }

    private fun openHotspot() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        if (!start(tether, quiet = true) && !start(Intent(Settings.ACTION_WIRELESS_SETTINGS), quiet = true)) {
            start(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun start(intent: Intent, quiet: Boolean = false): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        if (!quiet) toast("Impossible d'ouvrir cet écran")
        false
    }

    // ------------------------------------------------------------------ outils

    /** Exécute [work] hors du thread principal, puis [done] sur l'interface. */
    private fun <T> bg(work: () -> T, onError: ((Throwable) -> Unit)? = null, done: (T) -> Unit) {
        thread {
            val r = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.fold(done) { e -> onError?.invoke(e) ?: toast(e.message ?: e.toString()) }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun label(s: String) = text(s, 12f).apply { setTextColor(GREY); setPadding(0, dp(10), 0, 0) }

    private fun field(parent: LinearLayout, title: String, value: String): EditText {
        parent.addView(label(title))
        return EditText(this).apply {
            setText(value)
            setSingleLine(false)
            textSize = 15f
            setTextColor(NAVY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), BORDER)
            }
            parent.addView(
                this,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
    }

    /** Bouton secondaire : surface discrete et contour leger. */
    private fun button(title: String, onClick: () -> Unit) =
        styledButton(title, SURFACE, NAVY, BORDER, onClick)

    /** Bouton principal : aplat de la couleur d'accent. */
    private fun primary(title: String, onClick: () -> Unit) =
        styledButton(title, TEAL, Color.WHITE, TEAL, onClick)

    private fun styledButton(title: String, fill: Int, ink: Int, stroke: Int, onClick: () -> Unit) =
        Button(this).apply {
            fun shape() = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(fill)
                setStroke(dp(1), stroke)
            }
            text = title
            isAllCaps = false
            textSize = 15f
            setTextColor(ink)
            minHeight = dp(48)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            // Retire l'ombre animee heritee du theme : l'ensemble reste plat et homogene.
            stateListAnimator = null
            val ripple = Color.argb(70, Color.red(ink), Color.green(ink), Color.blue(ink))
            background = RippleDrawable(ColorStateList.valueOf(ripple), shape(), shape())
            setOnClickListener { onClick() }
        }

    /** Encadre de contenu : surface douce, coins arrondis. */
    private fun card(child: View) = vertical().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(SURFACE)
            setStroke(dp(1), BORDER)
        }
        addView(child)
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, h)
    }

    private fun sectionTitle(s: String) = text(s, 12f, bold = true).apply {
        setTextColor(GREY)
        letterSpacing = 0.12f
        setPadding(dp(4), dp(24), 0, dp(10))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEachIndexed { i, v ->
            addView(
                v,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) },
            )
        }
    }
}

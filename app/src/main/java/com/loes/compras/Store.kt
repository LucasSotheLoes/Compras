package com.loes.compras

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class App : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(File(filesDir, "dados.json"))
        PDFBoxResourceLoader.init(applicationContext)
    }
}

/**
 * Todos os dados ficam num único arquivo JSON no aparelho. Para ~10 fornecedores e
 * algumas centenas de itens isso é pequeno e rápido; nada sai do celular.
 */
class Store(private val file: File) {
    var suppliers by mutableStateOf<List<Supplier>>(emptyList())
        private set
    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var prices by mutableStateOf<List<PriceEntry>>(emptyList())
        private set
    var settings by mutableStateOf(Settings())
        private set

    init {
        if (file.exists()) {
            runCatching { applyJson(JSONObject(file.readText())) }
        }
    }

    private fun applyJson(o: JSONObject) {
        fun arr(key: String): List<JSONObject> {
            val a: JSONArray = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }
        }
        suppliers = arr("suppliers").map(::supplierFromJson)
        items = arr("items").map(::itemFromJson)
        prices = arr("prices").map(::priceFromJson)
        settings = settingsFromJson(o.optJSONObject("settings"))
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("suppliers", JSONArray().also { a -> suppliers.forEach { a.put(it.toJson()) } })
        o.put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
        o.put("prices", JSONArray().also { a -> prices.forEach { a.put(it.toJson()) } })
        o.put("settings", settings.toJson())
        return o.toString()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Substitui tudo pelo conteúdo de um backup. Lança exceção se o JSON for inválido. */
    fun importJson(text: String) {
        val o = JSONObject(text)
        applyJson(o)
        save()
    }

    fun supplier(id: String?): Supplier? = suppliers.firstOrNull { it.id == id }
    fun item(id: String?): Item? = items.firstOrNull { it.id == id }

    fun upsertSupplier(s: Supplier) {
        suppliers = if (suppliers.any { it.id == s.id }) suppliers.map { if (it.id == s.id) s else it } else suppliers + s
        save()
    }

    fun deleteSupplier(id: String) {
        suppliers = suppliers.filterNot { it.id == id }
        prices = prices.filterNot { it.supplierId == id }
        save()
    }

    fun upsertItem(i: Item) {
        items = if (items.any { it.id == i.id }) items.map { if (it.id == i.id) i else it } else items + i
        save()
    }

    fun upsertItems(list: List<Item>) {
        if (list.isEmpty()) return
        val byId = list.associateBy { it.id }
        val existing = items.map { byId[it.id] ?: it }
        val newOnes = list.filter { n -> items.none { it.id == n.id } }
        items = existing + newOnes
        save()
    }

    fun deleteItem(id: String) {
        items = items.filterNot { it.id == id }
        prices = prices.filterNot { it.itemId == id }
        save()
    }

    fun addPrices(list: List<PriceEntry>) {
        if (list.isEmpty()) return
        prices = prices + list
        save()
    }

    fun deletePrice(id: String) {
        prices = prices.filterNot { it.id == id }
        save()
    }

    fun updateSettings(s: Settings) {
        settings = s
        save()
    }
}

package com.loes.compras

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

data class Supplier(
    val id: String,
    val name: String,
    val phone: String = "",
    val cnpj: String = "",
    val prazoPadrao: Int = 28,
    /** Assinaturas de documentos sem CNPJ (ex.: orçamentos) já ligadas a este fornecedor. */
    val docKeys: List<String> = emptyList(),
)

data class Item(
    val id: String,
    val name: String,
    val unit: String = "UN",
    /** Textos normalizados de cotações já ligados a este item (auto-match na próxima vez). */
    val aliases: List<String> = emptyList(),
    /** Códigos do fornecedor vindos da NF, no formato "<cnpj ou id do fornecedor>|<código>". */
    val codes: List<String> = emptyList(),
    val minStock: Double = 0.0,
    val maxStock: Double = 0.0,
    val lastCount: Double? = null,
    val lastCountDate: Long? = null,
    /** Quantidade colocada à mão na lista de compras (0 = não está na lista). */
    val wanted: Double = 0.0,
)

enum class Source { NF, COTACAO }

data class PriceEntry(
    val id: String,
    val itemId: String,
    val supplierId: String,
    val date: Long,
    /** Preço unitário de tabela (sem impostos/frete). */
    val unitPrice: Double,
    /** Custo unitário real: preço + IPI + ST + frete − desconto. */
    val effectiveCost: Double,
    /** Quantidade comprada (0 para cotação). */
    val qty: Double,
    val source: Source,
    val prazoDias: Int,
    val note: String = "",
)

data class Settings(
    /** Taxa mensal (%) usada para trazer o preço a prazo para o valor à vista. */
    val taxaMensal: Double = 1.0,
    /** Alerta quando o custo sobe mais que este % em relação à compra anterior. */
    val alertaPct: Double = 5.0,
    /** Cotações mais antigas que isso não entram no ranking. */
    val janelaDias: Int = 30,
    /** Quantos dias de consumo a sugestão de pedido tenta cobrir. */
    val coberturaDias: Int = 30,
)

// ---------- JSON ----------

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

fun Supplier.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("phone", phone).put("cnpj", cnpj).put("prazoPadrao", prazoPadrao)
    .put("docKeys", docKeys.toJsonArray())

fun supplierFromJson(o: JSONObject) = Supplier(
    id = o.getString("id"),
    name = o.optString("name"),
    phone = o.optString("phone"),
    cnpj = o.optString("cnpj"),
    prazoPadrao = o.optInt("prazoPadrao", 28),
    docKeys = o.optJSONArray("docKeys").strings(),
)

fun Item.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("unit", unit)
    .put("aliases", aliases.toJsonArray()).put("codes", codes.toJsonArray())
    .put("minStock", minStock).put("maxStock", maxStock)
    .put("lastCount", lastCount ?: JSONObject.NULL)
    .put("lastCountDate", lastCountDate ?: JSONObject.NULL)
    .put("wanted", wanted)

fun itemFromJson(o: JSONObject) = Item(
    id = o.getString("id"),
    name = o.optString("name"),
    unit = o.optString("unit", "UN"),
    aliases = o.optJSONArray("aliases").strings(),
    codes = o.optJSONArray("codes").strings(),
    minStock = o.optDouble("minStock", 0.0),
    maxStock = o.optDouble("maxStock", 0.0),
    lastCount = o.optDoubleOrNull("lastCount"),
    lastCountDate = o.optLongOrNull("lastCountDate"),
    wanted = o.optDouble("wanted", 0.0),
)

fun PriceEntry.toJson(): JSONObject = JSONObject()
    .put("id", id).put("itemId", itemId).put("supplierId", supplierId).put("date", date)
    .put("unitPrice", unitPrice).put("effectiveCost", effectiveCost).put("qty", qty)
    .put("source", source.name).put("prazoDias", prazoDias).put("note", note)

fun priceFromJson(o: JSONObject) = PriceEntry(
    id = o.getString("id"),
    itemId = o.getString("itemId"),
    supplierId = o.getString("supplierId"),
    date = o.optLong("date"),
    unitPrice = o.optDouble("unitPrice", 0.0),
    effectiveCost = o.optDouble("effectiveCost", 0.0),
    qty = o.optDouble("qty", 0.0),
    source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.COTACAO),
    prazoDias = o.optInt("prazoDias", 0),
    note = o.optString("note"),
)

fun Settings.toJson(): JSONObject = JSONObject()
    .put("taxaMensal", taxaMensal).put("alertaPct", alertaPct)
    .put("janelaDias", janelaDias).put("coberturaDias", coberturaDias)

fun settingsFromJson(o: JSONObject?): Settings {
    if (o == null) return Settings()
    return Settings(
        taxaMensal = o.optDouble("taxaMensal", 1.0),
        alertaPct = o.optDouble("alertaPct", 5.0),
        janelaDias = o.optInt("janelaDias", 30),
        coberturaDias = o.optInt("coberturaDias", 30),
    )
}

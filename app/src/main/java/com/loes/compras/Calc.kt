package com.loes.compras

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

const val DAY_MS = 86_400_000L

val BR: Locale = Locale.forLanguageTag("pt-BR")

fun money(v: Double): String = NumberFormat.getCurrencyInstance(BR).format(v)
fun num(v: Double, dec: Int = 2): String = String.format(BR, "%.${dec}f", v)
fun qtyFmt(v: Double): String = if (abs(v - floor(v)) < 1e-9) v.toLong().toString() else num(v, 2)
fun dateFmt(ms: Long): String = SimpleDateFormat("dd/MM/yy", BR).format(Date(ms))
fun pctFmt(v: Double): String = (if (v > 0) "+" else "") + num(v, 1) + "%"

/** Traz um custo pago em [prazoDias] para o equivalente à vista, com juros compostos mensais. */
fun presentValue(cost: Double, prazoDias: Int, taxaMensalPct: Double): Double =
    cost / (1.0 + taxaMensalPct / 100.0).pow(prazoDias / 30.0)

fun variationPct(new: Double, old: Double): Double = if (old > 0) (new - old) / old * 100.0 else 0.0

fun Store.pv(e: PriceEntry): Double = presentValue(e.effectiveCost, e.prazoDias, settings.taxaMensal)

/** Último preço de cada fornecedor para o item, do mais barato (à vista equivalente) ao mais caro. */
fun Store.latestPerSupplier(itemId: String, onlyWindow: Boolean): List<PriceEntry> {
    val limit = System.currentTimeMillis() - settings.janelaDias * DAY_MS
    return prices.asSequence()
        .filter { it.itemId == itemId && (!onlyWindow || it.date >= limit) }
        .groupBy { it.supplierId }
        .values
        .map { g -> g.maxBy { it.date } }
        .sortedBy { pv(it) }
}

fun Store.bestOffer(itemId: String): PriceEntry? =
    latestPerSupplier(itemId, true).firstOrNull() ?: latestPerSupplier(itemId, false).firstOrNull()

fun Store.lastEntry(itemId: String): PriceEntry? = prices.filter { it.itemId == itemId }.maxByOrNull { it.date }

/** Entrada anterior do mesmo item (preferindo o mesmo fornecedor). */
fun Store.previousOf(e: PriceEntry): PriceEntry? {
    val older = prices.filter { it.itemId == e.itemId && it.id != e.id && it.date <= e.date }
    return older.filter { it.supplierId == e.supplierId }.maxByOrNull { it.date } ?: older.maxByOrNull { it.date }
}

/** Último custo conhecido do item (mesmo fornecedor se houver), para alertas na importação. */
fun Store.lastCostFor(itemId: String, supplierId: String?): Double? {
    val all = prices.filter { it.itemId == itemId }
    val same = all.filter { it.supplierId == supplierId }.maxByOrNull { it.date }
    return (same ?: all.maxByOrNull { it.date })?.effectiveCost
}

data class StockInfo(
    /** Consumo médio por dia estimado pelos intervalos entre compras (NF). */
    val consumoDia: Double?,
    /** Estoque estimado hoje = última contagem + entradas depois dela − consumo no período. */
    val estimado: Double?,
    /** Quantidade sugerida para pedir (0 = não precisa). */
    val sugerido: Double,
)

fun Store.stockInfo(item: Item): StockInfo {
    val now = System.currentTimeMillis()
    val buys = prices.filter { it.itemId == item.id && it.source == Source.NF && it.qty > 0 }.sortedBy { it.date }

    val consumo: Double? = if (buys.size >= 2) {
        val days = (buys.last().date - buys.first().date).toDouble() / DAY_MS
        // Tudo que foi comprado antes da última compra foi consumido no intervalo.
        if (days >= 1.0) buys.dropLast(1).sumOf { it.qty } / days else null
    } else null

    val estimado: Double? = item.lastCount?.let { count ->
        val since = item.lastCountDate ?: now
        val entradas = buys.filter { it.date > since }.sumOf { it.qty }
        val gasto = (consumo ?: 0.0) * ((now - since).toDouble() / DAY_MS)
        (count + entradas - gasto).coerceAtLeast(0.0)
    }

    val alvo = when {
        item.maxStock > 0 -> item.maxStock
        consumo != null -> item.minStock + consumo * settings.coberturaDias
        else -> item.minStock * 2
    }

    val sugerido = if (estimado != null && item.minStock > 0 && estimado <= item.minStock) {
        ceil(alvo - estimado).coerceAtLeast(1.0)
    } else 0.0

    return StockInfo(consumo, estimado, sugerido)
}

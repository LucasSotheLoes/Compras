package com.loes.compras

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================
// Cotação colada do WhatsApp
// =====================================================================

private class QRow(val line: QuoteLine, item: String?) {
    var include by mutableStateOf(item != null)
    var price by mutableStateOf(num(line.price))
    var itemId by mutableStateOf(item)
}

@Composable
fun QuoteImportScreen(store: Store, nav: Nav, text: String) {
    val ctx = LocalContext.current
    val rows = remember(text) { parseQuote(text).map { QRow(it, store.matchItem(it.desc)?.id) } }
    var supplierId by remember { mutableStateOf<String?>(null) }
    var prazo by remember { mutableStateOf("0") }
    var ipi by remember { mutableStateOf("0") }
    var st by remember { mutableStateOf("0") }
    var frete by remember { mutableStateOf("0") }
    var desc by remember { mutableStateOf("0") }
    var picking by remember { mutableStateOf<QRow?>(null) }

    fun eff(price: Double): Double {
        val d = (parseBr(desc) ?: 0.0) / 100
        val i = (parseBr(ipi) ?: 0.0) / 100
        val s = (parseBr(st) ?: 0.0) / 100
        val f = (parseBr(frete) ?: 0.0) / 100
        val base = price * (1 - d)
        return base * (1 + i + s + f)
    }

    val ready = supplierId != null && rows.any { it.include && it.itemId != null }

    SubScreen(
        title = "Cotação (${rows.size} linhas)",
        onBack = { nav.pop() },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = ready,
                    onClick = {
                        val now = System.currentTimeMillis()
                        val pz = parseBr(prazo)?.toInt() ?: 0
                        val chosen = rows.filter { it.include && it.itemId != null }
                        val entries = chosen.mapNotNull { r ->
                            val p = parseBr(r.price) ?: return@mapNotNull null
                            PriceEntry(
                                id = newId(), itemId = r.itemId!!, supplierId = supplierId!!, date = now,
                                unitPrice = p, effectiveCost = eff(p), qty = 0.0,
                                source = Source.COTACAO, prazoDias = pz,
                            )
                        }
                        // Aprende o texto do vendedor para casar sozinho da próxima vez
                        val updated = chosen.mapNotNull { r ->
                            val it0 = store.item(r.itemId) ?: return@mapNotNull null
                            val alias = normalize(r.line.desc)
                            if (alias in it0.aliases) null else it0.copy(aliases = it0.aliases + alias)
                        }.distinctBy { it.id }
                        store.upsertItems(updated)
                        store.addPrices(entries)
                        Toast.makeText(ctx, "${entries.size} preços salvos", Toast.LENGTH_SHORT).show()
                        nav.pop()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) { Text(if (supplierId == null) "Escolha o fornecedor" else "Salvar preços") }
            }
        },
    ) { p ->
        LazyColumn(Modifier.padding(p), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupplierPicker(store, supplierId, onSelect = { sid ->
                        supplierId = sid
                        prazo = (store.supplier(sid)?.prazoPadrao ?: 0).toString()
                    })
                    Row {
                        NumField("Prazo (dias)", prazo, { prazo = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        NumField("Desconto %", desc, { desc = it }, Modifier.weight(1f))
                    }
                    Row {
                        NumField("IPI %", ipi, { ipi = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        NumField("ST %", st, { st = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        NumField("Frete %", frete, { frete = it }, Modifier.weight(1f))
                    }
                    Text(
                        "IPI, ST e frete em % sobre o preço, se o vendedor passou \"por fora\". Deixe 0 se o preço já é final.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rows.isEmpty()) Hint("Não achei nenhuma linha com preço (ex.: \"Tubo 25mm Tigre - 18,90\").")
                }
            }
            itemsIndexed(rows) { _, r ->
                val price = parseBr(r.price) ?: 0.0
                val item = store.item(r.itemId)
                val best = item?.let { store.bestOffer(it.id) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = r.include, onCheckedChange = { r.include = it })
                            Text(r.line.desc, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            NumField("R$", r.price, { r.price = it }, Modifier.width(110.dp))
                        }
                        OutlinedButton(onClick = { picking = r }, modifier = Modifier.fillMaxWidth()) {
                            Text(item?.name ?: "Ligar a um item ▸")
                        }
                        if (item != null && price > 0) {
                            val e = eff(price)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Custo real ${money(e)}", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                if (best != null && best.supplierId != supplierId) {
                                    Text(
                                        "melhor atual ${money(best.effectiveCost)} (${store.supplier(best.supplierId)?.name}) ",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    VarBadge(variationPct(e, best.effectiveCost))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { r ->
        ItemPickerDialog(store, initial = r.line.desc, onDismiss = { picking = null }, onPick = { id ->
            r.itemId = id
            r.include = true
            picking = null
        })
    }
}

// =====================================================================
// Nota fiscal (PDF da DANFE)
// =====================================================================

private class NRow(val line: NfLine, item: String?) {
    var include by mutableStateOf(true)
    var itemId by mutableStateOf(item)
    var qty by mutableStateOf(num(line.qty, 2))
    var total by mutableStateOf(num(line.vTotal))
    var ipi by mutableStateOf(num(line.vIpi))
}

@Composable
fun NfImportScreen(store: Store, nav: Nav, uri: Uri) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<NfParsed?>(null) }
    var rows by remember { mutableStateOf<List<NRow>>(emptyList()) }
    var supplierId by remember { mutableStateOf<String?>(null) }
    var prazo by remember { mutableStateOf("28") }
    var frete by remember { mutableStateOf("0") }
    var st by remember { mutableStateOf("0") }
    var descTotal by remember { mutableStateOf("0") }
    var picking by remember { mutableStateOf<NRow?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            val text = withContext(Dispatchers.IO) { readPdfText(ctx, uri) }
            val nf = parseDanfe(text)
            parsed = nf
            val sup = nf.cnpjEmitente?.let { c -> store.suppliers.firstOrNull { digits(it.cnpj) == c && c.isNotEmpty() } }
            supplierId = sup?.id
            if (sup != null) prazo = sup.prazoPadrao.toString()
            val key = nf.cnpjEmitente ?: sup?.id ?: ""
            rows = nf.lines.map { l ->
                val byCode = if (key.isNotEmpty()) store.items.firstOrNull { codeKey(key, l.code) in it.codes } else null
                NRow(l, (byCode ?: store.matchItem(l.desc))?.id)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    val nf = parsed
    val already = nf?.numero?.let { n -> store.prices.any { it.note == "NF $n" && (supplierId == null || it.supplierId == supplierId) } } ?: false

    // Rateio de frete/ST/desconto da nota proporcional ao valor de cada item
    val included = rows.filter { it.include }
    val base = included.sumOf { parseBr(it.total) ?: 0.0 }
    val extra = (parseBr(frete) ?: 0.0) + (parseBr(st) ?: 0.0) - (parseBr(descTotal) ?: 0.0)
    fun unitCost(r: NRow): Double? {
        val q = parseBr(r.qty) ?: return null
        val t = parseBr(r.total) ?: return null
        if (q <= 0 || base <= 0) return null
        val share = t / base
        return (t + (parseBr(r.ipi) ?: 0.0) + extra * share) / q
    }

    SubScreen(
        title = if (nf?.numero != null) "NF ${nf.numero}" else "Importar nota",
        onBack = { nav.pop() },
        bottomBar = {
            if (nf != null && rows.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = supplierId != null && included.any { it.itemId != null },
                    onClick = {
                        val sid = supplierId!!
                        val date = nf.data ?: System.currentTimeMillis()
                        val pz = parseBr(prazo)?.toInt() ?: 0
                        val key = nf.cnpjEmitente ?: sid
                        val chosen = included.filter { it.itemId != null }
                        val entries = chosen.mapNotNull { r ->
                            val q = parseBr(r.qty) ?: return@mapNotNull null
                            val t = parseBr(r.total) ?: return@mapNotNull null
                            val uc = unitCost(r) ?: return@mapNotNull null
                            PriceEntry(
                                id = newId(), itemId = r.itemId!!, supplierId = sid, date = date,
                                unitPrice = t / q, effectiveCost = uc, qty = q,
                                source = Source.NF, prazoDias = pz,
                                note = nf.numero?.let { "NF $it" } ?: "NF",
                            )
                        }
                        val updated = chosen.mapNotNull { r ->
                            val it0 = store.item(r.itemId) ?: return@mapNotNull null
                            val k = codeKey(key, r.line.code)
                            if (k in it0.codes || r.line.code.isBlank()) null else it0.copy(codes = it0.codes + k)
                        }.distinctBy { it.id }
                        store.upsertItems(updated)
                        // Guarda o CNPJ no fornecedor para reconhecê-lo sozinho na próxima nota
                        val sup = store.supplier(sid)
                        if (sup != null && sup.cnpj.isBlank() && nf.cnpjEmitente != null) {
                            store.upsertSupplier(sup.copy(cnpj = nf.cnpjEmitente))
                        }
                        store.addPrices(entries)
                        Toast.makeText(ctx, "${entries.size} itens da nota salvos", Toast.LENGTH_SHORT).show()
                        nav.pop()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) { Text(if (supplierId == null) "Escolha o fornecedor" else "Salvar ${included.size} itens") }
            }
        },
    ) { p ->
        if (loading) {
            Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SubScreen
        }
        if (error != null || nf == null) {
            Hint("Não consegui ler o PDF: $error", Modifier.padding(p))
            return@SubScreen
        }
        LazyColumn(Modifier.padding(p), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Emissão ${nf.data?.let { dateFmt(it) } ?: "?"} · CNPJ emitente ${nf.cnpjEmitente ?: "não encontrado"} · ${rows.size} itens lidos",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (already) Text("⚠ Esta nota parece já ter sido importada.", color = Up, fontWeight = FontWeight.Bold)
                    SupplierPicker(store, supplierId, newCnpj = nf.cnpjEmitente ?: "", onSelect = { sid ->
                        supplierId = sid
                        prazo = (store.supplier(sid)?.prazoPadrao ?: 28).toString()
                    })
                    Row {
                        NumField("Prazo (dias)", prazo, { prazo = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        NumField("Frete total R$", frete, { frete = it }, Modifier.weight(1f))
                    }
                    Row {
                        NumField("ICMS-ST total R$", st, { st = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        NumField("Desconto total R$", descTotal, { descTotal = it }, Modifier.weight(1f))
                    }
                    Text(
                        "Frete, ST e desconto estão no quadro \"Cálculo do imposto\" da DANFE e são rateados entre os itens pelo valor de cada um. O IPI já vem por item.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rows.isEmpty()) {
                        Hint(
                            "Não reconheci a tabela de itens desta DANFE. Copie o texto extraído abaixo e me mande para eu ajustar o leitor para o layout desse fornecedor."
                        )
                    }
                    Row {
                        OutlinedButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Ocultar texto" else "Ver texto extraído") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(nf.rawText))
                            Toast.makeText(ctx, "Texto copiado", Toast.LENGTH_SHORT).show()
                        }) { Text("Copiar texto") }
                    }
                    if (showRaw) {
                        SelectionContainer { Text(nf.rawText.take(6000), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            itemsIndexed(rows) { _, r ->
                val item = store.item(r.itemId)
                val uc = if (r.include) unitCost(r) else null
                val prev = item?.let { store.lastCostFor(it.id, supplierId) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = r.include, onCheckedChange = { r.include = it })
                            Column(Modifier.weight(1f)) {
                                Text(r.line.desc, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "cód. ${r.line.code} · ${r.line.unit} · unit. ${money(r.line.vUnit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Row {
                            NumField("Qtd", r.qty, { r.qty = it }, Modifier.weight(1f))
                            Spacer(Modifier.width(4.dp))
                            NumField("Total R$", r.total, { r.total = it }, Modifier.weight(1f))
                            Spacer(Modifier.width(4.dp))
                            NumField("IPI R$", r.ipi, { r.ipi = it }, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { picking = r }, modifier = Modifier.fillMaxWidth()) {
                            Text(item?.name ?: "Ligar a um item ▸")
                        }
                        if (uc != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Custo real ${money(uc)}/${item?.unit ?: r.line.unit}", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                if (prev != null) {
                                    val v = variationPct(uc, prev)
                                    Text("antes ${money(prev)} ", style = MaterialTheme.typography.bodySmall)
                                    VarBadge(v)
                                    if (v > store.settings.alertaPct) Text(" ⚠", color = Up)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { r ->
        ItemPickerDialog(store, initial = r.line.desc, newUnit = r.line.unit, onDismiss = { picking = null }, onPick = { id ->
            r.itemId = id
            picking = null
        })
    }
}

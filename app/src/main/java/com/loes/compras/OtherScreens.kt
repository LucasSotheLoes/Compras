package com.loes.compras

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// =====================================================================
// Comparar
// =====================================================================

@Composable
fun CompareScreen(store: Store, nav: Nav) {
    var onlyMulti by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val data = store.items
        .map { it to store.latestPerSupplier(it.id, true) }
        .filter { (item, l) ->
            l.isNotEmpty() && (!onlyMulti || l.size >= 2) &&
                (q.isBlank() || normalize(item.name).contains(normalize(q)))
        }
        .sortedBy { it.first.name.lowercase() }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Text(
                    "Ranking pelo custo real trazido à vista (taxa ${num(store.settings.taxaMensal, 1)}% a.m.), " +
                        "usando o último preço de cada fornecedor nos últimos ${store.settings.janelaDias} dias.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(q, { q = it }, label = { Text("Filtrar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                FilterChip(selected = onlyMulti, onClick = { onlyMulti = !onlyMulti }, label = { Text("Só com 2+ fornecedores") })
                if (data.isEmpty()) Hint("Nada para comparar ainda. Cole cotações de fornecedores diferentes para os mesmos itens.")
            }
        }
        items(data, key = { it.first.id }) { (item, l) ->
            val best = l.first()
            val bestPv = store.pv(best)
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.push(Screen.ItemDetail(item.id)) }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    l.forEachIndexed { i, e ->
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (store.supplier(e.supplierId)?.name ?: "?"),
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${money(e.effectiveCost)} · ${e.prazoDias}d · ${dateFmt(e.date)}  ",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (i == 0) Text("melhor", color = Down, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            else VarBadge(variationPct(store.pv(e), bestPv))
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Repor: contagem rápida + sugestão de pedido por fornecedor
// =====================================================================

@Composable
fun RestockScreen(store: Store) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Lista de compras") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Contagem") })
        }
        if (tab == 0) OrderSuggestion(store) else CountScreen(store)
    }
}

private enum class Motivo { ORCADO, MANUAL, ESTOQUE }

private data class OrderLine(
    val item: Item,
    val qty: Double,
    val offer: PriceEntry?,
    val second: PriceEntry?,
    val motivo: Motivo,
)

private fun startOfToday(): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

@Composable
private fun OrderSuggestion(store: Store) {
    val ctx = LocalContext.current
    var dias by rememberSaveable { mutableStateOf(1) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    val since = startOfToday() - (dias - 1) * DAY_MS

    fun ordered(item: Item) = store.upsertItem(item.copy(wanted = 0.0, orderedAt = System.currentTimeMillis()))

    // Monta a lista: tudo que foi orçado no período + o que você adicionou + o que o estoque pede
    val lines = mutableListOf<OrderLine>()
    val pedidos = mutableListOf<Item>()
    for (item in store.items) {
        val quotes = store.prices.filter { it.itemId == item.id && it.source == Source.COTACAO && it.createdAt >= since }
        val lastQuoteAt = quotes.maxOfOrNull { it.createdAt }
        val done = item.orderedAt
        val orcado = lastQuoteAt != null && (done == null || done < lastQuoteAt)
        val auto = store.stockInfo(item).sugerido
        val estoque = auto > 0 && (done == null || done < (item.lastCountDate ?: 0L))
        val motivo = when {
            item.wanted > 0 -> Motivo.MANUAL
            orcado -> Motivo.ORCADO
            estoque -> Motivo.ESTOQUE
            else -> null
        }
        if (motivo == null) {
            if (done != null && done >= since) pedidos += item
            continue
        }
        val qty = when (motivo) {
            Motivo.MANUAL -> item.wanted
            Motivo.ESTOQUE -> auto
            Motivo.ORCADO -> quotes.filter { it.qty > 0 }.maxByOrNull { it.createdAt }?.qty ?: 0.0
        }
        val rank = store.latestPerSupplier(item.id, true).ifEmpty { store.latestPerSupplier(item.id, false) }
        lines += OrderLine(item, qty, rank.getOrNull(0), rank.getOrNull(1), motivo)
    }
    val groups = lines.sortedBy { it.item.name.lowercase() }
        .groupBy { it.offer?.supplierId }
        .entries.sortedBy { store.supplier(it.key)?.name?.lowercase() ?: "~" }
    val totalGeral = lines.sumOf { (it.offer?.effectiveCost ?: 0.0) * it.qty }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Orçados hoje", 2 to "2 dias", 7 to "7 dias").forEach { (d, label) ->
                        FilterChip(selected = dias == d, onClick = { dias = d }, label = { Text(label) })
                    }
                }
                Text(
                    if (lines.isEmpty()) "Nada pendente. Importe os orçamentos do dia, ou adicione itens à mão."
                    else "${lines.size} itens para comprar" + (if (totalGeral > 0) " · ${money(totalGeral)}" else "") +
                        ". Marque ✓ no que já pediu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Adicionar item à mão") }
            }
        }
        items(groups, key = { it.key ?: "none" }) { (sid, ls) ->
            val sup = store.supplier(sid)
            val total = ls.sumOf { (it.offer?.effectiveCost ?: 0.0) * it.qty }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Text(
                        (sup?.name ?: "Sem preço registrado") + if (total > 0) "  ·  ${money(total)}" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    ls.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = false, onCheckedChange = { ordered(l.item) })
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { editing = l.item }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    (if (l.qty > 0) "${qtyFmt(l.qty)} ${l.item.unit}  " else "") + l.item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val detalhe = buildString {
                                    l.offer?.let { append(money(it.effectiveCost)) }
                                    l.second?.let { s2 ->
                                        val dif = variationPct(store.pv(s2), store.pv(l.offer!!))
                                        append(" · ${store.supplier(s2.supplierId)?.name ?: "?"} ${money(s2.effectiveCost)} (${pctFmt(dif)})")
                                    }
                                    when (l.motivo) {
                                        Motivo.MANUAL -> append(" · adicionado à mão")
                                        Motivo.ESTOQUE -> append(" · estoque no mínimo")
                                        Motivo.ORCADO -> Unit
                                    }
                                }
                                if (detalhe.isNotBlank()) {
                                    Text(detalhe.trim(' ', '·'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.padding(start = 8.dp)) {
                        Button(onClick = { sendOrder(ctx, sup, ls) }) { Text("Enviar no WhatsApp") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val now = System.currentTimeMillis()
                            store.upsertItems(ls.map { it.item.copy(wanted = 0.0, orderedAt = now) })
                        }) { Text("Pedi tudo") }
                    }
                }
            }
        }
        if (pedidos.isNotEmpty()) {
            item {
                SectionTitle("Já pedidos (${pedidos.size})")
            }
            items(pedidos.sortedByDescending { it.orderedAt ?: 0L }, key = { "p" + it.id }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = { store.upsertItem(item.copy(orderedAt = null)) })
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        java.text.SimpleDateFormat("HH:mm", BR).format(java.util.Date(item.orderedAt ?: 0L)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (adding) {
        ItemPickerDialog(store, initial = "", onDismiss = { adding = false }, onPick = { id ->
            adding = false
            editing = store.item(id)
        })
    }
    editing?.let { item ->
        val auto = store.stockInfo(item).sugerido
        WantedDialog(item, initialQty = if (item.wanted > 0) item.wanted else auto, onDismiss = { editing = null }) { q ->
            store.upsertItem(item.copy(wanted = q, orderedAt = if (q > 0) null else item.orderedAt))
            editing = null
        }
    }
}

/** Define quanto comprar de um item; 0 tira da lista. */
@Composable
fun WantedDialog(item: Item, initialQty: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var q by remember(item.id) { mutableStateOf(if (initialQty > 0) qtyFmt(initialQty) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Quantidade a comprar (${item.unit})", q, { q = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0, 5.0, 10.0, 50.0).forEach { inc ->
                        OutlinedButton(onClick = { q = qtyFmt((parseBr(q) ?: 0.0) + inc) }) { Text("+${qtyFmt(inc)}") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = (parseBr(q) ?: 0.0) > 0, onClick = { onSave(parseBr(q) ?: 0.0) }) { Text("Colocar na lista") }
        },
        dismissButton = {
            Row {
                if (item.wanted > 0) TextButton(onClick = { onSave(0.0) }) { Text("Tirar da lista") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

private fun sendOrder(ctx: Context, sup: Supplier?, ls: List<OrderLine>) {
    val msg = buildString {
        append("Olá! Pedido LOES Material de Construção:\n")
        ls.forEach { append("- ${qtyFmt(it.qty)} ${it.item.unit} ${it.item.name}\n") }
        append("Obrigado!")
    }
    val phone = digits(sup?.phone ?: "").let { if (it.isNotEmpty() && !it.startsWith("55")) "55$it" else it }
    val intent = if (phone.length >= 12) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=${Uri.encode(msg)}"))
    } else {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Enviar pedido")
    }
    runCatching { ctx.startActivity(intent) }.onFailure {
        Toast.makeText(ctx, "Não achei o WhatsApp", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun CountScreen(store: Store) {
    val ctx = LocalContext.current
    val counts = remember { mutableStateMapOf<String, String>() }
    val mins = remember { mutableStateMapOf<String, String>() }
    var q by rememberSaveable { mutableStateOf("") }
    val list = store.items
        .filter { q.isBlank() || normalize(it.name).contains(normalize(q)) }
        .sortedBy { it.name.lowercase() }
    val dirty = counts.values.any { it.isNotBlank() } || mins.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(
                    "Conte na prateleira e digite só o que contou. O mínimo pode ser ajustado aqui mesmo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(q, { q = it }, label = { Text("Filtrar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            items(list, key = { it.id }) { item ->
                val est = store.stockInfo(item).estimado
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "estimado ${est?.let { qtyFmt(it) } ?: "—"} ${item.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NumField("Mín", mins[item.id] ?: (if (item.minStock > 0) qtyFmt(item.minStock) else ""), { mins[item.id] = it }, Modifier.width(80.dp))
                    Spacer(Modifier.width(4.dp))
                    NumField("Contei", counts[item.id] ?: "", { counts[item.id] = it }, Modifier.width(90.dp))
                }
                HorizontalDivider()
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                enabled = dirty,
                onClick = {
                    val now = System.currentTimeMillis()
                    val updated = store.items.mapNotNull { item ->
                        val c = counts[item.id]?.let { parseBr(it) }
                        val m = mins[item.id]?.let { parseBr(it) ?: 0.0 }
                        if (c == null && m == null) null
                        else item.copy(
                            lastCount = c ?: item.lastCount,
                            lastCountDate = if (c != null) now else item.lastCountDate,
                            minStock = m ?: item.minStock,
                        )
                    }
                    store.upsertItems(updated)
                    counts.clear()
                    mins.clear()
                    Toast.makeText(ctx, "${updated.size} itens atualizados", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) { Text("Salvar contagem") }
        }
    }
}

// =====================================================================
// Mais: fornecedores, configurações, backup
// =====================================================================

@Composable
fun MoreScreen(store: Store, nav: Nav) {
    val ctx = LocalContext.current
    var s by remember(store.settings) { mutableStateOf(store.settings) }
    var taxa by remember(store.settings) { mutableStateOf(num(store.settings.taxaMensal, 2)) }
    var alerta by remember(store.settings) { mutableStateOf(num(store.settings.alertaPct, 1)) }
    var janela by remember(store.settings) { mutableStateOf(store.settings.janelaDias.toString()) }
    var cobertura by remember(store.settings) { mutableStateOf(store.settings.coberturaDias.toString()) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.toJson().toByteArray()) }
            }.onSuccess { Toast.makeText(ctx, "Backup salvo", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Erro ao salvar: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImport = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionTitle("Fornecedores")
        store.suppliers.sortedBy { it.name.lowercase() }.forEach { sup ->
            ListItem(
                headlineContent = { Text(sup.name) },
                supportingContent = {
                    Text(listOf(sup.phone, if (sup.cnpj.isNotBlank()) "CNPJ ${sup.cnpj}" else "", "${sup.prazoPadrao} dias").filter { it.isNotBlank() }.joinToString(" · "))
                },
                modifier = Modifier.clickable { nav.push(Screen.SupplierEdit(sup.id)) },
            )
        }
        OutlinedButton(onClick = { nav.push(Screen.SupplierEdit(null)) }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("+ Fornecedor")
        }

        SectionTitle("Configurações")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Taxa de juros mensal % (para comparar prazos)", taxa, { taxa = it }, Modifier.fillMaxWidth())
            NumField("Alertar se o custo subir mais de %", alerta, { alerta = it }, Modifier.fillMaxWidth())
            NumField("Cotação vale por (dias)", janela, { janela = it }, Modifier.fillMaxWidth())
            NumField("Pedido cobre quantos dias de consumo", cobertura, { cobertura = it }, Modifier.fillMaxWidth())
            Button(onClick = {
                s = Settings(
                    taxaMensal = parseBr(taxa) ?: 1.0,
                    alertaPct = parseBr(alerta) ?: 5.0,
                    janelaDias = parseBr(janela)?.toInt() ?: 30,
                    coberturaDias = parseBr(cobertura)?.toInt() ?: 30,
                )
                store.updateSettings(s)
                Toast.makeText(ctx, "Configurações salvas", Toast.LENGTH_SHORT).show()
            }) { Text("Salvar configurações") }
        }

        SectionTitle("Backup")
        Text(
            "Tudo fica só no celular. Exporte de vez em quando (ex.: para o Google Drive) para não perder os dados.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp)) {
            Button(onClick = { export.launch("compras-loes-backup.json") }) { Text("Exportar") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Importar") }
        }
        Text(
            "${store.items.size} itens · ${store.suppliers.size} fornecedores · ${store.prices.size} preços registrados",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restaurar backup?") },
            text = { Text("Isso substitui TODOS os dados atuais do app pelos do arquivo.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    runCatching { store.importJson(text) }
                        .onSuccess { Toast.makeText(ctx, "Backup restaurado", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, "Arquivo inválido", Toast.LENGTH_LONG).show() }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun SupplierEditScreen(store: Store, nav: Nav, id: String?) {
    val existing = store.supplier(id)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var cnpj by remember { mutableStateOf(existing?.cnpj ?: "") }
    var prazo by remember { mutableStateOf((existing?.prazoPadrao ?: 28).toString()) }
    var confirmDelete by remember { mutableStateOf(false) }

    SubScreen(
        if (existing == null) "Novo fornecedor" else "Fornecedor",
        onBack = { nav.pop() },
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Excluir") }
        },
    ) { p ->
        Column(
            Modifier
                .padding(p)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp do vendedor (DDD + número)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cnpj, { cnpj = it }, label = { Text("CNPJ (preenchido sozinho pela 1ª nota)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            NumField("Prazo padrão de pagamento (dias)", prazo, { prazo = it }, Modifier.fillMaxWidth())
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    store.upsertSupplier(
                        Supplier(
                            id = existing?.id ?: newId(),
                            name = name.trim(),
                            phone = phone.trim(),
                            cnpj = digits(cnpj),
                            prazoPadrao = parseBr(prazo)?.toInt() ?: 0,
                        )
                    )
                    nav.pop()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir ${existing.name}?") },
            text = { Text("Apaga o fornecedor e todos os preços dele. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; nav.pop(); store.deleteSupplier(existing.id) }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

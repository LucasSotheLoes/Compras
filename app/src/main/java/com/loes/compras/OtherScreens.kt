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
    var onlyMulti by rememberSaveable { mutableStateOf(true) }
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
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pedido sugerido") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Contagem") })
        }
        if (tab == 0) OrderSuggestion(store) else CountScreen(store)
    }
}

private data class OrderLine(val item: Item, val qty: Double, val offer: PriceEntry?)

@Composable
private fun OrderSuggestion(store: Store) {
    val ctx = LocalContext.current
    val lines = store.items.mapNotNull { item ->
        val s = store.stockInfo(item)
        if (s.sugerido > 0) OrderLine(item, s.sugerido, store.bestOffer(item.id)) else null
    }
    val groups = lines.groupBy { it.offer?.supplierId }
    val semConfig = store.items.count { it.minStock <= 0 || it.lastCount == null }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (lines.isEmpty()) "Nada para repor agora pelo estoque estimado."
                else "${lines.size} itens chegaram no estoque mínimo. Agrupados pelo fornecedor mais barato.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (semConfig > 0) {
                Text(
                    "$semConfig itens sem estoque mínimo ou sem contagem não entram na sugestão (use a aba Contagem).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(groups.entries.toList(), key = { it.key ?: "none" }) { (sid, ls) ->
            val sup = store.supplier(sid)
            val total = ls.sumOf { (it.offer?.effectiveCost ?: 0.0) * it.qty }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(sup?.name ?: "Sem preço registrado", style = MaterialTheme.typography.titleSmall)
                    ls.forEach { l ->
                        Text(
                            "${qtyFmt(l.qty)} ${l.item.unit}  ${l.item.name}" +
                                (l.offer?.let { "  · ${money(it.effectiveCost)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (total > 0) Text("Estimado: ${money(total)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { sendOrder(ctx, sup, ls) }) { Text("Enviar pedido no WhatsApp") }
                }
            }
        }
    }
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

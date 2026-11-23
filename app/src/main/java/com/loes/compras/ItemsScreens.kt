package com.loes.compras

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ItemsScreen(store: Store, nav: Nav) {
    var q by rememberSaveable { mutableStateOf("") }
    val all = store.items
    val list = remember(q, all) {
        val n = normalize(q)
        all.filter { n.isBlank() || normalize(it.name).contains(n) || similarity(q, it.name) >= 0.5 }
            .sortedBy { it.name.lowercase() }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                label = { Text("Buscar item") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (all.isEmpty()) {
                Hint(
                    "Nenhum item ainda. Vá em Entrada e importe uma nota (PDF) ou cole uma cotação do " +
                        "WhatsApp: os itens são criados ali mesmo. Ou toque em + para cadastrar."
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(list, key = { it.id }) { item -> ItemRow(store, item) { nav.push(Screen.ItemDetail(item.id)) } }
            }
        }
        FloatingActionButton(
            onClick = { nav.push(Screen.ItemEdit(null)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Novo item") }
    }
}

@Composable
private fun ItemRow(store: Store, item: Item, onClick: () -> Unit) {
    val best = store.bestOffer(item.id)
    val last = store.lastEntry(item.id)
    val prev = last?.let { store.previousOf(it) }
    ListItem(
        headlineContent = { Text(item.name) },
        supportingContent = {
            Text(
                if (best == null) "Sem preços ainda"
                else "Melhor: ${store.supplier(best.supplierId)?.name ?: "?"} · ${money(best.effectiveCost)}/${item.unit}"
            )
        },
        trailingContent = {
            if (last != null && prev != null) VarBadge(variationPct(last.effectiveCost, prev.effectiveCost))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
fun ItemDetailScreen(store: Store, nav: Nav, id: String) {
    val item = store.item(id)
    if (item == null) {
        SubScreen("Item", onBack = { nav.pop() }) { p -> Hint("Item removido.", Modifier.padding(p)) }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var wantedDlg by remember { mutableStateOf(false) }
    val history = store.prices.filter { it.itemId == id }.sortedBy { it.date }
    val ranking = store.latestPerSupplier(id, false)
    val stock = store.stockInfo(item)

    SubScreen(
        title = item.name,
        onBack = { nav.pop() },
        actions = {
            IconButton(onClick = { nav.push(Screen.ItemEdit(id)) }) { Icon(Icons.Filled.Edit, "Editar") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Excluir") }
        },
    ) { p ->
        Column(
            Modifier
                .padding(p)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Último preço de cada fornecedor")
            if (ranking.isEmpty()) Hint("Sem preços. Importe uma nota ou cotação, ou lance um preço manual.")
            ranking.forEachIndexed { i, e ->
                val diff = if (i > 0) variationPct(store.pv(e), store.pv(ranking[0])) else 0.0
                ListItem(
                    headlineContent = {
                        Text(
                            store.supplier(e.supplierId)?.name ?: "?",
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = {
                        Text(
                            "${money(e.effectiveCost)}/${item.unit} · ${e.prazoDias} dias · " +
                                "à vista eq. ${money(store.pv(e))} · ${if (e.source == Source.NF) "NF" else "cotação"} ${dateFmt(e.date)}"
                        )
                    },
                    trailingContent = {
                        if (i == 0) Text("melhor", color = Down, fontWeight = FontWeight.Bold)
                        else VarBadge(diff)
                    },
                )
            }
            OutlinedButton(onClick = { manual = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Lançar preço manual")
            }
            Button(onClick = { wantedDlg = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (item.wanted > 0) "Na lista de compras: ${qtyFmt(item.wanted)} ${item.unit}" else "+ Lista de compras")
            }

            if (history.size >= 2) {
                SectionTitle("Histórico do custo real")
                PriceChart(history)
            }

            SectionTitle("Estoque")
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mínimo: ${qtyFmt(item.minStock)} ${item.unit}" + if (item.maxStock > 0) " · Máximo: ${qtyFmt(item.maxStock)}" else "")
                    Text(
                        "Última contagem: " + (item.lastCount?.let { "${qtyFmt(it)} em ${dateFmt(item.lastCountDate ?: 0L)}" } ?: "nunca")
                    )
                    Text("Consumo estimado: " + (stock.consumoDia?.let { "${num(it * 30, 1)} ${item.unit}/mês" } ?: "precisa de 2+ notas"))
                    Text("Estoque estimado hoje: " + (stock.estimado?.let { qtyFmt(it) } ?: "—"))
                    if (stock.sugerido > 0) Text("Sugestão: pedir ${qtyFmt(stock.sugerido)} ${item.unit}", color = Up, fontWeight = FontWeight.Bold)
                }
            }

            SectionTitle("Todas as entradas")
            history.asReversed().forEach { e ->
                var askDel by remember(e.id) { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("${dateFmt(e.date)} · ${store.supplier(e.supplierId)?.name ?: "?"}") },
                    supportingContent = {
                        Text(
                            "tabela ${money(e.unitPrice)} → real ${money(e.effectiveCost)}" +
                                (if (e.qty > 0) " · qtd ${qtyFmt(e.qty)}" else "") +
                                (if (e.note.isNotBlank()) " · ${e.note}" else "")
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { askDel = true }) { Icon(Icons.Filled.Delete, "Apagar entrada") }
                    },
                )
                if (askDel) {
                    AlertDialog(
                        onDismissRequest = { askDel = false },
                        title = { Text("Apagar esta entrada?") },
                        confirmButton = { TextButton(onClick = { askDel = false; store.deletePrice(e.id) }) { Text("Apagar") } },
                        dismissButton = { TextButton(onClick = { askDel = false }) { Text("Cancelar") } },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir \"${item.name}\"?") },
            text = { Text("Apaga o item e todo o histórico de preços dele. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; nav.pop(); store.deleteItem(id) }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
    if (manual) ManualPriceDialog(store, item) { manual = false }
    if (wantedDlg) {
        WantedDialog(item, initialQty = if (item.wanted > 0) item.wanted else stock.sugerido, onDismiss = { wantedDlg = false }) { q ->
            store.upsertItem(item.copy(wanted = q))
            wantedDlg = false
        }
    }
}

@Composable
private fun ManualPriceDialog(store: Store, item: Item, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var supplierId by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf("") }
    var prazo by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Preço manual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SupplierPicker(store, supplierId, onSelect = { sid ->
                    supplierId = sid
                    prazo = (store.supplier(sid)?.prazoPadrao ?: 0).toString()
                })
                NumField("Custo real por ${item.unit} (R$)", price, { price = it }, Modifier.fillMaxWidth())
                NumField("Prazo (dias)", prazo, { prazo = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = supplierId != null && (parseBr(price) ?: 0.0) > 0,
                onClick = {
                    val v = parseBr(price) ?: 0.0
                    store.addPrices(
                        listOf(
                            PriceEntry(
                                id = newId(), itemId = item.id, supplierId = supplierId!!,
                                date = System.currentTimeMillis(), unitPrice = v, effectiveCost = v, qty = 0.0,
                                source = Source.COTACAO, prazoDias = parseBr(prazo)?.toInt() ?: 0, note = "manual",
                            )
                        )
                    )
                    Toast.makeText(ctx, "Preço salvo", Toast.LENGTH_SHORT).show()
                    onClose()
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

@Composable
fun ItemEditScreen(store: Store, nav: Nav, id: String?) {
    val existing = store.item(id)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "UN") }
    var min by remember { mutableStateOf(existing?.minStock?.let { qtyFmt(it) } ?: "") }
    var max by remember { mutableStateOf(existing?.maxStock?.takeIf { it > 0 }?.let { qtyFmt(it) } ?: "") }

    SubScreen(if (existing == null) "Novo item" else "Editar item", onBack = { nav.pop() }) { p ->
        Column(
            Modifier
                .padding(p)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome (ex.: Tubo soldável 25mm Tigre 6m)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(unit, { unit = it.uppercase() }, label = { Text("Unidade (UN, PC, M, CX, KG...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                NumField("Estoque mínimo", min, { min = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                NumField("Estoque máximo", max, { max = it }, Modifier.weight(1f))
            }
            Text(
                "O mínimo é o ponto de pedido: quando o estoque estimado chegar nele, o item entra na sugestão. " +
                    "O máximo é opcional (sem ele, o app usa o consumo estimado × dias de cobertura).",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val base = existing ?: Item(id = newId(), name = "")
                    store.upsertItem(
                        base.copy(
                            name = name.trim(),
                            unit = unit.trim().ifBlank { "UN" },
                            minStock = parseBr(min) ?: 0.0,
                            maxStock = parseBr(max) ?: 0.0,
                        )
                    )
                    nav.pop()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar") }
        }
    }
}

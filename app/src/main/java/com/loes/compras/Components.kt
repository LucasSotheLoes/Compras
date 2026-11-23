package com.loes.compras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

val Up = Color(0xFFC62828)
val Down = Color(0xFF2E7D32)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFE65100),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE0B2),
            onPrimaryContainer = Color(0xFF3E2000),
            secondary = Color(0xFF5D4037),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Mostra "▲ +8,2%" em vermelho ou "▼ −3,0%" em verde. */
@Composable
fun VarBadge(pct: Double) {
    if (abs(pct) < 0.05) return
    val up = pct > 0
    Text(
        text = (if (up) "▲ " else "▼ ") + pctFmt(pct),
        color = if (up) Up else Down,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
fun SupplierPicker(
    store: Store,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    newCnpj: String = "",
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(store.supplier(selectedId)?.name ?: "Escolher fornecedor ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            store.suppliers.sortedBy { it.name.lowercase() }.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { open = false; onSelect(s.id) })
            }
            DropdownMenuItem(text = { Text("+ Novo fornecedor") }, onClick = { open = false; creating = true })
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Novo fornecedor") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val s = Supplier(id = newId(), name = name.trim(), cnpj = newCnpj)
                        store.upsertSupplier(s)
                        creating = false
                        onSelect(s.id)
                    },
                ) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }
}

/** Busca/escolhe um item do catálogo, ou cria um novo com o texto digitado. */
@Composable
fun ItemPickerDialog(
    store: Store,
    initial: String,
    newUnit: String = "UN",
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var q by remember { mutableStateOf(initial) }
    val all = store.items
    val list = remember(q, all) {
        if (q.isBlank()) all.sortedBy { it.name.lowercase() }
        else {
            val nq = normalize(q)
            all.map { it to similarity(q, it.name) }
                .filter { it.second > 0.25 || normalize(it.first.name).contains(nq) }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qual item é esse?") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it },
                    label = { Text("Buscar ou nome do novo item") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list.take(60), key = { it.id }) { item ->
                        Text(
                            item.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item.id) }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = q.isNotBlank(),
                onClick = {
                    val created = Item(id = newId(), name = q.trim(), unit = newUnit)
                    store.upsertItem(created)
                    onPick(created.id)
                },
            ) { Text("Criar novo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun PriceChart(entries: List<PriceEntry>, modifier: Modifier = Modifier) {
    if (entries.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val minV = entries.minOf { it.effectiveCost }
    val maxV = entries.maxOf { it.effectiveCost }
    val t0 = entries.first().date
    val t1 = entries.last().date
    Column(modifier.padding(horizontal = 16.dp)) {
        Text("máx ${money(maxV)}", style = MaterialTheme.typography.labelSmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(vertical = 6.dp)
        ) {
            val m = 10f
            val w = size.width - 2 * m
            val h = size.height - 2 * m
            val pts = entries.map { e ->
                val x = if (t1 == t0) w / 2 else (e.date - t0).toFloat() / (t1 - t0).toFloat() * w
                val y = if (maxV == minV) h / 2 else (h - ((e.effectiveCost - minV) / (maxV - minV)).toFloat() * h)
                Offset(x + m, y + m)
            }
            for (i in 1 until pts.size) drawLine(color, pts[i - 1], pts[i], strokeWidth = 4f)
            pts.forEach { drawCircle(color, radius = 7f, center = it) }
        }
        Row {
            Text("mín ${money(minV)}", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("${dateFmt(t0)} → ${dateFmt(t1)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

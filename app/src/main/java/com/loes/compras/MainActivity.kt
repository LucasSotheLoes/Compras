package com.loes.compras

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp

sealed class Incoming {
    data class Text(val text: String) : Incoming()
    data class Pdf(val uri: Uri) : Incoming()
}

sealed class Screen {
    data class ItemDetail(val id: String) : Screen()
    data class ItemEdit(val id: String?) : Screen()
    data class QuoteImport(val text: String) : Screen()
    data class NfImport(val uri: Uri) : Screen()
    data class SupplierEdit(val id: String?) : Screen()
}

class Nav {
    var stack by mutableStateOf<List<Screen>>(emptyList())
        private set

    fun push(s: Screen) { stack = stack + s }
    fun pop() { stack = stack.dropLast(1) }
}

enum class MainTab(val label: String, val icon: ImageVector) {
    ITENS("Itens", Icons.Filled.Menu),
    COMPARAR("Comparar", Icons.Filled.Star),
    REPOR("Repor", Icons.Filled.ShoppingCart),
    ENTRADA("Entrada", Icons.Filled.Add),
    MAIS("Mais", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as App).store
        if (savedInstanceState == null) incoming = parseIntent(intent)
        setContent {
            AppTheme {
                AppRoot(store, incoming, onConsumed = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = parseIntent(intent)
    }
}

fun parseIntent(i: Intent?): Incoming? {
    if (i == null) return null
    return when (i.action) {
        Intent.ACTION_SEND -> {
            val type = i.type ?: ""
            if (type.startsWith("text/")) {
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { Incoming.Text(it) }
            } else {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let { Incoming.Pdf(it) }
            }
        }
        Intent.ACTION_VIEW -> i.data?.let { Incoming.Pdf(it) }
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(store: Store, incoming: Incoming?, onConsumed: () -> Unit) {
    val nav = remember { Nav() }
    var tab by rememberSaveable { mutableStateOf(MainTab.ITENS) }

    LaunchedEffect(incoming) {
        when (incoming) {
            is Incoming.Text -> nav.push(Screen.QuoteImport(incoming.text))
            is Incoming.Pdf -> nav.push(Screen.NfImport(incoming.uri))
            null -> Unit
        }
        if (incoming != null) onConsumed()
    }

    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    when (val top = nav.stack.lastOrNull()) {
        is Screen.ItemDetail -> ItemDetailScreen(store, nav, top.id)
        is Screen.ItemEdit -> ItemEditScreen(store, nav, top.id)
        is Screen.QuoteImport -> QuoteImportScreen(store, nav, top.text)
        is Screen.NfImport -> NfImportScreen(store, nav, top.uri)
        is Screen.SupplierEdit -> SupplierEditScreen(store, nav, top.id)
        null -> Scaffold(
            topBar = { TopAppBar(title = { Text(if (tab == MainTab.ITENS) "Compras LOES" else tab.label) }) },
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { p ->
            Box(Modifier.padding(p).fillMaxSize()) {
                when (tab) {
                    MainTab.ITENS -> ItemsScreen(store, nav)
                    MainTab.COMPARAR -> CompareScreen(store, nav)
                    MainTab.REPOR -> RestockScreen(store)
                    MainTab.ENTRADA -> EntradaScreen(nav)
                    MainTab.MAIS -> MoreScreen(store, nav)
                }
            }
        }
    }
}

@Composable
fun EntradaScreen(nav: Nav) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) nav.push(Screen.NfImport(uri))
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("PDF: nota fiscal ou orçamento", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "DANFE vira compra (histórico + estoque). Orçamento/pedido do fornecedor vira cotação para comparar. " +
                        "Dica: no WhatsApp ou no e-mail, toque no PDF → Compartilhar → Compras LOES.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) { Text("Escolher PDF") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cotação do WhatsApp", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Cole a mensagem do vendedor (uma linha por produto com o preço). " +
                        "Também dá pra selecionar a mensagem no WhatsApp → Compartilhar → Compras LOES.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Texto da cotação") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { text = clipboard.getText()?.text ?: text }) { Text("Colar") }
                    Button(enabled = text.isNotBlank(), onClick = { nav.push(Screen.QuoteImport(text)); text = "" }) {
                        Text("Ler cotação")
                    }
                }
            }
        }
    }
}

package com.loes.compras

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.text.Normalizer
import java.text.SimpleDateFormat
import kotlin.math.abs
import kotlin.math.max

// ---------- Números e texto ----------

/** "1.234,56" → 1234.56 ; "18,90" → 18.9 ; "18.90" → 18.9 */
fun parseBr(s: String): Double? {
    var t = s.replace("R$", "").replace(" ", "").replace(" ", "").trim()
    if (t.isEmpty()) return null
    if (t.contains(',')) {
        t = t.replace(".", "").replace(',', '.')
    } else if (t.count { it == '.' } > 1) {
        t = t.replace(".", "")
    }
    return t.toDoubleOrNull()
}

private val STOPWORDS = setOf("de", "do", "da", "dos", "das", "c", "com", "p", "para", "x", "e", "un", "und", "pc", "pca", "cada")

fun normalize(s: String): String {
    val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return noAccents.lowercase()
        .replace(Regex("(\\d)([a-z])"), "$1 $2")
        .replace(Regex("([a-z])(\\d)"), "$1 $2")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotBlank() && it !in STOPWORDS }.toSet()

/**
 * Semelhança 0..1 entre dois nomes de produto. Números (bitola, comprimento) precisam
 * ser compatíveis: "tubo 25" nunca casa com "tubo 32".
 */
fun similarity(a: String, b: String): Double {
    val ta = tokens(a)
    val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    val na = ta.filter { t -> t.any { it.isDigit() } }.toSet()
    val nb = tb.filter { t -> t.any { it.isDigit() } }.toSet()
    if (na.isNotEmpty() && nb.isNotEmpty() && !nb.containsAll(na) && !na.containsAll(nb)) return 0.0
    var match = 0.0
    for (t in ta) {
        if (t in tb) {
            match += 1.0
        } else if (t.length >= 3 && tb.any { o -> o.length >= 3 && (o.startsWith(t) || t.startsWith(o)) }) {
            match += 0.8 // abreviação: "sold" ~ "soldavel"
        }
    }
    return (2.0 * match) / (ta.size + tb.size)
}

fun Store.matchItem(text: String, minScore: Double = 0.5): Item? {
    val n = normalize(text)
    if (n.isEmpty()) return null
    items.firstOrNull { it.aliases.contains(n) }?.let { return it }
    return items
        .map { it to max(similarity(text, it.name), it.aliases.maxOfOrNull { a -> similarity(text, a) } ?: 0.0) }
        .filter { it.second >= minScore }
        .maxByOrNull { it.second }
        ?.first
}

fun codeKey(supplierKey: String, code: String) = "$supplierKey|${code.trim().uppercase()}"
fun digits(s: String) = s.filter { it.isDigit() }

// ---------- Cotação colada do WhatsApp ----------

data class QuoteLine(val raw: String, val desc: String, val price: Double)

// "[22/11/2026 14:03] Fulano: " ou "22/11/2026 14:03 - Fulano: "
private val waPrefix = Regex("""^\[?\d{1,2}/\d{1,2}(/\d{2,4})?,?\s+\d{1,2}:\d{2}(:\d{2})?]?\s*(-\s*)?[^:]{1,40}:\s*""")
private val priceRe = Regex("""(?<![\d.,])(\d{1,3}(?:\.\d{3})+,\d{2}|\d+,\d{2}|\d+\.\d{2})(?![\d.,])""")
private val unitWords = Regex("""(?iu)\b(cada|unit[aá]rio|unid\.?|und\.?|un\.?|p[cç]\.?|o\s+metro|o\s+m)(?=\s|$)""")

fun parseQuote(text: String): List<QuoteLine> = text.lines().mapNotNull { line0 ->
    val line = line0.replace(waPrefix, "").trim()
    if (line.isEmpty()) return@mapNotNull null
    // Linha de tabela (orçamento colado): "9214 REG. GAVETA 3/4" PC 5,00 41,000 0,0 205,000"
    tableLine(line)?.let { t -> return@mapNotNull QuoteLine(line, t.desc, t.vUnit) }
    val m = priceRe.findAll(line).lastOrNull() ?: return@mapNotNull null
    val price = parseBr(m.value) ?: return@mapNotNull null
    if (price <= 0) return@mapNotNull null
    var desc = line.removeRange(m.range).replace("R$", " ").replace(unitWords, " ")
    desc = desc.replace(Regex("\\s+"), " ")
        .trim(' ', '-', ':', '=', '.', '*', '_', '>', '–', '|', '/', ',', '$')
        .trim()
    if (desc.length < 2) null else QuoteLine(line, desc, price)
}

// ---------- DANFE em PDF ----------

data class NfLine(
    val code: String,
    val desc: String,
    val unit: String,
    val qty: Double,
    val vUnit: Double,
    val vTotal: Double,
    val vIpi: Double,
)

data class NfParsed(
    val cnpjEmitente: String?,
    val numero: String?,
    val data: Long?,
    val lines: List<NfLine>,
    val rawText: String,
    /** true = orçamento/pedido do fornecedor (vira cotação, não compra). */
    val isQuote: Boolean = false,
    /** Identifica o fornecedor no documento: CNPJ, ou telefone+vendedor quando não há CNPJ. */
    val docKey: String? = null,
    /** Prazo de pagamento lido do documento (ex.: "BLOQUETO 28", "28/56 DDL" → média). */
    val prazo: Int? = null,
)

fun readPdfText(ctx: Context, uri: Uri): String {
    val input = ctx.contentResolver.openInputStream(uri) ?: error("Não consegui abrir o arquivo")
    return input.use { stream ->
        PDDocument.load(stream).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.getText(doc)
        }
    }
}

// Linha de item: <código> <descrição> <NCM 8 dígitos> <CST> <CFOP> <UN> <números...>
private val itemRe = Regex(
    """^(.*?)\s(\d{4}\.?\d{2}\.?\d{2})\s+(\d{2,4})\s+([1-7]\d{3})\s+([A-Za-z][A-Za-z0-9]{0,5}\.?)\s+(.*)$"""
)
private val numTok = Regex("""^-?(\d{1,3}(\.\d{3})+|\d+)(,\d+)?$""")
private val dateRe = Regex("""\b(\d{2}/\d{2}/\d{4})\b""")
private val cnpjRe = Regex("""\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}""")
private val chaveRe = Regex("""(?<!\d)((?:\d{4}[ .]?){10}\d{4})(?!\d)""")
private val endMarkers = listOf("DADOS ADICIONAIS", "ISSQN", "INFORMAÇÕES COMPLEMENTARES", "INFORMACOES COMPLEMENTARES", "RESERVADO AO FISCO")
private val headerWords = listOf("CÓDIGO", "CODIGO", "DESCRI", "NCM", "CFOP", "FOLHA", "PÁGINA", "PAGINA", "DANFE", "CONTINUA")

/**
 * Escolhe quantidade, valor unitário, valor total e valor do IPI entre os números da linha.
 * Confere se qtd × unitário ≈ total (com ou sem coluna de desconto no meio).
 */
private fun pickValues(n: List<Double>): List<Double>? {
    if (n.size < 3) return null
    val q = n[0]
    val vu = n[1]
    if (q <= 0) return null
    for (k in 2 until minOf(n.size, 4)) {
        val vt = n[k]
        val tol = max(0.05, vt * 0.02)
        val ok = abs(q * vu - vt) <= tol ||
            (k == 3 && abs(q * vu - n[2] - vt) <= tol) // coluna de desconto entre unitário e total
        if (ok && vt > 0) {
            val rest = n.drop(k + 1) // BC ICMS, V.ICMS, V.IPI, ALÍQ.ICMS, ALÍQ.IPI
            val ipi = if (rest.size >= 5) rest[2] else 0.0
            return listOf(q, vu, vt, ipi)
        }
    }
    return null
}

/**
 * Linha de tabela genérica (orçamentos e pedidos de ERP). Exemplos reais:
 *   9214 REG. GAVETA DOCOL BASE 3/4" PC 5,00 41,000 0,0 205,000
 *   968276 90251306000 REGISTRO GAVETA 3/4" - DOCOL DOCOL 84818093 UN 0,00% R$ 0,00 0,00% R$ 0,00 5 R$ 37,90 R$ 189,50
 * Pega a sequência de números no fim da linha e procura qtd × unitário ≈ total.
 */
fun tableLine(line0: String): NfLine? {
    val line = line0.replace("_x000D_", " ").replace("R$", " ").replace("%", " ")
    val toks = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var i = toks.size
    while (i > 0 && numTok.matches(toks[i - 1])) i--
    val nums = toks.subList(i, toks.size).mapNotNull { parseBr(it) }
    if (nums.size < 3) return null
    var head = toks.subList(0, i)
    var unit = "UN"
    if (head.size >= 2 && Regex("^[A-Za-z]{1,4}\\.?$").matches(head.last())) {
        unit = head.last().trimEnd('.').uppercase()
        head = head.dropLast(1)
    }
    // Coluna de NCM logo antes da unidade
    if (head.size >= 2 && Regex("^\\d{4}\\.?\\d{2}\\.?\\d{2}$").matches(head.last())) head = head.dropLast(1)
    if (head.isEmpty()) return null

    // Total costuma ser a última coluna; qtd e unitário são colunas vizinhas antes dele.
    for (k in nums.indices.reversed()) {
        val vt = nums[k]
        if (vt <= 0) continue
        val tol = max(0.05, vt * 0.02)
        for (qi in 0 until k - 1) {
            val q = nums[qi]
            val vu = nums[qi + 1]
            if (q <= 0 || vu <= 0 || abs(q * vu - vt) > tol) continue
            val before = nums.subList(0, qi)
            val between = nums.subList(qi + 2, k)
            var ipi = 0.0
            if (between.size == 1 && between[0] in 0.0..30.0) {
                ipi = vt * between[0] / 100.0 // IPI % entre unitário e total
            } else if (before.size >= 4) {
                // IPI% IPI R$ ST% ST R$ antes da quantidade: decide se o R$ é por unidade ou da linha
                fun valor(pct: Double, v: Double) =
                    if (v <= 0) 0.0 else if (abs(v - vt * pct / 100) <= abs(v - vu * pct / 100)) v else v * q
                ipi = valor(before[0], before[1]) + valor(before[2], before[3])
            }
            val first = head[0]
            val code = if (head.size >= 2 && first.any { it.isDigit() } && first.length <= 18) first else ""
            var rest = if (code.isNotEmpty()) head.drop(1) else head
            // Segundo código (código do fabricante/fornecedor), ex.: 90251306000, CF211, 002375-2
            if (rest.size >= 2 && rest[0].any { it.isDigit() } && Regex("^[A-Z0-9./\\-]+$").matches(rest[0])) rest = rest.drop(1)
            val desc = rest.joinToString(" ").trim().trim('-', ' ')
            if (desc.count { it.isLetter() } < 2) return null
            return NfLine(code, desc, unit, q, vu, vt, ipi)
        }
    }
    return null
}

private val prazoRe = Regex("""(?i)(?:bloqueto|boleto|ddl|duplicata|plano)[ \t:\-]{0,5}(\d{1,3}(?:\s*/\s*\d{1,3})*)(?!\d)""")
private val prazoDiasRe = Regex("""(?i)(?<![\d,.])(\d{1,3}(?:\s*/\s*\d{1,3})*)\s*(?:dias|ddl|dd)\b""")
private val foneRe = Regex("""(?i)fone\s*:?\s*([\d()\s\-]{8,20})""")
private val vendRe = Regex("""(?i)vend(?:as|edor)\s*:?\s*(\d+)""")
private val cnpjLabelRe = Regex("""(?i)cnpj\s*:?\s*(\d{14})(?!\d)""")
private val orcNumRe = Regex("""(?iu)(?:or[çc]amento|pedido)[ \t]*(?:n[º°o.]*)?[ \t]*:?[ \t]*(\d{3,})""")

private fun parsePrazo(text: String): Int? {
    // Prefere "BLOQUETO 28" / "BOLETO 28/56"; depois "28/56 DDL" ou "30 dias".
    // Ignora "válido por 2 dias" (validade do orçamento, não prazo).
    val candidates = prazoRe.findAll(text).map { it.groupValues[1] } +
        prazoDiasRe.findAll(text)
            .filterNot { m -> text.substring(maxOf(0, m.range.first - 12), m.range.first).contains("lido", ignoreCase = true) }
            .map { it.groupValues[1] }
    for (c in candidates) {
        val parts = c.split('/').mapNotNull { it.trim().toIntOrNull() }.filter { it in 7..180 }
        if (parts.isNotEmpty()) return parts.average().toInt()
    }
    return null
}

private fun looksLikeContinuation(l: String): Boolean {
    if (l.length > 90) return false
    val up = l.uppercase()
    if (headerWords.any { up.contains(it) }) return false
    val digitsCount = l.count { it.isDigit() }
    val letters = l.count { it.isLetter() }
    return letters >= 3 && digitsCount <= letters
}

fun parseDanfe(text: String): NfParsed {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val out = mutableListOf<NfLine>()
    var cont = 0
    var ended = false

    for (l in lines) {
        val up = l.uppercase()
        if (out.isNotEmpty() && endMarkers.any { up.contains(it) }) ended = true

        val m = itemRe.find(l)
        if (m != null) {
            val nums = m.groupValues[6].split(Regex("\\s+")).filter { numTok.matches(it) }.mapNotNull { parseBr(it) }
            val v = pickValues(nums)
            if (v != null) {
                val head = m.groupValues[1].trim()
                val sp = head.indexOf(' ')
                val code = if (sp > 0) head.substring(0, sp) else head
                val desc = if (sp > 0) head.substring(sp + 1).trim() else ""
                out += NfLine(
                    code = code,
                    desc = desc.ifBlank { code },
                    unit = m.groupValues[5].trimEnd('.').uppercase(),
                    qty = v[0], vUnit = v[1], vTotal = v[2], vIpi = v[3],
                )
                ended = false
                cont = 0
                continue
            }
        }
        if (out.isNotEmpty() && !ended && cont < 2 && looksLikeContinuation(l)) {
            val last = out.removeAt(out.size - 1)
            out += last.copy(desc = (last.desc + " " + l).trim())
            cont++
        } else if (out.isNotEmpty()) {
            cont = 2 // linha não relacionada: para de emendar descrição
        }
    }

    // Chave de acesso (44 dígitos): cUF(2) AAMM(4) CNPJ(14) mod(2) série(3) nNF(9) ...
    // Sem tabela de DANFE: tenta o layout genérico de orçamento/pedido de ERP
    val danfeTable = out.isNotEmpty()
    if (!danfeTable) out += lines.mapNotNull { tableLine(it) }

    var cnpj: String? = null
    var numero: String? = null
    var temChave = false
    chaveRe.findAll(text).map { digits(it.value) }.firstOrNull { it.length == 44 }?.let { chave ->
        cnpj = chave.substring(6, 20)
        numero = chave.substring(25, 34).trimStart('0')
        temChave = true
    }
    if (cnpj == null) cnpj = cnpjRe.find(text)?.value?.let(::digits)
        ?: cnpjLabelRe.find(text)?.groupValues?.get(1) // "Cnpj: 00138093000549" (1º = emitente)

    val upText = text.uppercase()
    val emissIdx = upText.indexOf("EMISS").coerceAtMost(text.length)
    val dateStr = (if (emissIdx >= 0) dateRe.find(text, emissIdx) else null)?.groupValues?.get(1)
        ?: dateRe.find(text)?.groupValues?.get(1)
    val data = dateStr?.let { runCatching { SimpleDateFormat("dd/MM/yyyy", BR).parse(it)?.time }.getOrNull() }

    val pareceOrcamento = listOf("ORÇAMENTO", "ORCAMENTO", "PEDIDO", "COTAÇÃO", "COTACAO", "PROPOSTA").any { upText.contains(it) }
    val isQuote = !temChave && (pareceOrcamento || !danfeTable)
    if (isQuote) orcNumRe.find(text)?.let { numero = it.groupValues[1] }

    // Sem CNPJ no documento: identifica o fornecedor pelo telefone + código do vendedor
    val docKey = cnpj ?: run {
        val fone = foneRe.find(text)?.groupValues?.get(1)?.let(::digits)?.takeIf { it.length >= 8 }
        val vend = vendRe.find(text)?.groupValues?.get(1)
        if (fone == null && vend == null) null else "doc:${fone ?: ""}:${vend ?: ""}"
    }

    return NfParsed(cnpj, numero, data, out, text, isQuote = isQuote, docKey = docKey, prazo = parsePrazo(text))
}

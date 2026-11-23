# Compras LOES

App Android, offline e de uso pessoal, para otimizar as compras da LOES Material de Construção com os fornecedores.

## O que faz

**Entrada sem digitar**
- **PDF da DANFE**: no WhatsApp ou e-mail, toque no PDF → Compartilhar → *Compras LOES* (ou aba Entrada → Escolher PDF). O app lê código, descrição, quantidade, valor unitário, total e IPI de cada item. Pela chave de acesso ele pega o CNPJ do emitente e o número da nota, reconhece o fornecedor sozinho a partir da 2ª nota e avisa se a nota já foi importada.
- **Cotação do WhatsApp**: selecione a mensagem do vendedor → Compartilhar → *Compras LOES* (ou cole na aba Entrada). Cada linha com preço vira um item da cotação, por exemplo `Tubo soldável 25mm Tigre - R$ 18,90`.
- **Aprende**: quando você liga uma linha a um item, o app guarda o texto do vendedor e o código do fornecedor. Da próxima vez, o item é reconhecido sozinho.

**Custo real, não preço de tabela**
- Custo unitário = preço + IPI + ICMS-ST + frete − desconto. Na nota, frete, ST e desconto são rateados pelo valor de cada item.
- O ranking compara pelo valor **à vista equivalente**: custo ÷ (1 + taxa)^(prazo/30). Assim, 28 dias de prazo contam a seu favor.
- O app mostra um alerta ▲ vermelho quando o custo sobe mais que o limite configurado (padrão 5%) em relação à compra anterior.

**Histórico de preços**: gráfico do custo real ao longo do tempo e o último preço de cada fornecedor, por item.

**Reposição** (aba Repor)
- *Contagem*: digite só o que contou na prateleira e ajuste o estoque mínimo ali mesmo.
- *Pedido sugerido*: o estoque estimado é a última contagem + as entradas por NF depois dela − o consumo médio, que é calculado pelos intervalos entre as compras. Quando o estoque estimado chega no mínimo, o item entra no pedido, agrupado pelo fornecedor mais barato, e um botão manda o pedido pronto no WhatsApp do vendedor.

**Backup**: Mais → Exportar/Importar (arquivo JSON). Todos os dados ficam só no celular.

## Como gerar o APK

### Opção A: GitHub (sem instalar nada)
1. Crie um repositório (pode ser privado) e suba esta pasta inteira.
2. O workflow `.github/workflows/build.yml` roda sozinho. Em **Actions → Gerar APK → (última execução) → Artifacts**, baixe `ComprasLoes-apk`.
3. Mande o `app-debug.apk` para o celular e instale. Na primeira vez, o Android pede para permitir a instalação de fontes desconhecidas.

### Opção B: Android Studio
1. File → Open → esta pasta. Espere o Gradle sincronizar.
2. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
3. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Técnico
- Kotlin 2.0.21 + Jetpack Compose (Material 3), AGP 8.7.3, Gradle 8.10.2, minSdk 26 (Android 8+).
- Persistência: um único `dados.json` em `filesDir` (escrita atômica via arquivo temporário).
- PDF: `com.tom-roush:pdfbox-android` (extração de texto offline, `sortByPosition = true`).
- O leitor da DANFE (`Parsers.kt → parseDanfe`) procura linhas no padrão `código descrição NCM CST CFOP UN qtd unit total [BC ICMS, V.ICMS, V.IPI, alíq.]` e confere se qtd × unitário ≈ total. Se o layout de algum fornecedor não for reconhecido, use "Copiar texto" na tela da nota para ajustar o regex.

## Estrutura
```
app/src/main/java/com/loes/compras/
  Model.kt          dados + JSON
  Store.kt          armazenamento local e App
  Calc.kt           custo à vista, ranking, consumo e sugestão de pedido
  Parsers.kt        números BR, semelhança de nomes, cotação WhatsApp, DANFE
  Components.kt     tema, campos, seletores, gráfico
  MainActivity.kt   navegação, abas, intents de compartilhamento, Entrada
  ItemsScreens.kt   lista, detalhe e edição de itens
  ImportScreens.kt  revisão da cotação e da nota
  OtherScreens.kt   Comparar, Repor, Mais, Fornecedor
```

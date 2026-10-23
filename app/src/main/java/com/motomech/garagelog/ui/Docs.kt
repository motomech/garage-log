package com.motomech.garagelog.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.motomech.garagelog.R
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.DOC_TYPES
import com.motomech.garagelog.data.Doc
import com.motomech.garagelog.data.DocFiles
import com.motomech.garagelog.data.DocPage
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.VField
import com.motomech.garagelog.data.Vehicle
import com.motomech.garagelog.data.addMonths
import com.motomech.garagelog.data.deleteDoc
import com.motomech.garagelog.data.docStatus
import com.motomech.garagelog.data.fdate
import com.motomech.garagelog.data.replaceVehicle
import com.motomech.garagelog.data.saveDoc
import com.motomech.garagelog.data.today
import com.motomech.garagelog.data.usd0
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.data.vehicleDocs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/* ---------------- on the vehicle screen ---------------- */

private fun Vehicle.detailRows(): List<Pair<String, String>> = buildList {
    if (plate.isNotBlank()) add("License plate" to listOf(plate, plateState).filter { it.isNotBlank() }.joinToString(" · "))
    if (vin.isNotBlank()) add("VIN" to vin)
    if (color.isNotBlank()) add("Color" to color)
    if (engine.isNotBlank()) add("Engine" to engine)
    if (tires.isNotBlank()) add("Tire size" to tires)
    if (oil.isNotBlank()) add("Oil" to oil)
    if (insurer.isNotBlank() || policy.isNotBlank()) add("Insurance" to listOf(insurer, policy).filter { it.isNotBlank() }.joinToString(" · "))
    bought?.let { add("Purchased" to fdate(it)) }
    price?.let { add("Price paid" to usd0(it)) }
    custom.filter { it.label.isNotBlank() || it.value.isNotBlank() }.forEach { add(it.label.ifBlank { "Other" } to it.value) }
    if (notes.isNotBlank()) add("Notes" to notes)
}

@Composable
private fun SectionHead(title: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { H2(title) }
        TextButton(onClick = onClick) { Text(action, color = G.accent, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun VehicleDetails(v: Vehicle) {
    val nav = LocalNav.current
    val clip = LocalClipboardManager.current
    val rows = v.detailRows()
    SectionHead("Details", "Edit") { nav.sheet(SheetReq.Details(v.id)) }
    ListBox {
        if (rows.isEmpty()) Empty("No details yet. Tap Edit to add the plate, VIN, insurance and more.")
        rows.forEachIndexed { i, (label, value) ->
            val isVin = label == "VIN"
            ListRow(
                label, first = i == 0,
                onClick = if (isVin) ({ clip.setText(AnnotatedString(value)); nav.toast("VIN copied") }) else null,
                extra = {
                    Text(value, fontSize = 15.sp, color = G.ink, fontWeight = FontWeight.Medium,
                        fontFamily = if (isVin) FontFamily.Monospace else FontFamily.Default)
                },
            ) { if (isVin) Sub("Tap to copy") }
        }
    }
}

@Composable
fun VehicleDocuments(d: AppData, v: Vehicle) {
    val nav = LocalNav.current
    val docs = d.vehicleDocs(v.id)
    SectionHead("Documents", "+ Add") { nav.sheet(SheetReq.DocS(null, v.id)) }
    if (docs.isEmpty()) {
        ListBox { Empty("Keep the registration, insurance card, title and receipts here. Tap Add to take a photo or attach a PDF.") }
        return
    }
    docs.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { doc -> DocTile(doc, Modifier.weight(1f)) { nav.open(Page.DocP(doc.id)) } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DocTile(doc: Doc, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(G.card).border(1.dp, G.line, shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(76.dp).background(G.chip), contentAlignment = Alignment.Center) {
            val first = doc.pages.firstOrNull()
            if (first != null) PageImage(first, 0, 300, Modifier.fillMaxSize(), ContentScale.Crop)
            else GIcon(R.drawable.ic_box, 28.dp, G.muted)
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(doc.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val st = docStatus(doc)
            if (st != null) Pill(st.second, st.first)
            else Pill(if (doc.pages.any { it.pdf }) "PDF" else "${doc.pages.size} photo${if (doc.pages.size == 1) "" else "s"}")
        }
    }
}

/** A photo, or one page of a PDF, drawn off the main thread. */
@Composable
fun PageImage(p: DocPage, index: Int, widthPx: Int, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, p, index, widthPx) {
        value = withContext(Dispatchers.IO) { DocFiles.render(ctx, p, index, widthPx) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = scale)
    else Box(modifier.background(G.chip))
}

/* ---------------- edit details ---------------- */

@Composable
fun DetailsForm(d: AppData, vid: Long, onClose: () -> Unit) {
    val nav = LocalNav.current
    val v = d.vehicle(vid)
    if (v == null) {
        LaunchedEffect(vid) { onClose() }
        return
    }
    var plate by remember { mutableStateOf(v.plate) }
    var state by remember { mutableStateOf(v.plateState) }
    var vin by remember { mutableStateOf(v.vin) }
    var color by remember { mutableStateOf(v.color) }
    var engine by remember { mutableStateOf(v.engine) }
    var tires by remember { mutableStateOf(v.tires) }
    var oil by remember { mutableStateOf(v.oil) }
    var insurer by remember { mutableStateOf(v.insurer) }
    var policy by remember { mutableStateOf(v.policy) }
    var bought by remember { mutableStateOf(v.bought) }
    var price by remember { mutableStateOf(v.price?.plain() ?: "") }
    var notes by remember { mutableStateOf(v.notes) }
    val custom = remember { mutableStateListOf<VField>().apply { addAll(v.custom) } }

    SheetTitle("${v.label} details")
    Hint("Every field is optional. Leave blank what you don't need.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField("License plate", plate, { plate = it.uppercase() }, Modifier.weight(2f))
        TextField("State", state, { state = it.uppercase().take(3) }, Modifier.weight(1f))
    }
    TextField("VIN", vin, { vin = it.uppercase().filter { c -> !c.isWhitespace() }.take(17) }, Modifier.fillMaxWidth())
    if (vin.isNotEmpty() && vin.length != 17) Hint("A VIN is 17 characters (${vin.length} so far). Older vehicles can have fewer.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField("Color", color, { color = it }, Modifier.weight(1f))
        TextField("Engine", engine, { engine = it }, Modifier.weight(1f), "5.3L V8")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField("Tire size", tires, { tires = it }, Modifier.weight(1f), "275/60R20")
        TextField("Oil", oil, { oil = it }, Modifier.weight(1f), "0W-20, 8 qt")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField("Insurance company", insurer, { insurer = it }, Modifier.weight(1f))
        TextField("Policy number", policy, { policy = it }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OptDate("Purchase date", bought, { bought = it }, Modifier.weight(1f)) { today() }
        NumField("Price paid", price, { price = it }, Modifier.weight(1f))
    }
    TextField("Notes", notes, { notes = it }, Modifier.fillMaxWidth())
    custom.forEachIndexed { i, f ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField("Field name", f.label, { custom[i] = f.copy(label = it) }, Modifier.weight(1f), "Gate code")
            TextField("Value", f.value, { custom[i] = f.copy(value = it) }, Modifier.weight(1f))
            TextButton(onClick = { custom.removeAt(i) }) { Text("✕", color = G.muted) }
        }
    }
    GhostButton("+ Add a field", Modifier.fillMaxWidth()) { custom.add(VField("", "")) }
    PrimaryButton("Save") {
        val cur = Repo.current.vehicle(vid) ?: return@PrimaryButton
        Repo.update {
            it.replaceVehicle(cur.copy(
                plate = plate.trim(), plateState = state.trim(), vin = vin.trim(), color = color.trim(), engine = engine.trim(),
                tires = tires.trim(), oil = oil.trim(), insurer = insurer.trim(), policy = policy.trim(), bought = bought,
                price = price.toNum(), notes = notes.trim(),
                custom = custom.map { f -> VField(f.label.trim(), f.value.trim()) }.filter { f -> f.label.isNotEmpty() || f.value.isNotEmpty() },
            ))
        }
        nav.toast("Details saved"); onClose()
    }
}

/** A date that can be left empty: a button to set it, then the date with a way to clear it. */
@Composable
private fun OptDate(label: String, value: String?, onChange: (String?) -> Unit, modifier: Modifier, default: () -> String) {
    if (value == null) GhostButton("Set ${label.lowercase()}", modifier) { onChange(default()) }
    else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DateField(value, { onChange(it) }, Modifier.weight(1f), label)
        TextButton(onClick = { onChange(null) }) { Text("✕", color = G.muted) }
    }
}

/* ---------------- add or edit a document ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocForm(d: AppData, req: SheetReq.DocS, onClose: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = req.doc
    var type by remember { mutableStateOf(e?.type ?: DOC_TYPES.first()) }
    var name by remember { mutableStateOf(e?.name ?: "") }
    var expires by remember { mutableStateOf(e?.expires) }
    var remind by remember { mutableStateOf(e?.remind ?: true) }
    val pages = remember { mutableStateListOf<DocPage>().apply { e?.pages?.let { addAll(it) } } }
    var busy by remember { mutableStateOf(false) }
    var shotUri by remember { mutableStateOf<Uri?>(null) }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            val got = withContext(Dispatchers.IO) { uris.mapNotNull { DocFiles.import(ctx, it) } }
            pages.addAll(got)
            if (got.size < uris.size) nav.toast("Couldn't read ${uris.size - got.size} of the files")
            busy = false
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shotUri?.let { add(listOf(it)) } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }

    SheetTitle(if (e == null) "Add a document" else "Edit document")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Take photo", Modifier.weight(1f)) {
            runCatching { DocFiles.cameraUri(ctx).also { shotUri = it; camera.launch(it) } }.onFailure { nav.toast("No camera app found") }
        }
        GhostButton("From photos", Modifier.weight(1f)) {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        GhostButton("PDF or file", Modifier.weight(1f)) { files.launch(arrayOf("application/pdf", "image/*")) }
    }
    if (busy) Hint("Adding…")
    if (pages.isNotEmpty()) {
        Hint(if (pages.size == 1) "1 page. Take or add more for the back side." else "${pages.size} pages, kept in this order.")
        pages.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(8.dp)).background(G.chip)) {
                        PageImage(p, 0, 240, Modifier.fillMaxSize(), ContentScale.Crop)
                        if (p.pdf) Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) { Pill("PDF") }
                        TextButton(onClick = { pages.remove(p) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                            Text("✕", color = G.ink, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Sub("Type")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DOC_TYPES.forEach { t ->
            FilterChip(
                selected = type == t, onClick = { type = t }, label = { Text(t, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = G.ink, selectedLabelColor = G.bg),
            )
        }
    }
    TextField("Name (optional)", name, { name = it }, Modifier.fillMaxWidth(), "$type ${today().take(4)}")
    OptDate("Expiry date", expires, { expires = it }, Modifier.fillMaxWidth()) { addMonths(today(), 12) }
    if (expires != null) CheckRow("Remind me 30 days before it expires", remind) { remind = it }
    PrimaryButton(if (e == null) "Save document" else "Save changes") {
        if (busy) { nav.toast("Still adding the photos…"); return@PrimaryButton }
        if (pages.isEmpty()) { nav.toast("Take a photo or attach a file first."); return@PrimaryButton }
        val doc = Doc(e?.id ?: 0, req.vid, type, name.trim(), pages.toList(), e?.added ?: today(), expires, remind)
        Repo.update { it.saveDoc(doc) }
        // Pages taken off an existing document go now; ones added and then cancelled go at the next app start.
        e?.pages?.filter { it !in pages }?.let { DocFiles.delete(ctx, it) }
        nav.toast("Document saved"); onClose()
    }
}

/* ---------------- viewing a document ---------------- */

@Composable
fun DocScreen(d: AppData, id: Long) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val doc = d.docs.firstOrNull { it.id == id }
    if (doc == null) {
        LaunchedEffect(id) { nav.open(null) }
        return
    }
    val v = d.vehicle(doc.vid)
    var askDelete by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<Pair<DocPage, Int>?>(null) }
    val all by produceState(emptyList<Pair<DocPage, Int>>(), doc) {
        value = withContext(Dispatchers.IO) { DocFiles.allPages(ctx, doc) }
    }

    TextButton(onClick = { nav.open(Page.VehicleP(doc.vid)) }) { Text("‹ " + (v?.label ?: "Garage"), color = G.ink) }
    ScreenTitle(doc.label, listOfNotNull(v?.label, doc.type.takeIf { doc.name.isNotBlank() }, "added ${fdate(doc.added)}").joinToString(" · "))
    docStatus(doc)?.let { (level, text) ->
        Note(text + if (doc.remind && doc.expires != null) " · reminder 30 days before" else "", level)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Share", Modifier.weight(1f)) { share(ctx, doc) }
        GhostButton("Edit", Modifier.weight(1f)) { nav.sheet(SheetReq.DocS(doc, doc.vid)) }
        GhostButton("Delete", Modifier.weight(1f)) { askDelete = true }
    }
    if (all.size > 1) Hint("${all.size} pages. Tap a page to zoom.") else Hint("Tap to zoom.")
    all.forEach { (p, i) ->
        PageImage(p, i, 1200, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, G.line, RoundedCornerShape(10.dp))
            .clickable { zoom = p to i }, ContentScale.FillWidth)
    }
    zoom?.let { (p, i) -> ZoomDialog(p, i) { zoom = null } }
    if (askDelete) Confirm("Delete ${doc.label}?", "The photos and files are removed from the phone. This can't be undone.", "Delete",
        onYes = {
            Repo.update { it.deleteDoc(doc.id) }
            DocFiles.delete(ctx, doc.pages)
            nav.toast("Document deleted"); nav.open(Page.VehicleP(doc.vid))
        }) { askDelete = false }
}

private fun share(ctx: android.content.Context, doc: Doc) {
    val uris = DocFiles.shareUris(ctx, doc)
    if (uris.isEmpty()) return
    val mime = when {
        doc.pages.all { it.pdf } -> "application/pdf"
        doc.pages.none { it.pdf } -> "image/jpeg"
        else -> "*/*"
    }
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    send.type = mime
    send.putExtra(Intent.EXTRA_SUBJECT, doc.label)
    send.clipData = ClipData.newRawUri(doc.label, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share ${doc.label}")) }
}

/** Full screen page with pinch to zoom and drag to move. */
@Composable
private fun ZoomDialog(p: DocPage, index: Int, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PageImage(p, index, 2000, Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text("Close", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

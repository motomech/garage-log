package com.motomech.garagelog.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motomech.garagelog.data.AppData
import com.motomech.garagelog.data.Level
import com.motomech.garagelog.data.activeId
import com.motomech.garagelog.data.isConnected
import com.motomech.garagelog.data.mi1
import com.motomech.garagelog.data.vehicle

val Big = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)

@Composable
fun GIcon(@DrawableRes id: Int, size: Dp = 22.dp, tint: Color = Color.Unspecified) =
    Icon(painterResource(id), contentDescription = null, modifier = Modifier.size(size),
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint)

@Composable
fun ScreenTitle(text: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text, style = Big.copy(fontSize = 34.sp), color = G.ink)
        if (sub != null) Sub(sub)
    }
}

@Composable
fun Sub(text: String, modifier: Modifier = Modifier, color: Color = G.muted) =
    Text(text, fontSize = 14.sp, color = color, modifier = modifier)

@Composable
fun H2(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = G.ink,
    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable
fun GCard(
    modifier: Modifier = Modifier,
    bg: Color = G.card,
    border: Color = G.line,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape, color = bg, border = BorderStroke(if (border == G.ok) 2.dp else 1.dp, border),
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
fun Pill(text: String, level: Level? = null, live: Boolean = false, @DrawableRes icon: Int? = null) {
    val (bg, fg) = when {
        live || level == Level.OK -> G.okBg to G.ok
        level == Level.WARN -> G.warnBg to G.warn
        level == Level.BAD -> G.badBg to G.bad
        else -> G.chip to G.muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        if (icon != null) GIcon(icon, 12.dp, fg)
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Note(text: String, level: Level?, modifier: Modifier = Modifier) {
    val (bg, fg) = when (level) {
        Level.OK -> G.okBg to G.ok
        Level.WARN -> G.warnBg to G.warn
        Level.BAD -> G.badBg to G.bad
        null -> G.chip to G.ink
    }
    Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
fun Tiles(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun RowScope.Tile(label: String, value: String, unit: String? = null, foot: String? = null) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(G.card)
            .border(1.dp, G.line, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Sub(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Big.copy(fontSize = 28.sp), color = G.ink)
            if (unit != null) Sub(" $unit", Modifier.padding(bottom = 4.dp))
        }
        if (foot != null) Sub(foot)
    }
}

/** A bordered list of rows, like the simulator's .list. */
@Composable
fun ListBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
fun ListRow(
    title: String,
    sub: String? = null,
    value: String? = null,
    valueSub: String? = null,
    first: Boolean = false,
    onClick: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    if (!first) HorizontalDivider(color = G.line)
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
            if (sub != null) Sub(sub)
            extra()
        }
        Column(horizontalAlignment = Alignment.End) {
            if (value != null) Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
            if (valueSub != null) Sub(valueSub)
            trailing()
        }
    }
}

@Composable
fun Empty(text: String) = Text(text, color = G.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))

@Composable
fun Hint(text: String) = Text(text, color = G.muted, fontSize = 13.sp)

/** Vehicle picker chip for the Fuel, Service and Parts headers. Shows "Connected" or "Active" under the active vehicle. */
@Composable
fun VehicleChip(d: AppData, vid: Long?, allowAll: Boolean = false, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = d.activeId()
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(G.chip).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(if (vid == null) "All vehicles" else d.vehicle(vid)?.label ?: "", fontWeight = FontWeight.SemiBold,
                    color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                if (vid != null && vid == active) Text(if (d.isConnected(vid)) "Connected" else "Active", fontSize = 11.sp, color = G.ok)
            }
            Text("  ▾", color = G.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowAll) DropdownMenuItem(text = { Text("All vehicles") }, onClick = { open = false; onPick(null) })
            d.vehicles.forEach { v ->
                val tag = if (v.id == active) (if (d.isConnected(v.id)) " (connected)" else " (active)") else ""
                DropdownMenuItem(text = { Text(v.label + tag) }, onClick = { open = false; onPick(v.id) })
            }
        }
    }
}

@Composable
fun TripBar(d: AppData, onEnd: () -> Unit) {
    val t = d.trip ?: return
    val v = d.vehicle(t.vid) ?: return
    val mins = ((System.currentTimeMillis() - t.startedAt) / 60000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(G.ink).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Trip in progress · ${v.label}", color = G.bg.copy(alpha = 0.8f), fontSize = 13.sp)
            Text("${mi1(t.miles)} mi", color = G.bg, style = Big.copy(fontSize = 30.sp))
            Text("$mins min · ends when ${v.btName ?: "Bluetooth"} disconnects", color = G.bg.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        TextButton(onClick = onEnd) { Text("End trip", color = G.bg) }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) =
    OutlinedTextField(
        value = value, onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )

@Composable
fun TextField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } }, modifier = modifier)

/** Text field with a dropdown of common choices that still allows free typing. */
@Composable
fun SuggestField(label: String, value: String, options: List<String>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val matches = options.filter { value.isBlank() || it.contains(value, ignoreCase = true) }.filter { it != value }
    Box(modifier) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it); open = true }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton(onClick = { open = !open }) { Text("▾") } },
        )
        DropdownMenu(expanded = open && matches.isNotEmpty(), onDismissRequest = { open = false }) {
            matches.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onChange(m); open = false }) }
        }
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = modifier, border = BorderStroke(1.dp, G.line)) { Text(text, color = G.ink) }

@Composable
fun Gap(h: Dp = 8.dp) = Spacer(Modifier.size(h))

fun String.toNum(): Double? = replace(",", "").trim().toDoubleOrNull()

fun Double.plain(): String = if (this == Math.floor(this)) Math.round(this).toString() else toString()

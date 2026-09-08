package net.snehesh.endocam

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

data class UsbCommands(
    val custom: Boolean = true,
    val init: String = UsbCamera.DEFAULT_INIT.toHex(),   // EP 0x02
    val start: String = UsbCamera.DEFAULT_START.toHex(), // EP 0x01
)

/** SharedPreferences store for [Enhance] and [UsbCommands]. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("endocam", Context.MODE_PRIVATE)

    fun loadEnhance(): Enhance {
        val d = Enhance()
        return Enhance(
            enabled = p.getBoolean("enh", d.enabled),
            brightness = p.getFloat("bri", d.brightness),
            contrast = p.getFloat("con", d.contrast),
            saturation = p.getFloat("sat", d.saturation),
            sharpen = p.getFloat("shp", d.sharpen),
            denoise = p.getBoolean("dn", d.denoise),
            denoiseStrength = p.getFloat("dns", d.denoiseStrength),
            applyToFiles = p.getBoolean("files", d.applyToFiles),
            pinchZoom = p.getBoolean("zoom", d.pinchZoom),
            tapLevel = p.getBoolean("level", d.tapLevel),
            mirror = p.getBoolean("mir", d.mirror),
            flipVertical = p.getBoolean("flipv", d.flipVertical),
        )
    }

    fun save(e: Enhance) {
        p.edit()
            .putBoolean("enh", e.enabled).putFloat("bri", e.brightness).putFloat("con", e.contrast)
            .putFloat("sat", e.saturation).putFloat("shp", e.sharpen).putBoolean("dn", e.denoise)
            .putFloat("dns", e.denoiseStrength).putBoolean("files", e.applyToFiles).putBoolean("zoom", e.pinchZoom).putBoolean("level", e.tapLevel)
            .putBoolean("mir", e.mirror).putBoolean("flipv", e.flipVertical)
            .apply()
    }

    fun loadUsb(): UsbCommands {
        val d = UsbCommands()
        return UsbCommands(
            custom = p.getBoolean("usb", d.custom),
            init = p.getString("usb_init", d.init) ?: d.init,
            start = p.getString("usb_start", d.start) ?: d.start,
        )
    }

    fun save(u: UsbCommands) {
        p.edit().putBoolean("usb", u.custom).putString("usb_init", u.init).putString("usb_start", u.start).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    enhance: Enhance,
    onEnhance: (Enhance) -> Unit,
    usb: UsbCommands,
    onUsb: (UsbCommands) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionTitle("Image enhancement")
            SwitchRow("Enhance image", enhance.enabled, "Contrast, colour, noise and sharpness. Off shows the camera as it is.") {
                onEnhance(enhance.copy(enabled = it))
            }
            val on = enhance.enabled
            SliderRow("Brightness", enhance.brightness, -0.5f..0.5f, on) { onEnhance(enhance.copy(brightness = it)) }
            SliderRow("Contrast", enhance.contrast, 0.5f..2f, on) { onEnhance(enhance.copy(contrast = it)) }
            SliderRow("Saturation", enhance.saturation, 0f..2f, on) { onEnhance(enhance.copy(saturation = it)) }
            SliderRow("Sharpen", enhance.sharpen, 0f..1f, on) { onEnhance(enhance.copy(sharpen = it)) }
            SwitchRow("Reduce noise", enhance.denoise, "Blends each frame with the previous one. Fast motion smears a little.", on) {
                onEnhance(enhance.copy(denoise = it))
            }
            SliderRow("Noise reduction strength", enhance.denoiseStrength, 0f..0.8f, on && enhance.denoise) {
                onEnhance(enhance.copy(denoiseStrength = it))
            }
            SwitchRow("Apply to saved photos and videos", enhance.applyToFiles, "Off saves the original camera frames.", on) {
                onEnhance(enhance.copy(applyToFiles = it))
            }
            TextButton(onClick = { onEnhance(Enhance()) }) { Text("Reset enhancement to defaults") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionTitle("View")
            SwitchRow("Mirror image", enhance.mirror, "Flips left and right, like a mirror. Applies to saved files too.") {
                onEnhance(enhance.copy(mirror = it))
            }
            SwitchRow("Flip vertically", enhance.flipVertical, "Flips top and bottom, for a probe held upside down.") {
                onEnhance(enhance.copy(flipVertical = it))
            }
            SwitchRow("Pinch to zoom", enhance.pinchZoom, "Two fingers zoom 1x to 4x and drag to move. Digital zoom, applies to saved files too. Tap the zoom chip to reset.") {
                onEnhance(enhance.copy(pinchZoom = it))
            }
            SwitchRow("Tap to set brightness point", enhance.tapLevel, "Tap the picture and that spot is pulled to mid brightness, like tap to expose. The lens itself is fixed focus. Tap the same spot again to clear.") {
                onEnhance(enhance.copy(tapLevel = it))
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionTitle("USB commands (advanced)")
            Text(
                "Bytes sent to the camera at start. Change them only with a command captured from the vendor app. " +
                    "Going back reconnects the camera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Use these commands", usb.custom, "Off sends the built-in commands.") { onUsb(usb.copy(custom = it)) }
            HexField("Init command, endpoint 0x02 OUT", usb.init, usb.custom) { onUsb(usb.copy(init = it)) }
            HexField("Start command, endpoint 0x01 OUT", usb.start, usb.custom) { onUsb(usb.copy(start = it)) }
            TextButton(onClick = { onUsb(UsbCommands()) }) { Text("Reset USB commands to defaults") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, support: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(support, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun HexField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val bad = parseHex(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        isError = bad,
        singleLine = true,
        supportingText = { Text(if (bad) "Enter bytes as hex, for example BB AA 05 00 00" else "${parseHex(value)!!.size} bytes") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

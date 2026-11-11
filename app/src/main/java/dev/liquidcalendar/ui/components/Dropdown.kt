package dev.liquidcalendar.ui.components

// Copiado de Liquid Messages (ui/components/Dropdown.kt), que es el de Liquid Contacts sin el desplegable de cuentas.

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.liquidcalendar.R
import dev.liquidcalendar.ui.glass.DialogSpec
import dev.liquidcalendar.ui.glass.GlassButton
import dev.liquidcalendar.ui.glass.LocalDialog
import dev.liquidcalendar.ui.glass.LocalGlass
import dev.liquidcalendar.ui.glass.SettingRow
import dev.liquidcalendar.ui.glass.styled
import dev.liquidcalendar.util.str

/**
 * Fila de un desplegable: lo elegido a la vista y una flecha hacia abajo. Las opciones se abren en
 * una ventana emergente. Sin [title], lo elegido es el título (cuando arriba ya hay un rótulo).
 */
@Composable
fun DropdownRow(title: String?, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    SettingRow(
        title ?: value,
        modifier.heightIn(min = 48.dp),
        subtitle = value.takeIf { title != null },
        onClick = onClick,
        trailing = { Icon(styled(Icons.Rounded.ExpandMore), null, tint = p.contentSecondary) },
    )
}

/** Una opción dentro de la ventana; la elegida lleva la marca. */
@Composable
fun OptionRow(title: String, chosen: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    val p = LocalGlass.current
    SettingRow(
        title,
        Modifier.heightIn(min = 48.dp).semantics { selected = chosen },
        subtitle = subtitle,
        onClick = onClick,
        trailing = if (chosen) ({ Icon(styled(Icons.Rounded.Check), null, tint = p.accentFg) }) else null,
    )
}

/** Un desplegable para elegir una opción entre varias. [placeholder] es lo que dice sin nada elegido. */
@Composable
fun <T> Dropdown(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onPick: (T) -> Unit,
) {
    val dialog = LocalDialog.current
    DropdownRow(
        title,
        selected?.let(label) ?: placeholder,
        {
            dialog(
                DialogSpec(title) { dismiss ->
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(options) { option ->
                            OptionRow(label(option), option == selected, {
                                onPick(option)
                                dismiss()
                            })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                },
            )
        },
        modifier,
    )
}

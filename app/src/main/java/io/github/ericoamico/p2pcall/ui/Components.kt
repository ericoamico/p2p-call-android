package io.github.ericoamico.p2pcall.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Shape = RoundedCornerShape(12.dp)

/** Anel de foco bem visível para quem usa teclado. */
@Composable
private fun focusBorder(interaction: MutableInteractionSource, fallback: BorderStroke?): BorderStroke? {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else fallback
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * Botão de alternância: o TalkBack anuncia "ativado"/"desativado" (como o aria-pressed do web).
 * O rótulo não muda com o estado, só a cor.
 */
@Composable
fun ToggleAction(
    text: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val container = if (checked) colors.primary else colors.surfaceVariant
    val content = if (checked) colors.onPrimary else colors.onSurface
    val border = focusBorder(interaction, BorderStroke(1.dp, colors.outline))!!
    Row(
        modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(border, Shape)
            .clip(Shape)
            .background(container)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(12.dp))
        Text(text, color = content, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val m = modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
    if (primary) {
        Button(onClick, m, enabled = enabled, shape = Shape, border = focusBorder(interaction, null), interactionSource = interaction) { content() }
    } else {
        OutlinedButton(
            onClick, m, enabled = enabled, shape = Shape, interactionSource = interaction,
            border = focusBorder(interaction, BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
        ) { content() }
    }
}

/** Lista de opções (microfone, qualidade). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.padding(top = 8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = text(selected),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

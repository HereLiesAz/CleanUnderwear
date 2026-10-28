package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.hereliesaz.cleanunderwear.domain.LookupChoice
import com.hereliesaz.cleanunderwear.domain.LookupOption

/**
 * Where a run of "Fill in details" ends: for each contact the lookups found people for, every
 * person found, best fit first, with what fits and what doesn't. The user says which is them, or
 * that none is, and the next contact comes up. Only the chosen person's details are used; nothing
 * found is written to a contact until the user picks it.
 */
@Composable
fun ChoicesScreen(viewModel: MainViewModel, onNavigateBack: () -> Unit) {
    val choices by viewModel.choices.collectAsState()
    // "Decide later" moves a contact to the back of the line for this visit; it stays saved.
    var later by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val ordered = choices.sortedBy { later.indexOf(it.targetId) }
    val current = ordered.firstOrNull()

    Scaffold(
        topBar = { AppTopBar(title = "Who is it?", onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (current == null) {
            Column(Modifier.padding(padding).fillMaxSize().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Nothing left to choose.", style = MaterialTheme.typography.titleMedium)
                AppCaption("When Fill in details finds people for a contact, they wait here for you to say which is them.")
                AppAction("Done", onNavigateBack)
            }
            return@Scaffold
        }
        key(current.targetId) {
            ChoiceFor(
                choice = current,
                remaining = ordered.size,
                padding = padding,
                viewModel = viewModel,
                onLater = { later = later.filter { it != current.targetId } + current.targetId }
            )
        }
    }
}

@Composable
private fun ChoiceFor(
    choice: LookupChoice,
    remaining: Int,
    padding: PaddingValues,
    viewModel: MainViewModel,
    onLater: () -> Unit
) {
    val target by viewModel.observeTarget(choice.targetId).collectAsState(initial = null)
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val name = target?.displayName ?: choice.contactName

    LazyColumn(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppCaption("$remaining contact${if (remaining == 1) "" else "s"} to go")
                Text("Which of these is $name?", style = MaterialTheme.typography.titleLarge)
                // What the app has for them, to compare against.
                target?.let { t ->
                    val known = listOfNotNull(
                        t.phoneNumber,
                        t.email,
                        t.residenceInfo?.takeIf { it.isNotBlank() },
                        t.dateOfBirth?.let { if (it.startsWith("~")) "born about ${it.removePrefix("~")}" else "born $it" }
                    )
                    AppCaption(if (known.isEmpty()) "You have only their name." else "You have: " + known.joinToString(" · "))
                }
                AppCaption("Picking one fills in what $name is missing from it; nothing you already have is changed.")
            }
        }
        itemsIndexed(choice.options) { index, option ->
            OptionCard(option, selected = picked == index, onSelect = { picked = index })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppAction(
                    picked?.let { "That's $name" } ?: "Pick one above",
                    { picked?.let { viewModel.chooseLookup(choice.targetId, choice.options[it]) } },
                    Modifier.fillMaxWidth(),
                    enabled = picked != null
                )
                AppTextAction("None of these is $name", { viewModel.chooseLookup(choice.targetId, null) })
                if (remaining > 1) AppTextAction("Decide later", onLater)
            }
        }
    }
}

@Composable
private fun OptionCard(option: LookupOption, selected: Boolean, onSelect: () -> Unit) {
    AppCard(
        modifier = Modifier.clickable(onClick = onSelect, role = Role.RadioButton),
        emphasis = selected
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(option.name ?: "No name given", style = MaterialTheme.typography.titleMedium)
                option.dob?.let { Text(if (it.startsWith("~")) "Born about ${it.removePrefix("~")}" else "Born $it", style = MaterialTheme.typography.bodyMedium) }
                option.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                option.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (option.fits.isNotEmpty()) AppCaption("Fits: " + option.fits.joinToString(", ") { it.replaceFirstChar { c -> c.lowercase() } })
                if (option.differs.isNotEmpty()) AppCaption("Doesn't fit: " + option.differs.joinToString(", ") { it.replaceFirstChar { c -> c.lowercase() } })
                AppCaption("Found by searching their ${option.foundBy.joinToString(" and ")}")
            }
        }
    }
}

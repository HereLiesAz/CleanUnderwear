package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.hereliesaz.cleanunderwear.R
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
    val everyChoice by viewModel.choices.collectAsState()
    // A contact whose last batch was ruled out waits for the next run: not put to the user now.
    val choices = everyChoice.filter { !it.wantsMore }
    val waitingForNextRun = everyChoice.count { it.wantsMore }
    // "Decide later" moves a contact to the back of the line for this visit; it stays saved.
    var later by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val ordered = choices.sortedBy { later.indexOf(it.targetId) }
    val current = ordered.firstOrNull()

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.choices_title), onBack = onNavigateBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = screenInsets()
    ) { padding ->
        if (current == null) {
            Column(
                Modifier.padding(padding).contentWidth().fillMaxSize().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(stringResource(R.string.choices_nothing_left), style = MaterialTheme.typography.titleMedium)
                AppCaption(stringResource(R.string.choices_nothing_left_caption))
                if (waitingForNextRun > 0) AppCaption(stringResource(R.string.choices_waiting_next_run, waitingForNextRun))
                AppAction(stringResource(R.string.done), onNavigateBack)
            }
            return@Scaffold
        }
        // A new batch ("none of these" went on to the next) starts the screen afresh: nothing picked.
        key(current.targetId, current.rounds) {
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
    var confirmNone by rememberSaveable { mutableStateOf(false) }
    val name = target?.displayName ?: choice.contactName
    val bottomBar = LocalBottomBarHeight.current

    LazyColumn(
        modifier = Modifier.padding(padding).contentWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.md, top = Spacing.md, end = Spacing.md, bottom = Spacing.md + bottomBar),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppCaption(pluralStringResource(R.plurals.choices_to_go, remaining, remaining))
                Text(stringResource(R.string.choices_which_is, name), style = MaterialTheme.typography.titleLarge)
                // What the app has for them, to compare against.
                target?.let { t ->
                    val known = listOfNotNull(
                        t.phoneNumber,
                        t.email,
                        t.residenceInfo?.takeIf { it.isNotBlank() },
                        t.dateOfBirth?.let {
                            if (it.startsWith("~")) stringResource(R.string.choices_born_about_lower, it.removePrefix("~"))
                            else stringResource(R.string.choices_born_lower, it)
                        }
                    )
                    AppCaption(
                        if (known.isEmpty()) stringResource(R.string.choices_only_name)
                        else stringResource(R.string.choices_you_have, known.joinToString(" · "))
                    )
                }
                AppCaption(stringResource(R.string.choices_picking_fills, name))
                // More were found than are shown: say how many, so it is plain there are more to see.
                if (choice.foundSoFar > choice.options.size) {
                    AppCaption(stringResource(R.string.choices_showing, choice.options.size, choice.foundSoFar))
                }
            }
        }
        itemsIndexed(choice.options) { index, option ->
            OptionCard(option, selected = picked == index, onSelect = { picked = index })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppAction(
                    picked?.let { stringResource(R.string.choices_thats, name) } ?: stringResource(R.string.choices_pick_one),
                    { picked?.let { viewModel.chooseLookup(choice.targetId, choice.options[it]) } },
                    Modifier.fillMaxWidth(),
                    enabled = picked != null
                )
                if (choice.hasMore) {
                    // None of these: the next run goes on to the next ones, from what the searches
                    // found, without searching again and without showing anyone twice. Nothing is
                    // opened now and nothing is discarded; this contact leaves the list for now.
                    AppAction(stringResource(R.string.choices_none_more), { viewModel.nextBatchOnNextRun(choice.targetId) }, Modifier.fillMaxWidth())
                    AppCaption(stringResource(R.string.choices_none_more_caption, name))
                    // Ending the search for this contact discards everyone found, so it asks first.
                    AppTextAction(stringResource(R.string.choices_stop_looking, name), { confirmNone = true })
                } else {
                    // Discards everyone found for this contact, so it asks first.
                    AppTextAction(stringResource(R.string.choices_none, name), { confirmNone = true })
                }
                if (remaining > 1) AppTextAction(stringResource(R.string.choices_decide_later), onLater)
            }
        }
    }

    if (confirmNone) {
        val found = choice.foundSoFar
        AppDialog(
            title = stringResource(R.string.choices_none_title, name),
            confirm = stringResource(R.string.choices_none_confirm),
            onConfirm = { confirmNone = false; viewModel.chooseLookup(choice.targetId, null) },
            onDismiss = { confirmNone = false }
        ) {
            Text(pluralStringResource(R.plurals.choices_none_body, found, found, name))
        }
    }
}

@Composable
private fun OptionCard(option: LookupOption, selected: Boolean, onSelect: () -> Unit) {
    AppCard(
        // selectable, not clickable: TalkBack reads the card as a radio button and whether it is chosen.
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        emphasis = selected
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(option.name ?: stringResource(R.string.choices_no_name), style = MaterialTheme.typography.titleMedium)
                option.dob?.let {
                    Text(
                        if (it.startsWith("~")) stringResource(R.string.choices_born_about, it.removePrefix("~"))
                        else stringResource(R.string.choices_born, it),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (option.deceased) {
                    Text(
                        stringResource(R.string.choices_listed_deceased),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                option.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                option.phone?.let { Text(formatPhone(it), style = MaterialTheme.typography.bodyMedium) }
                // What the person's own page added, when it was read: more to tell people apart by.
                val shownPhone = option.phone?.filter { it.isDigit() }
                option.phones.orEmpty().filter { it != shownPhone }.take(4).takeIf { it.isNotEmpty() }?.let { more ->
                    AppCaption(stringResource(R.string.choices_more_phones, more.joinToString(", ") { formatPhone(it) }))
                }
                option.emails.orEmpty().take(3).takeIf { it.isNotEmpty() }?.let {
                    AppCaption(stringResource(R.string.choices_emails, it.joinToString(", ")))
                }
                option.otherAddresses.orEmpty().take(3).takeIf { it.isNotEmpty() }?.let {
                    AppCaption(stringResource(R.string.choices_past_addresses, it.joinToString("; ")))
                }
                option.relatives.orEmpty().take(5).takeIf { it.isNotEmpty() }?.let {
                    AppCaption(stringResource(R.string.choices_relatives, it.joinToString(", ")))
                }
                if (option.fits.isNotEmpty()) {
                    val fits = option.fits.map { lookupReason(it) }
                    AppCaption(stringResource(R.string.choices_fits, fits.joinToString(", ") { it.replaceFirstChar { c -> c.lowercase() } }))
                }
                if (option.differs.isNotEmpty()) {
                    val differs = option.differs.map { lookupReason(it) }
                    AppCaption(stringResource(R.string.choices_differs, differs.joinToString(", ") { it.replaceFirstChar { c -> c.lowercase() } }))
                }
                AppCaption(stringResource(R.string.choices_found_by, lookupModes(option.foundBy)))
            }
        }
    }
}

// What a lookup saves for a found person: plain-English keys for the reasons it fits or differs and
// for the searches that turned them up. The choices outlive an app update, so what is saved is
// never a resource id; the words the user reads come from the resources, below.
private const val CAME_UP_FOR = "Came up for their "

/** The words for one saved reason; a key this version doesn't know is shown as saved. */
@Composable
private fun lookupReason(key: String): String = when (key) {
    "Same name" -> stringResource(R.string.choices_fit_same_name)
    "Same phone number" -> stringResource(R.string.choices_fit_same_phone)
    "Same area code" -> stringResource(R.string.choices_fit_same_area_code)
    "Same city or ZIP" -> stringResource(R.string.choices_fit_same_place)
    "Same email address" -> stringResource(R.string.choices_fit_same_email)
    "Same age" -> stringResource(R.string.choices_fit_same_age)
    "A different name" -> stringResource(R.string.choices_differs_name)
    "A different age" -> stringResource(R.string.choices_differs_age)
    else -> if (key.startsWith(CAME_UP_FOR)) {
        stringResource(R.string.choices_fit_came_up, lookupModes(key.removePrefix(CAME_UP_FOR).split(" and ")))
    } else {
        key
    }
}

/** The searches that turned a person up ("phone", "name"), in words, joined with "and". */
@Composable
private fun lookupModes(keys: List<String>): String {
    val words = keys.map { key ->
        when (key) {
            "phone" -> stringResource(R.string.choices_mode_phone)
            "email" -> stringResource(R.string.choices_mode_email)
            "address" -> stringResource(R.string.choices_mode_address)
            "name" -> stringResource(R.string.choices_mode_name)
            else -> key
        }
    }
    return words.joinToString(stringResource(R.string.list_and))
}

/** Ten digits as "(555) 123-4567"; anything else as it came. */
private fun formatPhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    val ten = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    return if (ten.length == 10) "(${ten.take(3)}) ${ten.substring(3, 6)}-${ten.substring(6)}" else raw
}

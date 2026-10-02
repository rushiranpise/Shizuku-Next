package moe.shizuku.manager.ui.component

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.Activities

/**
 * An intent, described by hand.
 *
 * The list beside this answers "what does this app have"; this answers "make this happen", which is
 * the other half of the same question and the half somebody needs when the thing they want is not
 * on any launcher icon. All of an intent is written out because none of it can be inferred: an
 * action, some data, a type, a component, categories, and whatever extras and flags the receiver
 * expects.
 *
 * It also takes an intent as a URI, in the format the platform defines for exactly this
 * (`Intent.URI_INTENT_SCHEME`), which is what the Shizuku plugin for Activity Launcher passes
 * between apps and what most automation tools emit:
 *
 *     intent:#Intent;action=android.intent.action.VIEW;data=https://example.com;end
 *
 * Loading one fills the fields rather than sending it blind, so a URI copied out of somewhere can
 * be read and corrected first - which most of them need, since they rarely arrive with a component
 * on them.
 *
 * The state lives here rather than in the composable that draws it because two things need it: the
 * form, and the send action in the app bar above it.
 */
internal class IntentDraft {

    var uri by mutableStateOf("")
    var action by mutableStateOf("")
    var data by mutableStateOf("")
    var type by mutableStateOf("")
    var component by mutableStateOf("")
    var packageName by mutableStateOf("")
    var categories by mutableStateOf("")
    var extras by mutableStateOf<List<Extra>>(emptyList())
    var flags by mutableStateOf<Set<Int>>(emptySet())

    /** Whether this is an activity to start or a broadcast to send. */
    var operation by mutableStateOf(IntentOperation.ACTIVITY)

    /** What the intent reaches, as last looked up. Empty is the answer worth seeing. */
    var targets by mutableStateOf<List<Activities.Target>>(emptyList())

    /** What the last send had to say, or null when it had nothing to complain about. */
    var message by mutableStateOf<Int?>(null)

    private var nextId = 1

    /**
     * Fills the component from an activity the catalogue knows about.
     *
     * The component is the one field that cannot be guessed and the one the app has just listed
     * a screen away, so this is the whole of the bridge between the two tabs: everything else in
     * an intent is something a person means, and the component is something a device has.
     */
    fun prefill(packageName: String, name: String) {
        this.packageName = packageName
        component = name
        operation = IntentOperation.ACTIVITY
        message = null
    }

    fun addExtra() {
        extras = extras + Extra("", ExtraType.STRING, "", nextId++)
    }

    fun removeExtra(id: Int) {
        extras = extras.filterNot { it.id == id }
    }

    /** Edits one extra in place, by id, so a rename cannot land on the wrong row. */
    fun editExtra(id: Int, edit: (Extra) -> Extra) {
        extras = extras.map { if (it.id == id) edit(it) else it }
    }

    /** Whether there is enough of an intent here to be worth sending. */
    fun sendable(): Boolean =
        action.isNotBlank() || data.isNotBlank() || component.isNotBlank() || packageName.isNotBlank()

    fun build(): Intent {
        val intent = Intent()
        if (action.isNotBlank()) intent.action = action.trim()
        if (data.isNotBlank()) intent.data = Uri.parse(data.trim())
        if (type.isNotBlank()) intent.type = type.trim()

        val namedPackage = packageName.trim()
        if (namedPackage.isNotEmpty()) intent.setPackage(namedPackage)

        val namedComponent = component.trim()
        if (namedComponent.isNotEmpty()) {
            // "package/class" is the platform's flattened form, but both halves have a field here,
            // and the class on its own is what this app's own Copy hands over. So a bare class is
            // paired with the package above rather than refused - and a leading dot is expanded,
            // which is how a manifest writes a class in the app's own package.
            val name = ComponentName.unflattenFromString(namedComponent)
                ?: namedPackage.takeIf { it.isNotEmpty() }?.let { pkg ->
                    val cls = if (namedComponent.startsWith(".")) pkg + namedComponent else namedComponent
                    ComponentName(pkg, cls)
                }
            name?.let { intent.component = it }
        }

        categories.split(',', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { intent.addCategory(it) }

        extras.forEach { extra ->
            if (extra.key.isBlank()) return@forEach
            // An extra that will not parse is left out rather than sent as the string that would
            // not parse: most of these keys are read by an app that will not tell anybody it
            // received the wrong type.
            when (extra.type) {
                ExtraType.STRING -> intent.putExtra(extra.key, extra.value)
                ExtraType.INT -> extra.value.trim().toIntOrNull()
                    ?.let { intent.putExtra(extra.key, it) }

                ExtraType.LONG -> extra.value.trim().toLongOrNull()
                    ?.let { intent.putExtra(extra.key, it) }

                ExtraType.FLOAT -> extra.value.trim().toFloatOrNull()
                    ?.let { intent.putExtra(extra.key, it) }

                ExtraType.DOUBLE -> extra.value.trim().toDoubleOrNull()
                    ?.let { intent.putExtra(extra.key, it) }

                ExtraType.BOOLEAN -> extra.value.trim().toBooleanStrictOrNull()
                    ?.let { intent.putExtra(extra.key, it) }
            }
        }

        flags.forEach { intent.addFlags(it) }
        return intent
    }

    /** Fills the fields from an intent URI, or says why it could not be read. */
    fun load() {
        val parsed = runCatching {
            Intent.parseUri(uri.trim(), Intent.URI_INTENT_SCHEME)
        }.getOrNull()
        if (parsed == null) {
            message = R.string.intent_uri_invalid
            return
        }

        action = parsed.action.orEmpty()
        data = parsed.dataString.orEmpty()
        type = parsed.type.orEmpty()
        packageName = parsed.`package`.orEmpty()
        component = parsed.component?.flattenToString().orEmpty()
        categories = parsed.categories?.joinToString(", ").orEmpty()
        // The flags that are offered below, and only those: an intent carries them as one number,
        // and a bit this form does not name is a bit it cannot show.
        flags = Flags.filter { parsed.flags and it.value != 0 }.map { it.value }.toSet()

        val loaded = mutableListOf<Extra>()
        parsed.extras?.keySet()?.forEach { key ->
            when (val value = parsed.extras?.get(key)) {
                is String -> loaded += Extra(key, ExtraType.STRING, value, nextId++)
                is Int -> loaded += Extra(key, ExtraType.INT, value.toString(), nextId++)
                is Long -> loaded += Extra(key, ExtraType.LONG, value.toString(), nextId++)
                is Float -> loaded += Extra(key, ExtraType.FLOAT, value.toString(), nextId++)
                is Double -> loaded += Extra(key, ExtraType.DOUBLE, value.toString(), nextId++)
                is Boolean -> loaded += Extra(key, ExtraType.BOOLEAN, value.toString(), nextId++)
            }
        }
        extras = loaded
        message = null
    }
}

/**
 * The form itself: everything [IntentDraft] holds, one field per thing an intent can be.
 *
 * [onPick] leaves the form for the activity list, which is the only way to fill the component
 * without knowing a class name by heart.
 */
@Composable
internal fun IntentForm(draft: IntentDraft, bottomPadding: Dp, onPick: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 4.dp,
            end = 16.dp,
            bottom = bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FormNote(stringResource(R.string.intent_builder_note), error = false)
        }

        draft.message?.let { text ->
            item { FormNote(stringResource(text), error = true) }
        }

        // What is being sent, before what it is made of: the same fields describe both, so which
        // of the two they describe is the first thing to settle.
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntentOperation.entries.forEach { operation ->
                    FilterChip(
                        selected = draft.operation == operation,
                        onClick = { draft.operation = operation },
                        label = {
                            Text(
                                stringResource(
                                    when (operation) {
                                        IntentOperation.ACTIVITY -> R.string.intent_operation_activity
                                        IntentOperation.BROADCAST -> R.string.intent_operation_broadcast
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        // Said before sending rather than after: an app is never told that a broadcast reached
        // nobody, and is only told an activity was refused once it has been. Both are questions
        // the package manager answers in advance.
        if (draft.sendable()) {
            item {
                FormNote(
                    text = if (draft.targets.isEmpty()) {
                        stringResource(R.string.intent_targets_none)
                    } else {
                        stringResource(
                            R.string.intent_targets,
                            draft.targets.size,
                            draft.targets.take(3).joinToString(", ") { it.label }
                        )
                    },
                    error = draft.targets.isEmpty()
                )
            }
        }

        item {
            SegmentedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Field(
                        value = draft.uri,
                        onValueChange = { draft.uri = it },
                        label = stringResource(R.string.intent_uri)
                    )
                    PillButtonQuiet(onClick = { draft.load() }) {
                        Text(stringResource(R.string.intent_uri_load))
                    }
                }
            }
        }

        item {
            SegmentedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Field(draft.action, { draft.action = it }, stringResource(R.string.intent_action))
                    Field(draft.data, { draft.data = it }, stringResource(R.string.intent_data))
                    Field(draft.type, { draft.type = it }, stringResource(R.string.intent_type))
                    Field(
                        draft.component,
                        { draft.component = it },
                        stringResource(R.string.intent_component)
                    )
                    // Outlined, because it opens a picker rather than doing something on the spot:
                    // a text button has no border and no container, so this sat under the fields
                    // as bare words and read as a label rather than as the way to fill them.
                    OutlinedButton(onClick = onPick) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(
                            text = stringResource(R.string.intent_choose_activity),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Field(
                        draft.packageName,
                        { draft.packageName = it },
                        stringResource(R.string.intent_package)
                    )
                    Field(
                        draft.categories,
                        { draft.categories = it },
                        stringResource(R.string.intent_categories)
                    )
                }
            }
        }

        item { FormTitle(stringResource(R.string.intent_extras)) }

        items(draft.extras, key = { it.id }) { extra ->
            SegmentedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            Field(
                                value = extra.key,
                                onValueChange = { value -> draft.editExtra(extra.id) { it.copy(key = value) } },
                                label = stringResource(R.string.intent_key)
                            )
                        }
                        ExtraTypeMenu(
                            selected = extra.type,
                            onSelect = { chosen -> draft.editExtra(extra.id) { it.copy(type = chosen) } }
                        )
                        IconButton(onClick = { draft.removeExtra(extra.id) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.intent_extra_remove)
                            )
                        }
                    }
                    Field(
                        value = extra.value,
                        onValueChange = { value -> draft.editExtra(extra.id) { it.copy(value = value) } },
                        label = stringResource(R.string.intent_value)
                    )
                }
            }
        }

        item {
            PillButtonQuiet(onClick = { draft.addExtra() }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.intent_extra_add),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        item { FormTitle(stringResource(R.string.intent_flags)) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Flags.forEach { flag ->
                    FilterChip(
                        selected = flag.value in draft.flags,
                        onClick = {
                            draft.flags = if (flag.value in draft.flags) {
                                draft.flags - flag.value
                            } else {
                                draft.flags + flag.value
                            }
                        },
                        label = { Text(stringResource(flag.labelRes)) }
                    )
                }
            }
        }
    }
}

/**
 * Whether the form starts an activity or sends a broadcast.
 *
 * One form for both, because an intent is an intent: the action, the data, the categories, the
 * extras and the flags are the same list whichever of the two carries them, and a second form
 * would be a second copy of every field with one word changed.
 */
internal enum class IntentOperation { ACTIVITY, BROADCAST }

/** One extra: what it is called, what type it is, and what it holds. */
internal data class Extra(val key: String, val type: ExtraType, val value: String, val id: Int)

/**
 * The types an extra can be, which are the six an `Intent` can carry without a Parcelable - and
 * the six `am` can express on a command line, so anything built here can also be read back as one.
 */
internal enum class ExtraType { STRING, INT, LONG, FLOAT, DOUBLE, BOOLEAN }

/** A flag and the name for it. Only the ones worth reaching for. */
internal data class Flag(val labelRes: Int, val value: Int)

internal val Flags = listOf(
    Flag(R.string.intent_flag_clear_top, Intent.FLAG_ACTIVITY_CLEAR_TOP),
    Flag(R.string.intent_flag_single_top, Intent.FLAG_ACTIVITY_SINGLE_TOP),
    Flag(R.string.intent_flag_clear_task, Intent.FLAG_ACTIVITY_CLEAR_TASK),
    Flag(R.string.intent_flag_new_document, Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
    Flag(R.string.intent_flag_no_animation, Intent.FLAG_ACTIVITY_NO_ANIMATION),
    Flag(R.string.intent_flag_exclude_recents, Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
    Flag(R.string.intent_flag_grant_read, Intent.FLAG_GRANT_READ_URI_PERMISSION),
    Flag(R.string.intent_flag_grant_write, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
)

/** The type of an extra, as a menu rather than a field: there are six and they are not guessable. */
@Composable
private fun ExtraTypeMenu(selected: ExtraType, onSelect: (ExtraType) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        // The six types are a menu, so this is drawn as something that opens one: the borders and
        // the arrow are what say it can be changed, and neither was there before.
        OutlinedButton(onClick = { open = true }) {
            Text(selected.name.lowercase().replaceFirstChar { it.uppercase() })
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExtraType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(candidate)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun FormTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun FormNote(text: String, error: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .padding(bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

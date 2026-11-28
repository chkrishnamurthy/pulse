package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.training.Equipment
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.ImportNames
import com.fitbittrack.pulse.training.Muscle
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.screens.NumberStepper
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Which exercises a search shows: every word must be in the name, the muscle or the equipment. Favourites and used ones first. */
internal fun findExercises(
    all: List<ExerciseEntity>,
    text: String,
    muscle: Muscle?,
    equipment: Equipment?,
    mine: Boolean,
    used: Map<String, Int>,
): List<ExerciseEntity> {
    val wanted = text.lowercase().split(" ").filter { it.isNotBlank() }
    return all.filter { e ->
        (muscle == null || e.muscle == muscle) && (equipment == null || e.equipment == equipment) && (!mine || e.custom || e.favourite) &&
            wanted.all { w -> w in e.name.lowercase() || ImportNames.plain(w).let { p -> p.isNotEmpty() && p in ImportNames.plain(e.name) } || w in e.muscle.label.lowercase() || w in e.equipment.label.lowercase() }
    }.sortedWith(compareByDescending<ExerciseEntity> { it.favourite }.thenByDescending { used[it.id] ?: 0 }.thenByDescending { it.custom }.thenBy { it.name.lowercase() })
}

private enum class Filter { None, Muscle, Equipment }

@Composable
fun ExercisesScreen(
    all: List<ExerciseEntity>, used: Map<String, Int>, ties: Map<String, String>, title: String, contentPadding: PaddingValues,
    onBack: () -> Unit, onNew: () -> Unit, onPick: (ExerciseEntity) -> Unit, onFavourite: (String) -> Unit,
) {
    val c = PulseTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var equipment by rememberSaveable { mutableStateOf<Equipment?>(null) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(Filter.None) }
    val found = remember(all, text, muscle, equipment, mine, used) { findExercises(all, text, muscle, equipment, mine, used) }

    ListPage(title, contentPadding, onBack, actions = { IconAct(PulseIcons.Plus, "New exercise", onClick = onNew) }) {
        item { TextBox(text, "Search", { text = it }, icon = PulseIcons.Search, label = "Search exercises") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", muscle == null && equipment == null && !mine) { muscle = null; equipment = null; mine = false }
                FilterChip("Mine", mine, c.sleep) { mine = !mine }
                FilterChip(muscle?.label ?: "Muscle", muscle != null, c.active) { open = Filter.Muscle }
                FilterChip(equipment?.label ?: "Equipment", equipment != null, c.move) { open = Filter.Equipment }
            }
        }
        item { SectionTitle(if (found.isEmpty()) "Nothing found" else words(found.size, "exercise")) }
        itemsIndexed(found, key = { _, e -> e.id }) { i, e ->
            CardSlice(i == 0, i == found.lastIndex) {
                ExerciseRow(e, edbIdOf(e.id, ties), onClick = { onPick(e) }) {
                    IconButton(onClick = { onFavourite(e.id) }) {
                        Icon(
                            PulseIcons.Star,
                            contentDescription = if (e.favourite) "Remove from favourites: ${e.name}" else "Favourite: ${e.name}",
                            tint = if (e.favourite) c.move else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        item { Box(Modifier.padding(top = 12.dp)) { Hint("Nothing fits? Tap + to make your own.") } }
    }

    when (open) {
        Filter.None -> Unit
        Filter.Muscle -> PulseSheet({ open = Filter.None }) {
            Text("Muscle", style = MaterialTheme.typography.titleLarge)
            Choices(listOf<Muscle?>(null) + Muscle.entries, { it == muscle }, { it?.label ?: "All" }) { muscle = it; open = Filter.None }
        }
        Filter.Equipment -> PulseSheet({ open = Filter.None }) {
            Text("Equipment", style = MaterialTheme.typography.titleLarge)
            Choices(listOf<Equipment?>(null) + Equipment.entries, { it == equipment }, { it?.label ?: "All" }) { equipment = it; open = Filter.None }
        }
    }
}

/** One exercise in a list: its picture (still, so scrolling stays smooth), name and what it trains. */
@Composable
private fun ExerciseRow(e: ExerciseEntity, edbId: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExerciseThumb(edbId, e.name, 52)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall)
            Text(e.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Makes an own exercise, or changes one. For a catalogue exercise only rest and step can be changed. */
@Composable
fun ExerciseEditScreen(
    old: ExerciseEntity?,
    unit: WeightUnit,
    taken: Set<String>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: (ExerciseEntity) -> Unit,
    onRemove: () -> Unit,
) {
    val own = old == null || old.custom
    var name by rememberSaveable { mutableStateOf(old?.name.orEmpty()) }
    var track by rememberSaveable { mutableStateOf(old?.track ?: Track.WeightReps) }
    var equipment by rememberSaveable { mutableStateOf(old?.equipment ?: Equipment.Barbell) }
    var muscle by rememberSaveable { mutableStateOf(old?.muscle ?: Muscle.Chest) }
    var also by rememberSaveable { mutableStateOf(old?.secondary.orEmpty()) }
    var rest by rememberSaveable { mutableIntStateOf(old?.restSec ?: 120) }
    var step by rememberSaveable { mutableIntStateOf(old?.stepGrams ?: if (unit == WeightUnit.Kg) 2500 else Weights.grams(5.0, WeightUnit.Lb)) }
    var ask by remember { mutableStateOf(false) }
    val second = also.split(",").mapNotNull { n -> Muscle.entries.firstOrNull { it.name == n } }
    val clash = own && name.trim().lowercase() in taken && name.trim().lowercase() != old?.name?.lowercase()

    DetailPage(if (old == null) "New exercise" else old.name, contentPadding, onBack, actions = {
        if (old?.custom == true) MoreMenu("More", listOf(Act("Delete exercise", danger = true) { ask = true }))
    }) {
        if (own) {
            PulseCard {
                TextBox(name, "Name", { name = it })
                if (clash) Hint("You already have an exercise with this name.", MaterialTheme.colorScheme.error)
            }
            SectionTitle("How to count it")
            Segmented(Track.entries, track, { it.label }) { track = it }
            SectionTitle("Equipment")
            Choices(Equipment.entries, { it == equipment }, { it.label }) { equipment = it }
            SectionTitle("Main muscle")
            Choices(Muscle.entries, { it == muscle }, { it.label }) { m -> muscle = m; also = second.filter { it != m }.joinToString(",") { it.name } }
            SectionTitle("Also trains")
            Choices(
                Muscle.entries.filter { it != muscle },
                { it in second },
                { it.label },
            ) { m -> also = (if (m in second) second - m else second + m).joinToString(",") { it.name } }
        } else Hint("This exercise comes with Pulse. You can choose its rest and weight step.")
        val one = if (unit == WeightUnit.Kg) 500 else Weights.grams(1.0, WeightUnit.Lb)
        RowGroup(buildList {
            add { NumberStepper("Rest after a set", clock(rest) + " min", rest, 15, 0..600) { rest = it } }
            if (track == Track.WeightReps) add { NumberStepper(
                "Weight step",
                Weights.text(step, unit),
                step,
                one,
                one..Weights.grams(25.0, WeightUnit.Kg),
            ) { step = it } }
        }, dividerInset = 16)
        MainButton("Save exercise", enabled = name.isNotBlank() && !clash) {
            onSave((old ?: ExerciseEntity("", "", equipment, track, muscle)).copy(
                name = name.trim(),
                equipment = equipment,
                track = track,
                muscle = muscle,
                secondary = also,
                restSec = rest,
                stepGrams = step,
            ))
        }
    }
    if (ask) Ask("Delete this exercise?", "Workouts you did with it stay as they are.", "Delete", { ask = false }) { ask = false; onRemove() }
}

package com.kinetix.coach.ui.encyclopedia

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinetix.coach.data.KnowledgeCategory
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.ui.components.FilterChipPill
import com.kinetix.coach.ui.components.HairlineDivider
import com.kinetix.coach.ui.components.IndustrialCard
import com.kinetix.coach.ui.components.MetricCell
import com.kinetix.coach.ui.components.SectionLabel
import com.kinetix.coach.ui.components.categoryColor
import com.kinetix.coach.ui.components.categoryLabelFr
import com.kinetix.coach.ui.theme.BorderHairline
import com.kinetix.coach.ui.theme.CharcoalSurface
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.ElevatedSurface
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormFaultDim
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SteelGray
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncyclopediaScreen(
    viewModel: EncyclopediaViewModel,
    onAskCoach: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val totalCount = uiState.categoryCounts["all"] ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Header + Search Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CharcoalSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ENCYCLOPÉDIE KINETIX",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (totalCount > 0) {
                            "$totalCount fiches hors-ligne · FTS5 instantané"
                        } else {
                            "Chargement de la base locale…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = SteelGray
                    )
                }
                if (uiState.lastSearchMs > 0L) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text(
                            text = "${uiState.entries.size} rés. · ${uiState.lastSearchMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = FormGood,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Rechercher exercice, aliment, programme, blessure…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelGray
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = SignalOrange
                    )
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQuery() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Effacer",
                                tint = SteelGray
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg,
                    focusedBorderColor = SignalOrange,
                    unfocusedBorderColor = BorderHairline,
                    focusedTextColor = CrispWhite,
                    unfocusedTextColor = CrispWhite,
                    cursorColor = SignalOrange
                )
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(KnowledgeCategory.entries) { cat ->
                    val count = uiState.categoryCounts[cat.dbKey]
                    val label = if (count != null) "${cat.labelFr} ($count)" else cat.labelFr
                    FilterChipPill(
                        label = label,
                        selected = uiState.selectedCategory == cat,
                        onClick = { viewModel.onCategorySelected(cat) }
                    )
                }
            }
        }

        HairlineDivider()

        // Results List
        if (uiState.entries.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                IndustrialCard {
                    Text(
                        text = "Aucun résultat pour « ${uiState.query} »",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Essayez un terme plus court (ex: squat, poulet, créatine, épaule, hypertrophie) ou basculez le filtre sur « Tout ».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelGray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.entries, key = { it.id }) { entry ->
                    EncyclopediaEntryCard(
                        entry = entry,
                        onClick = { viewModel.selectEntry(entry) }
                    )
                }
            }
        }
    }

    // Detail Bottom Sheet
    uiState.selectedEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissEntry() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CharcoalSurface,
            contentColor = CrispWhite
        ) {
            EncyclopediaDetailSheet(
                entry = entry,
                onAskCoach = {
                    val prompt = buildCoachPromptForEntry(entry)
                    viewModel.dismissEntry()
                    onAskCoach(prompt)
                },
                onClose = { viewModel.dismissEntry() }
            )
        }
    }
}

@Composable
private fun EncyclopediaEntryCard(
    entry: KnowledgeEntry,
    onClick: () -> Unit
) {
    val accent = categoryColor(entry.category)
    IndustrialCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = categoryLabelFr(entry.category),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            if (entry.subcategory.isNotBlank()) {
                Text(
                    text = " · ${entry.subcategory}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            color = CrispWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = SteelGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncyclopediaDetailSheet(
    entry: KnowledgeEntry,
    onAskCoach: () -> Unit,
    onClose: () -> Unit
) {
    val accent = categoryColor(entry.category)
    val meta = remember(entry.metadataJson) {
        runCatching { JSONObject(entry.metadataJson) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${categoryLabelFr(entry.category)} · ${entry.subcategory.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = SteelGray)
            }
        }

        Text(
            text = entry.title,
            style = MaterialTheme.typography.headlineMedium,
            color = CrispWhite
        )

        // Structured Metadata Panel by Category
        if (meta != null) {
            when (entry.category.lowercase()) {
                "nutrition" -> NutritionMetaGrid(meta)
                "exercice" -> ExerciseMetaPanel(meta)
                "programme" -> ProgramMetaPanel(meta)
                "blessures" -> InjuryRedFlagsPanel(meta)
            }
        }

        if (entry.summary.isNotBlank()) {
            IndustrialCard(containerColor = ObsidianBg, borderColor = accent.copy(alpha = 0.4f)) {
                SectionLabel(text = "Synthèse")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrispWhite
                )
            }
        }

        IndustrialCard(containerColor = ObsidianBg) {
            SectionLabel(text = "Protocole & Détails complets")
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.body,
                style = MaterialTheme.typography.bodyMedium,
                color = CrispWhite
            )
        }

        // Tags strip if present
        val tagsList = remember(entry.tags) {
            entry.tags.split(",", " ").map { it.trim() }.filter { it.length >= 3 }.distinct().take(8)
        }
        if (tagsList.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tagsList.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 1-tap Coach escalation button
        Button(
            onClick = onAskCoach,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SignalOrange,
                contentColor = CrispWhite
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Demander au Coach IA de personnaliser",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun NutritionMetaGrid(meta: JSONObject) {
    if (!meta.has("kcal_100g")) return
    val kcal = meta.optDouble("kcal_100g", 0.0).toInt()
    val prot = meta.optDouble("proteines_g", 0.0)
    val carb = meta.optDouble("glucides_g", 0.0)
    val fat = meta.optDouble("lipides_g", 0.0)
    val fiber = meta.optDouble("fibres_g", 0.0)
    val gi = meta.optInt("index_glycemique", -1)
    val micro = meta.optString("micronutriments", "")
    val timing = meta.optString("moment_ideal", "")

    IndustrialCard(containerColor = ObsidianBg, borderColor = FormGood.copy(alpha = 0.45f)) {
        SectionLabel(text = "Valeurs nutritionnelles", trailing = "POUR 100 G")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCell("Énergie", "$kcal", "kcal", valueColor = SignalOrange)
            MetricCell("Protéines", String.format("%.1f", prot), "g", valueColor = FormGood)
            MetricCell("Glucides", String.format("%.1f", carb), "g")
            MetricCell("Lipides", String.format("%.1f", fat), "g")
        }
        Spacer(Modifier.height(10.dp))
        HairlineDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Fibres : ${String.format("%.1f", fiber)} g",
                style = MaterialTheme.typography.labelMedium,
                color = SteelGray
            )
            if (gi >= 0) {
                Text(
                    text = "Index glycémique : $gi",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelGray
                )
            }
        }
        if (micro.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Micronutriments : $micro",
                style = MaterialTheme.typography.labelMedium,
                color = CrispWhite
            )
        }
        if (timing.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Moment idéal : $timing",
                style = MaterialTheme.typography.labelMedium,
                color = FormGood
            )
        }
    }
}

@Composable
private fun ExerciseMetaPanel(meta: JSONObject) {
    val primaries = jsonArrayToList(meta.optJSONArray("muscles_primaires"))
    val synergists = jsonArrayToList(meta.optJSONArray("muscles_synergistes"))
    val equipment = meta.optString("materiel", "")
    val difficulty = meta.optString("difficulte", "")
    val reps = meta.optString("reps_optimales", "")

    if (primaries.isEmpty() && equipment.isBlank() && reps.isBlank()) return

    IndustrialCard(containerColor = ObsidianBg, borderColor = SignalOrange.copy(alpha = 0.45f)) {
        SectionLabel(text = "Paramètres biomécaniques")
        Spacer(Modifier.height(8.dp))
        if (primaries.isNotEmpty()) {
            MetaRow("Muscles cibles", primaries.joinToString(" · "))
        }
        if (synergists.isNotEmpty()) {
            MetaRow("Synergistes", synergists.joinToString(" · "))
        }
        if (equipment.isNotBlank()) {
            MetaRow("Matériel", equipment)
        }
        if (difficulty.isNotBlank()) {
            MetaRow("Difficulté", difficulty)
        }
        if (reps.isNotBlank()) {
            MetaRow("Format optimal", reps)
        }
    }
}

@Composable
private fun ProgramMetaPanel(meta: JSONObject) {
    val level = meta.optString("niveau", "")
    val freq = meta.optString("frequence", "")
    val duration = meta.optString("duree", "")
    val progression = meta.optString("progression", "")
    if (level.isBlank() && freq.isBlank() && duration.isBlank()) return

    IndustrialCard(containerColor = ObsidianBg) {
        SectionLabel(text = "Architecture du cycle")
        Spacer(Modifier.height(8.dp))
        if (level.isNotBlank()) MetaRow("Niveau", level)
        if (freq.isNotBlank()) MetaRow("Fréquence", freq)
        if (duration.isNotBlank()) MetaRow("Durée", duration)
        if (progression.isNotBlank()) MetaRow("Progression", progression)
    }
}

@Composable
private fun InjuryRedFlagsPanel(meta: JSONObject) {
    val redFlags = meta.optString("drapeaux_rouges", "")
    if (redFlags.isBlank()) return
    IndustrialCard(containerColor = FormFaultDim, borderColor = FormFault) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = FormFault,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "DRAPEAUX ROUGES — AVIS MÉDICAL",
                style = MaterialTheme.typography.labelMedium,
                color = FormFault,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = redFlags,
            style = MaterialTheme.typography.bodyMedium,
            color = CrispWhite
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SteelGray,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = CrispWhite,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun jsonArrayToList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.optString(i)
        if (v.isNotBlank()) out.add(v)
    }
    return out
}

private fun buildCoachPromptForEntry(entry: KnowledgeEntry): String = when (entry.category.lowercase()) {
    "exercice" -> "Explique-moi comment intégrer « ${entry.title} » dans mon entraînement selon mon profil, avec mes charges cibles et les 3 erreurs clés à éviter."
    "nutrition" -> "Comment intégrer « ${entry.title} » dans mon plan alimentaire selon mon profil et mon objectif ?"
    "programme" -> "Adapte le programme « ${entry.title} » à mon profil athlète (niveau, matériel, objectifs et antécédents)."
    "blessures" -> "Donne-moi un plan concret de prévention et d'adaptation d'entraînement basé sur « ${entry.title} »."
    else -> "Applique concrètement « ${entry.title} » à mon entraînement hebdomadaire selon mon profil."
}

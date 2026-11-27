package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.VoteStatus
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.tools.includeFontPadding

@Composable
context(_: Context)
fun GalleryTags(
    tagGroups: List<GalleryTagGroup>,
    onTagClick: (String) -> Unit,
    onTagLongClick: (String, String, VoteStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showTagTranslations by Settings.showTagTranslations.collectAsState()
    val canTranslate = showTagTranslations && EhTagDatabase.translatable && EhTagDatabase.initialized
    if (showTagTranslations && EhTagDatabase.translatable && !EhTagDatabase.initialized) {
        LaunchedEffect(showTagTranslations, EhTagDatabase.initialized) {
            EhTagDatabase.ensureInitialized()
        }
    }
    val ehTags = EhTagDatabase.takeIf { canTranslate }
    fun TagNamespace.translate() = ehTags?.getTranslation(tag = value) ?: value
    fun String.translate(ns: TagNamespace) = ehTags?.getTranslation(prefix = ns.prefix, tag = this) ?: this
    val showVote by Settings.showVoteStatus.collectAsState()
    val experimentalNewUi by Settings.experimentalNewUi.collectAsState()
    if (experimentalNewUi) {
        val defaultExpanded = remember {
            setOf(
                TagNamespace.Language.value,
                TagNamespace.Parody.value,
                TagNamespace.Character.value,
                TagNamespace.Artist.value,
                TagNamespace.Group.value,
                TagNamespace.Female.value,
            )
        }
        val expandedMap = remember(tagGroups) {
            mutableStateMapOf<String, Boolean>().apply {
                tagGroups.forEach { (ns, _) ->
                    put(ns.value, ns.value in defaultExpanded)
                }
            }
        }
        val allExpanded = tagGroups.all { (ns, _) -> expandedMap[ns.value] == true }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.filter_tag),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val target = !allExpanded
                        tagGroups.forEach { (ns, _) ->
                            expandedMap[ns.value] = if (target) true else (ns.value in defaultExpanded)
                        }
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (allExpanded) R.string.tags_collapse_all else R.string.tags_expand_all,
                        ),
                    )
                }
            }
            tagGroups.forEach { (ns, tags) ->
                val isOpen = expandedMap[ns.value] == true
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { expandedMap[ns.value] = !isOpen }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ns.translate(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = " (${tags.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        AnimatedVisibility(visible = isOpen) {
                            FlowRow(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                                tags.forEach { (text, power, vote) ->
                                    val translation = text.translate(ns)
                                    val tag = ns.value + ":" + text
                                    val hapticFeedback = LocalHapticFeedback.current
                                    Box {
                                        BaseRoundText(
                                            text = translation,
                                            weak = power == PowerStatus.Weak,
                                            solid = power == PowerStatus.Solid && showVote,
                                            modifier = Modifier.combinedClickable(
                                                onClick = { onTagClick(tag) },
                                                onLongClick = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onTagLongClick(tag, translation, vote)
                                                },
                                            ),
                                        )
                                        if (vote != VoteStatus.None && showVote) {
                                            Text(
                                                text = vote.display,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 2.dp),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmallEmphasized.copy(fontSize = 10.sp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier) {
            tagGroups.forEach { (ns, tags) ->
                Row {
                    BaseRoundText(
                        text = ns.translate(),
                        isGroup = true,
                    )
                    FlowRow {
                        tags.forEach { (text, power, vote) ->
                            val translation = text.translate(ns)
                            val tag = ns.value + ":" + text
                            val hapticFeedback = LocalHapticFeedback.current
                            Box {
                                BaseRoundText(
                                    text = translation,
                                    weak = power == PowerStatus.Weak,
                                    solid = power == PowerStatus.Solid && showVote,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onTagClick(tag) },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTagLongClick(tag, translation, vote)
                                        },
                                    ),
                                )
                                if (vote != VoteStatus.None && showVote) {
                                    Text(
                                        text = vote.display,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 2.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmallEmphasized.copy(fontSize = 10.sp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaseRoundText(
    text: String,
    modifier: Modifier = Modifier,
    weak: Boolean = false,
    solid: Boolean = false,
    isGroup: Boolean = false,
) {
    val bgColor = if (isGroup) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    Surface(
        modifier = Modifier.padding(4.dp),
        color = bgColor,
        shape = GalleryTagCorner,
    ) {
        Text(
            text = text,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(IntrinsicSize.Max),
            color = LocalContentColor.current.let { if (weak) it.copy(0.5F) else it },
            style = MaterialTheme.typography.labelLarge.includeFontPadding,
            textDecoration = if (solid) TextDecoration.Underline else null,
        )
    }
}

private val GalleryTagCorner = RoundedCornerShape(64.dp)

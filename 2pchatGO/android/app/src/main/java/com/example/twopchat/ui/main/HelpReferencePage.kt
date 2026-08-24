package com.example.twopchat.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.data.Localizations

@Composable
fun HelpReferencePage(
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onBackClick: () -> Unit
) {
    SubPageLayout(
        title = Localizations.getString("help_reference", appLanguage),
        appLanguage = appLanguage,
        onBackClick = onBackClick,
        surfaceColor = surfaceColor,
        onSurfaceColor = onSurfaceColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    HelpAccordionItem(
                        title = Localizations.getString("help_yggdrasil_title", appLanguage),
                        description = Localizations.getString("help_yggdrasil_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_tor_title", appLanguage),
                        description = Localizations.getString("help_tor_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_socks5_title", appLanguage),
                        description = Localizations.getString("help_socks5_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_relay_title", appLanguage),
                        description = Localizations.getString("help_relay_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_e2ee_title", appLanguage),
                        description = Localizations.getString("help_e2ee_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_privacy_title", appLanguage),
                        description = Localizations.getString("help_privacy_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_circuit_rotation_title", appLanguage),
                        description = Localizations.getString("help_circuit_rotation_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_sqlcipher_title", appLanguage),
                        description = Localizations.getString("help_sqlcipher_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_duress_title", appLanguage),
                        description = Localizations.getString("help_duress_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_onion_exchange_title", appLanguage),
                        description = Localizations.getString("help_onion_exchange_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_transport_badges_title", appLanguage),
                        description = Localizations.getString("help_transport_badges_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor,
                        showDivider = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section Header: Useful Tips & Best Practices
            Text(
                text = Localizations.getString("help_sec_tips", appLanguage),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    HelpAccordionItem(
                        title = Localizations.getString("help_tip_duress_title", appLanguage),
                        description = Localizations.getString("help_tip_duress_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_tip_tor_rotation_title", appLanguage),
                        description = Localizations.getString("help_tip_tor_rotation_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_tip_bridges_title", appLanguage),
                        description = Localizations.getString("help_tip_bridges_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor
                    )

                    HelpAccordionItem(
                        title = Localizations.getString("help_tip_screen_security_title", appLanguage),
                        description = Localizations.getString("help_tip_screen_security_desc", appLanguage),
                        primaryColor = primaryColor,
                        onSurfaceColor = onSurfaceColor,
                        showDivider = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun HelpAccordionItem(
    title: String,
    description: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    showDivider: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "accordionChevronRotation"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = primaryColor,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotationAngle)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(150))
        ) {
            Text(
                text = description,
                fontSize = 13.sp,
                color = onSurfaceColor.copy(alpha = 0.85f),
                lineHeight = 20.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)
            )
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = onSurfaceColor.copy(alpha = 0.08f)
            )
        }
    }
}

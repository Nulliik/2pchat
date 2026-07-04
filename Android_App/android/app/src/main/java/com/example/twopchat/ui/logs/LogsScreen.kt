package com.example.twopchat.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.AppDiagnostics
import com.example.twopchat.data.Localizations

@Composable
fun LogsScreen(
    appLanguage: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logs by AppDiagnostics.logs.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceColor)
                .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("←", color = onSurfaceColor, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = Localizations.getString("diagnostics_logs", appLanguage),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                    )
                    Text(
                        text = Localizations.getString("diagnostics_logs_desc", appLanguage),
                        fontSize = 11.sp,
                        color = onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { AppDiagnostics.clearLogs() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor.copy(alpha = 0.14f),
                    contentColor = primaryColor,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(Localizations.getString("clear_logs", appLanguage), fontSize = 11.sp)
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Localizations.getString("logs_empty", appLanguage),
                    color = onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(logs.reversed()) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), MaterialTheme.shapes.medium),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = entry.level,
                                    color = when (entry.level) {
                                        "ERROR" -> Color(0xFFF44336)
                                        "STATUS" -> primaryColor
                                        "PY" -> Color(0xFFFFC107)
                                        else -> onSurfaceVariant
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = entry.timestamp,
                                    color = onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = entry.message,
                                color = onSurfaceColor,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

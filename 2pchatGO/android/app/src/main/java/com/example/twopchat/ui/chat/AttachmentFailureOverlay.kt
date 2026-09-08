package com.example.twopchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.data.Localizations

/** Presentation only: callers decide visibility and whether retry is allowed. */
@Composable
internal fun AttachmentFailureOverlay(
    isCancelled: Boolean,
    hasFailed: Boolean,
    canRetry: Boolean,
    appLanguage: String,
    backgroundColor: Color,
    textColor: Color,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isCancelled) {
                    Localizations.tr(
                        appLanguage,
                        ru = "Передача отменена",
                        en = "Transfer cancelled",
                        de = "Übertragung abgebrochen",
                        es = "Transferencia cancelada",
                        fr = "Transfert annulé",
                        pt = "Transferência cancelada",
                        tr = "Aktarım iptal edildi"
                    )
                } else if (hasFailed) {
                    Localizations.tr(
                        appLanguage,
                        ru = "Ошибка передачи",
                        en = "Transfer failed",
                        de = "Übertragungsfehler",
                        es = "Error de transferencia",
                        fr = "Échec du transfert",
                        pt = "Falha na transferência",
                        tr = "Aktarım başarısız oldu"
                    )
                } else {
                    Localizations.tr(
                        appLanguage,
                        ru = "Файл удалён",
                        en = "File removed",
                        de = "Datei entfernt",
                        es = "Archivo eliminado",
                        fr = "Fichier supprimé",
                        pt = "Arquivo removido",
                        tr = "Dosya kaldırıldı"
                    )
                },
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (canRetry) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                        .clickable { onRetry() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "Возобновить",
                            en = "Resume",
                            de = "Fortsetzen",
                            es = "Reanudar",
                            fr = "Reprendre",
                            pt = "Retomar",
                            tr = "Devam Ettir"
                        ),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

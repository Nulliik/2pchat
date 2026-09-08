package com.example.twopchat.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.data.Localizations

@Composable
internal fun ColumnScope.MediaPreviewActions(
    showActions: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    // Action Buttons: Close & Send (Appear after 1.5s hold or kept visible)
    AnimatedVisibility(
        visible = showActions,
        enter = fadeIn(tween(250)) + expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)),
        exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(23.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF262628).copy(alpha = 0.9f),
                    contentColor = Color.White,
                ),
                border = null,
            ) {
                Text(
                    text = Localizations.tr(
                        appLanguage,
                        ru = "Закрыть",
                        en = "Close",
                        de = "Schließen",
                        es = "Cerrar",
                        fr = "Fermer",
                        pt = "Fechar",
                        tr = "Kapat"
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Button(
                onClick = {
                    onDismiss()
                    onSend()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(23.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = Color.White,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send_airplane),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "Отправить",
                            en = "Send",
                            de = "Senden",
                            es = "Enviar",
                            fr = "Envoyer",
                            pt = "Enviar",
                            tr = "Gönder"
                        ),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

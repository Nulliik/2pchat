package com.example.twopchat.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QualityTooltipBubble(
    isHd: Boolean,
    appLanguage: String,
    modifier: Modifier = Modifier,
    arrowAtTop: Boolean = true,
    arrowEndPadding: Dp = 18.dp,
) {
    val bubbleColor = Color(0xFA242426)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
    ) {
        if (arrowAtTop) {
            Canvas(
                modifier = Modifier
                    .padding(end = arrowEndPadding)
                    .size(width = 12.dp, height = 6.dp)
            ) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color = bubbleColor)
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = bubbleColor,
            shadowElevation = 6.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                // White rounded badge with bold black letters
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (isHd) "HD" else "SD",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHd) {
                        Localizations.tr(
                            appLanguage,
                            ru = "Фотография будет в высоком разрешении.",
                            en = "Photo will be sent in high quality.",
                            de = "Foto wird in hoher Qualität gesendet.",
                            es = "La foto se enviará en alta calidad.",
                            fr = "La photo sera envoyée en haute qualité.",
                            pt = "A foto será enviada em alta qualidade.",
                            tr = "Fotoğraf yüksek kalitede gönderilecek."
                        )
                    } else {
                        Localizations.tr(
                            appLanguage,
                            ru = "Фотография будет в обычном разрешении.",
                            en = "Photo will be sent in standard quality.",
                            de = "Foto wird in Standardqualität gesendet.",
                            es = "La foto se enviará en calidad estándar.",
                            fr = "La photo sera envoyée en qualité standard.",
                            pt = "A foto será enviada em qualidade padrão.",
                            tr = "Fotoğraf standart kalitede gönderilecek."
                        )
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (!arrowAtTop) {
            Canvas(
                modifier = Modifier
                    .padding(end = arrowEndPadding)
                    .size(width = 12.dp, height = 6.dp)
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(path, color = bubbleColor)
            }
        }
    }
}

@Composable
fun AnimatedQualityToggle(
    isHd: Boolean,
    onToggle: () -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = isHd,
            transitionSpec = {
                (scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(tween(160)))
                    .togetherWith(scaleOut(animationSpec = tween(120)) + fadeOut(tween(120)))
            },
            label = "QualityIconAnimation"
        ) { hd ->
            Icon(
                painter = painterResource(id = if (hd) R.drawable.ic_quality_hd else R.drawable.ic_quality_sd),
                contentDescription = if (hd) "HD Quality" else "SD Quality",
                tint = if (hd) activeColor else inactiveColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

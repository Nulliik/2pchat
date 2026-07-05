package com.example.twopchat.ui.disguise

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.data.Localizations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyRatesScreen(
    appLanguage: String,
    onUnlock: () -> Unit
) {
    val context = LocalContext.current
    var clickCount by remember { mutableStateOf(0) }
    var inputAmount by remember { mutableStateOf("") }
    var resultAmount by remember { mutableStateOf("0.00") }
    var fromCurrency by remember { mutableStateOf("USD") }
    var toCurrency by remember { mutableStateOf("RUB") }

    val rate = 88.54

    // Simple calculator logic
    LaunchedEffect(inputAmount) {
        val amt = inputAmount.toDoubleOrNull()
        if (amt != null) {
            resultAmount = String.format("%.2f", amt * rate)
        } else {
            resultAmount = "0.00"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (appLanguage == "Русский") "Курсы валют" else "Currency Rates",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.clickable {
                            clickCount++
                            if (clickCount >= 3) {
                                Toast.makeText(context, "Access Granted", Toast.LENGTH_SHORT).show()
                                onUnlock()
                            }
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                ),
                actions = {
                    IconButton(onClick = {
                        // Reset conversion
                        inputAmount = ""
                    }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                }
            )
        },
        containerColor = Color(0xFF0B0F19)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Live Rates Header
            Text(
                text = if (appLanguage == "Русский") "Рынки сегодня" else "Markets Today",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Market rate cards with mock line graphs
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("USD / RUB", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("88.54 RUB", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    }
                    // Mini Sparkline Graph
                    Canvas(modifier = Modifier.size(60.dp, 30.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 30f)
                            lineTo(15f, 20f)
                            lineTo(30f, 25f)
                            lineTo(45f, 5f)
                            lineTo(60f, 10f)
                        }
                        drawPath(path, color = Color(0xFF10B981), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("EUR / RUB", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("95.80 RUB", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    }
                    Canvas(modifier = Modifier.size(60.dp, 30.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 5f)
                            lineTo(15f, 15f)
                            lineTo(30f, 10f)
                            lineTo(45f, 28f)
                            lineTo(60f, 25f)
                        }
                        drawPath(path, color = Color(0xFFEF4444), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("BTC / USD", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("61,420.50 USD", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    }
                    Canvas(modifier = Modifier.size(60.dp, 30.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 28f)
                            lineTo(15f, 22f)
                            lineTo(30f, 12f)
                            lineTo(45f, 18f)
                            lineTo(60f, 2f)
                        }
                        drawPath(path, color = Color(0xFF10B981), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Conversion Tool Header
            Text(
                text = if (appLanguage == "Русский") "Конвертер валют" else "Quick Convert",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Conversion Panel Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = if (appLanguage == "Русский") "Сумма для конвертации" else "Amount to Convert",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = inputAmount,
                        onValueChange = {
                            inputAmount = it
                            // Secret numeric passcodes trigger: 777 or 2002
                            if (it == "777" || it == "2002") {
                                Toast.makeText(context, "Access Granted", Toast.LENGTH_SHORT).show()
                                onUnlock()
                            }
                        },
                        placeholder = { Text("0.00", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Conversion Results Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$fromCurrency ➔ $toCurrency",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "$resultAmount RUB",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "1 USD = $rate RUB (Updated 2 min ago)",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

package com.example.twopchat.ui.main

import android.widget.Toast
import com.example.twopchat.data.Localizations
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LanguageSettingsPage(
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onBackClick: () -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    primaryColor: Color
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val languageList = remember {
        listOf(
            Triple("Русский", "Russian", "🇷🇺"),
            Triple("English", "English", "🇬🇧"),
            Triple("Deutsch", "German", "🇩🇪"),
            Triple("Español", "Spanish", "🇪🇸"),
            Triple("Français", "French", "🇫🇷"),
            Triple("Português", "Portuguese", "🇵🇹"),
            Triple("Türkçe", "Turkish", "🇹🇷")
        )
    }

    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) languageList
        else languageList.filter {
            it.first.contains(searchQuery, ignoreCase = true) || it.second.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Top Bar Header: Back button, Title, and Search icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_back_arrow),
                    contentDescription = Localizations.tr(appLanguage, ru = "Назад", en = "Back", de = "Zurück", es = "Atrás", fr = "Retour", pt = "Voltar", tr = "Geri"),
                    tint = onSurfaceColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isSearching) {
                val searchContext = LocalContext.current
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = onSurfaceColor),
                    singleLine = true,
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = searchContext,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(surfaceColor, shape = RoundedCornerShape(12.dp))
                        .border(1.5.dp, primaryColor, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = Localizations.tr(appLanguage, ru = "Поиск языка...", en = "Search language...", de = "Sprache suchen...", es = "Buscar idioma...", fr = "Rechercher une langue...", pt = "Pesquisar idioma...", tr = "Dil ara..."),
                                    fontSize = 14.sp,
                                    color = onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = {
                        isSearching = false
                        searchQuery = ""
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Text(
                    text = Localizations.tr(appLanguage, ru = "Язык", en = "Language", de = "Sprache", es = "Idioma", fr = "Langue", pt = "Idioma", tr = "Dil"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { isSearching = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Section Title: "Язык" / "Language"
        Text(
            text = Localizations.tr(appLanguage, ru = "Язык", en = "Language", de = "Sprache", es = "Idioma", fr = "Langue", pt = "Idioma", tr = "Dil"),
            color = Color(0xFF00E676),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(filteredLanguages) { (nativeName, englishName, flag) ->
                val isSelected = appLanguage == nativeName
                val activeGreen = Color(0xFF00E676)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            val selectedLang = nativeName
                            onLanguageChanged(selectedLang)
                            val toastMsg = when (selectedLang) {
                                "Русский" -> "Язык изменен на Русский"
                                "Deutsch" -> "Sprache auf Deutsch geändert"
                                "Español" -> "Idioma cambiado a Español"
                                "Français" -> "Langue changée en Français"
                                "Português" -> "Idioma alterado para Português"
                                "Türkçe" -> "Dil Türkçe olarak değiştirildi"
                                else -> "Language changed to $englishName"
                            }
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) activeGreen else onSurfaceVariant.copy(alpha = 0.45f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(activeGreen, CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Text(text = flag, fontSize = 22.sp)

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = nativeName,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = onSurfaceColor
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = englishName,
                            fontSize = 12.sp,
                            color = onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
    }
}

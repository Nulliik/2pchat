package com.example.twopchat

object TorStatusFormatter {
    fun formatStatus(isRunning: Boolean, isConnecting: Boolean = false, appLanguage: String = "English", progress: Int = 0, isRotatingBridge: Boolean = false): String {
        val lang = appLanguage.lowercase()
        if (isRotatingBridge) {
            return when {
                lang.startsWith("рус") || lang == "ru" -> "🔄 Смена моста..."
                lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "🔄 Brücke wechseln..."
                lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "🔄 Cambiando puente..."
                lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "🔄 Changement de pont..."
                lang.startsWith("por") || lang == "pt" -> "🔄 Trocando ponte..."
                else -> "🔄 Rotating bridge..."
            }
        }
        return when {
            isRunning -> when {
                lang.startsWith("рус") || lang == "ru" -> "Подключено к Tor"
                lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "Mit Tor verbunden"
                lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "Conectado a Tor"
                lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "Connecté à Tor"
                lang.startsWith("por") || lang == "pt" -> "Conectado ao Tor"
                else -> "Connected to Tor"
            }
            isConnecting -> {
                val progressSuffix = if (progress > 0) " ($progress%)" else ""
                when {
                    lang.startsWith("рус") || lang == "ru" -> "Подключение...$progressSuffix"
                    lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "Verbindung wird hergestellt...$progressSuffix"
                    lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "Conectando...$progressSuffix"
                    lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "Connexion en cours...$progressSuffix"
                    lang.startsWith("por") || lang == "pt" -> "Conectando...$progressSuffix"
                    else -> "Connecting...$progressSuffix"
                }
            }
            else -> when {
                lang.startsWith("рус") || lang == "ru" -> "Отключено"
                lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "Getrennt"
                lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "Desconectado"
                lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "Déconnecté"
                lang.startsWith("por") || lang == "pt" -> "Desconectado"
                else -> "Disconnected"
            }
        }
    }

    fun formatStatus(isRunning: Boolean, isConnecting: Boolean, isRussian: Boolean, progress: Int = 0): String {
        return formatStatus(isRunning, isConnecting, if (isRussian) "Русский" else "English", progress)
    }

    fun getActivationToast(appLanguage: String): String {
        val lang = appLanguage.lowercase()
        return when {
            lang.startsWith("рус") || lang == "ru" -> "🧅 Анонимизация Tor включена. Задержка соединения может увеличиться."
            lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "🧅 Tor-Anonymisierung aktiviert. Die Verbindungsverzögerung kann sich erhöhen."
            lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "🧅 Privacidad de Tor activada. La latencia de conexión puede aumentar."
            lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "🧅 Anonymat Tor activé. La latence de connexion peut augmenter."
            lang.startsWith("por") || lang == "pt" -> "🧅 Privatização Tor ativada. A latência de conexão pode aumentar."
            else -> "🧅 Tor privacy enabled. Connection latency may increase."
        }
    }

    fun getFailedToast(appLanguage: String): String {
        val lang = appLanguage.lowercase()
        return when {
            lang.startsWith("рус") || lang == "ru" -> "🧅 Не удалось подключиться к Tor (проверьте мосты). Переход на прямое соединение."
            lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "🧅 Tor-Verbindung fehlgeschlagen (Brücken prüfen). Rückkehr zur direkten Verbindung."
            lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "🧅 Error al conectar con Tor (compruebe los puentes). Usando conexión directa."
            lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "🧅 Échec de la connexion à Tor (vérifiez les ponts). Retour à la connexion directe."
            lang.startsWith("por") || lang == "pt" -> "🧅 Falha ao conectar ao Tor (verifique as pontes). Usando conexão direta."
            else -> "🧅 Tor connection failed (check bridges). Falling back to direct connection."
        }
    }
}

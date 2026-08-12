package com.example.twopchat

object TorStatusFormatter {
    fun formatStatus(isRunning: Boolean, isConnecting: Boolean = false, appLanguage: String = "English"): String {
        val lang = appLanguage.lowercase()
        return when {
            isRunning -> when {
                lang.startsWith("рус") || lang == "ru" -> "Подключено к Tor"
                lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "Mit Tor verbunden"
                lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "Conectado a Tor"
                lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "Connecté à Tor"
                lang.startsWith("por") || lang == "pt" -> "Conectado ao Tor"
                else -> "Connected to Tor"
            }
            isConnecting -> when {
                lang.startsWith("рус") || lang == "ru" -> "Подключение..."
                lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "Verbindung wird hergestellt..."
                lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "Conectando..."
                lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "Connexion en cours..."
                lang.startsWith("por") || lang == "pt" -> "Conectando..."
                else -> "Connecting..."
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

    fun formatStatus(isRunning: Boolean, isConnecting: Boolean, isRussian: Boolean): String {
        return formatStatus(isRunning, isConnecting, if (isRussian) "Русский" else "English")
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
            lang.startsWith("рус") || lang == "ru" -> "🧅 Не удалось подключиться к Tor. Переход на прямое соединение."
            lang.startsWith("deu") || lang.startsWith("ger") || lang == "de" -> "🧅 Verbindung zum Tor-Daemon fehlgeschlagen. Auf direkte Verbindung zurückgekehrt."
            lang.startsWith("esp") || lang.startsWith("spa") || lang == "es" -> "🧅 Error al conectar con Tor. Usando conexión directa."
            lang.startsWith("fra") || lang.startsWith("fre") || lang == "fr" -> "🧅 Échec de la connexion à Tor. Retour à la connexion directe."
            lang.startsWith("por") || lang == "pt" -> "🧅 Falha ao conectar ao Tor. Usando conexão direta."
            else -> "🧅 Failed to connect to Tor daemon. Falling back to direct connection."
        }
    }
}

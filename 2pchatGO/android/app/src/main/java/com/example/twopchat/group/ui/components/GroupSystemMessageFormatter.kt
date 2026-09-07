package com.example.twopchat.group.ui.components

import com.example.twopchat.data.Localizations
import com.example.twopchat.group.ui.GroupSystemEventType
import com.example.twopchat.group.ui.GroupTimelineMessage

object GroupSystemMessageFormatter {

    private const val MAX_TITLE_LENGTH = 32

    fun format(message: GroupTimelineMessage, language: String): String {
        val eventType = message.systemEventType ?: return ""
        val lang = normalizeLanguage(language)
        val actorName = if (message.isSelfActor) {
            Localizations.tr(lang, ru = "Вы", en = "You", de = "Du", es = "Tú", fr = "Vous", pt = "Você", tr = "Siz")
        } else {
            message.authorName.ifBlank {
                message.authorId.take(8) + "…"
            }
        }
        val targetName = if (message.isSelfTarget) {
            Localizations.tr(lang, ru = "вас", en = "you", de = "dich", es = "te", fr = "vous", pt = "você", tr = "sizi")
        } else {
            message.targetMemberName.orEmpty().ifBlank {
                message.systemPayload["target_id"]?.take(8)?.let { "$it…" }
                    ?: (message.authorId.take(8) + "…")
            }
        }

        return when (eventType) {
            GroupSystemEventType.MEMBER_ADDED -> formatMemberAdded(message, actorName, targetName, lang)
            GroupSystemEventType.MEMBER_LEFT -> formatMemberLeft(message, targetName, lang)
            GroupSystemEventType.MEMBER_REMOVED -> formatMemberRemoved(message, actorName, targetName, lang)
            GroupSystemEventType.ROLE_CHANGED -> formatRoleChanged(message, actorName, targetName, lang)
            GroupSystemEventType.GROUP_NAME_CHANGED -> formatGroupNameChanged(message, actorName, lang)
            GroupSystemEventType.OWNERSHIP_TRANSFERRED -> formatOwnershipTransferred(message, actorName, targetName, lang)
        }
    }

    private fun normalizeLanguage(language: String): String = when (language.trim().lowercase()) {
        "ru", "rus", "russian", "русский" -> "Русский"
        "de", "deu", "ger", "german", "deutsch" -> "Deutsch"
        "es", "spa", "spanish", "español" -> "Español"
        "fr", "fra", "fre", "french", "français" -> "Français"
        "pt", "por", "portuguese", "português" -> "Português"
        "tr", "tur", "turkish", "türkçe" -> "Türkçe"
        else -> language
    }

    private fun formatMemberAdded(
        message: GroupTimelineMessage,
        actorName: String,
        targetName: String,
        language: String,
    ): String {
        return when {
            message.isSelfTarget -> Localizations.tr(
                language,
                ru = "Вы вступили в группу",
                en = "You joined the group",
                de = "Du bist der Gruppe beigetreten",
                es = "Te uniste al grupo",
                fr = "Vous avez rejoint le groupe",
                pt = "Você entrou no grupo",
                tr = "Gruba katıldınız",
            )
            message.isSelfActor -> Localizations.tr(
                language,
                ru = "Вы добавили $targetName",
                en = "You added $targetName",
                de = "Du hast $targetName hinzugefügt",
                es = "Añadiste a $targetName",
                fr = "Vous avez ajouté $targetName",
                pt = "Você adicionou $targetName",
                tr = "$targetName kişisini eklediniz",
            )
            else -> Localizations.tr(
                language,
                ru = "$actorName добавил(а) $targetName",
                en = "$actorName added $targetName",
                de = "$actorName hat $targetName hinzugefügt",
                es = "$actorName añadió a $targetName",
                fr = "$actorName a ajouté $targetName",
                pt = "$actorName adicionou $targetName",
                tr = "$actorName, $targetName kişisini ekledi",
            )
        }
    }

    private fun formatMemberLeft(
        message: GroupTimelineMessage,
        targetName: String,
        language: String,
    ): String {
        return if (message.isSelfTarget || message.isSelfActor) {
            Localizations.tr(
                language,
                ru = "Вы покинули группу",
                en = "You left the group",
                de = "Du hast die Gruppe verlassen",
                es = "Saliste del grupo",
                fr = "Vous avez quitté le groupe",
                pt = "Você saiu do grupo",
                tr = "Gruptan ayrıldınız",
            )
        } else {
            Localizations.tr(
                language,
                ru = "$targetName покинул(а) группу",
                en = "$targetName left the group",
                de = "$targetName hat die Gruppe verlassen",
                es = "$targetName salió del grupo",
                fr = "$targetName a quitté le groupe",
                pt = "$targetName saiu do grupo",
                tr = "$targetName gruptan ayrıldı",
            )
        }
    }

    private fun formatMemberRemoved(
        message: GroupTimelineMessage,
        actorName: String,
        targetName: String,
        language: String,
    ): String {
        return when {
            message.isSelfTarget -> Localizations.tr(
                language,
                ru = "Вас исключили из группы",
                en = "You were removed from the group",
                de = "Du wurdest aus der Gruppe entfernt",
                es = "Fuiste eliminado del grupo",
                fr = "Vous avez été retiré du groupe",
                pt = "Você foi removido do grupo",
                tr = "Gruptan çıkarıldınız",
            )
            message.isSelfActor -> Localizations.tr(
                language,
                ru = "Вы исключили $targetName",
                en = "You removed $targetName",
                de = "Du hast $targetName entfernt",
                es = "Eliminaste a $targetName",
                fr = "Vous avez retiré $targetName",
                pt = "Você removeu $targetName",
                tr = "$targetName kişisini gruptan çıkardınız",
            )
            else -> Localizations.tr(
                language,
                ru = "$actorName исключил(а) $targetName",
                en = "$actorName removed $targetName",
                de = "$actorName hat $targetName entfernt",
                es = "$actorName eliminó a $targetName",
                fr = "$actorName a retiré $targetName",
                pt = "$actorName removeu $targetName",
                tr = "$actorName, $targetName kişisini çıkardı",
            )
        }
    }

    private fun formatRoleChanged(
        message: GroupTimelineMessage,
        actorName: String,
        targetName: String,
        language: String,
    ): String {
        val rawRole = message.systemPayload["role"].orEmpty().uppercase()
        val roleLabel = when {
            rawRole.contains("ADMIN") -> Localizations.tr(
                language,
                ru = "администратором",
                en = "an administrator",
                de = "ein Administrator",
                es = "un administrador",
                fr = "un administrateur",
                pt = "um administrador",
                tr = "bir yönetici",
            )
            rawRole.contains("MODERAT") -> Localizations.tr(
                language,
                ru = "модератором",
                en = "a moderator",
                de = "ein Moderator",
                es = "un moderador",
                fr = "un modérateur",
                pt = "um moderador",
                tr = "bir moderatör",
            )
            else -> Localizations.tr(
                language,
                ru = "участником",
                en = "a member",
                de = "ein Mitglied",
                es = "un miembro",
                fr = "un membre",
                pt = "um membro",
                tr = "bir üye",
            )
        }

        return when {
            message.isSelfTarget -> Localizations.tr(
                language,
                ru = "Вас назначили $roleLabel",
                en = "You were assigned as $roleLabel",
                de = "Du wurdest zu $roleLabel ernannt",
                es = "Te asignaron como $roleLabel",
                fr = "Vous avez été nommé $roleLabel",
                pt = "Você foi nomeado como $roleLabel",
                tr = "$roleLabel olarak atandınız",
            )
            message.isSelfActor -> Localizations.tr(
                language,
                ru = "Вы назначили $targetName $roleLabel",
                en = "You made $targetName $roleLabel",
                de = "Du hast $targetName zu $roleLabel ernannt",
                es = "Hiciste a $targetName $roleLabel",
                fr = "Vous avez nommé $targetName $roleLabel",
                pt = "Você nomeou $targetName como $roleLabel",
                tr = "$targetName kişisini $roleLabel yaptınız",
            )
            else -> Localizations.tr(
                language,
                ru = "$actorName назначил(а) $targetName $roleLabel",
                en = "$actorName made $targetName $roleLabel",
                de = "$actorName hat $targetName zu $roleLabel ernannt",
                es = "$actorName hizo a $targetName $roleLabel",
                fr = "$actorName a nommé $targetName $roleLabel",
                pt = "$actorName nomeou $targetName como $roleLabel",
                tr = "$actorName, $targetName kişisini $roleLabel yaptı",
            )
        }
    }

    private fun formatGroupNameChanged(
        message: GroupTimelineMessage,
        actorName: String,
        language: String,
    ): String {
        val rawTitle = message.systemPayload["title"].orEmpty()
        val displayTitle = if (rawTitle.length > MAX_TITLE_LENGTH) {
            rawTitle.take(MAX_TITLE_LENGTH - 1) + "…"
        } else {
            rawTitle
        }

        return if (message.isSelfActor) {
            Localizations.tr(
                language,
                ru = "Вы изменили название группы на «$displayTitle»",
                en = "You changed the group name to \"$displayTitle\"",
                de = "Du hast den Gruppennamen in „$displayTitle“ geändert",
                es = "Cambiaste el nombre del grupo a «$displayTitle»",
                fr = "Vous avez changé le nom du groupe en «$displayTitle»",
                pt = "Você alterou o nome do grupo para \"$displayTitle\"",
                tr = "Grup adını \"$displayTitle\" olarak değiştirdiniz",
            )
        } else {
            Localizations.tr(
                language,
                ru = "$actorName изменил(а) название группы на «$displayTitle»",
                en = "$actorName changed the group name to \"$displayTitle\"",
                de = "$actorName hat den Gruppennamen in „$displayTitle“ geändert",
                es = "$actorName cambió el nombre del grupo a «$displayTitle»",
                fr = "$actorName a changé le nom du groupe en «$displayTitle»",
                pt = "$actorName alterou o nome do grupo para \"$displayTitle\"",
                tr = "$actorName grup adını \"$displayTitle\" olarak değiştirdi",
            )
        }
    }

    private fun formatOwnershipTransferred(
        message: GroupTimelineMessage,
        actorName: String,
        targetName: String,
        language: String,
    ): String {
        return when {
            message.isSelfTarget -> Localizations.tr(
                language,
                ru = "Вам передали права создателя группы",
                en = "Group ownership was transferred to you",
                de = "Das Gruppeneigentum wurde auf dich übertragen",
                es = "Se te transfirió la propiedad del grupo",
                fr = "La propriété du groupe vous a été transférée",
                pt = "A propriedade do grupo foi transferida para você",
                tr = "Grup sahipliği size devredildi",
            )
            message.isSelfActor -> Localizations.tr(
                language,
                ru = "Вы передали права создателя группы $targetName",
                en = "You transferred group ownership to $targetName",
                de = "Du hast das Gruppeneigentum an $targetName übertragen",
                es = "Transferiste la propiedad del grupo a $targetName",
                fr = "Vous avez transféré la propriété du groupe à $targetName",
                pt = "Você transferiu a propriedade do grupo para $targetName",
                tr = "Grup sahipliğini $targetName kişisine devrettiniz",
            )
            else -> Localizations.tr(
                language,
                ru = "$actorName передал(а) права создателя группы $targetName",
                en = "$actorName transferred group ownership to $targetName",
                de = "$actorName hat das Gruppeneigentum an $targetName übertragen",
                es = "$actorName transfirió la propiedad del grupo a $targetName",
                fr = "$actorName a transféré la propriété du groupe à $targetName",
                pt = "$actorName transferiu a propriedade do grupo para $targetName",
                tr = "$actorName grup sahipliğini $targetName kişisine devretti",
            )
        }
    }
}

package com.example.twopchat.group.runtime

import com.example.twopchat.group.storage.StoredGroupMessage

internal val groupTimelineOrder: Comparator<StoredGroupMessage> =
    compareBy(
        StoredGroupMessage::hlcPhysicalMs,
        StoredGroupMessage::hlcLogical,
        StoredGroupMessage::authorDeviceId,
        StoredGroupMessage::authorSeq,
        StoredGroupMessage::messageId,
    )

internal fun membersWhoReadMessage(
    message: StoredGroupMessage,
    readHorizonByMember: Map<String, StoredGroupMessage>,
): Set<String> = readHorizonByMember
    .filterValues { horizon -> groupTimelineOrder.compare(message, horizon) <= 0 }
    .keys

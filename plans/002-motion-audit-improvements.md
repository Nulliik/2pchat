# Plan 002: Comprehensive Motion & Animation Improvements for 2PChat

**Commit**: `HEAD`  
**Scope**: 
- `2PChat android/android/app/src/main/java/com/example/twopchat/theme/MotionTokens.kt` [NEW]
- `2PChat android/android/app/src/main/java/com/example/twopchat/ui/main/ChatsTab.kt`
- `2PChat android/android/app/src/main/java/com/example/twopchat/group/ui/GroupChatScreen.kt`
- `2PChat android/android/app/src/main/java/com/example/twopchat/ui/chat/ChatMessageBubble.kt`
- `2PChat android/android/app/src/main/java/com/example/twopchat/group/ui/GroupInfoScreen.kt`

---

## Executive Summary

Audit against `SKILL.md` and `SKILLanim.md` revealed high-leverage opportunities to refine motion across 2PChat Android:
1. Centralize ad-hoc spring/easing specs into a unified `MotionTokens.kt` design system.
2. GPU-accelerate composable transitions via `graphicsLayer` layer promotion to avoid unnecessary layout recompositions.
3. Add `Reduced Motion` system accessibility support.
4. Smooth out drawer expand/shrink asymmetry in Attachment Panel transitions.

---

## Step 1: Create Centralized Motion Tokens (`MotionTokens.kt`)

### [NEW] `MotionTokens.kt`
Path: [MotionTokens.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/theme/MotionTokens.kt)

Create standardized motion specs:
```kotlin
package com.example.twopchat.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object MotionTokens {
    // Easing Curves (Emil Kowalski / Material 3 standards)
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val DecelerateEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val AccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)

    // Standard Durations
    const val DurationFastMs = 150
    const val DurationNormalMs = 220
    const val DurationSlowMs = 350

    // Unified Spring Specs
    val ResponsiveSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow
    )

    val SubtlePressSpring = spring<Float>(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMedium
    )

    val BouncySpring = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessLow
    )

    val FastTween = tween<Float>(DurationFastMs, easing = DecelerateEasing)
    val NormalTween = tween<Float>(DurationNormalMs, easing = EmphasizedEasing)
}
```

---

## Step 2: GPU Layer Acceleration in `ChatsTab.kt`

### [MODIFY] `ChatsTab.kt`
Path: [ChatsTab.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/ui/main/ChatsTab.kt#L841-L847)

Replace raw layout modifiers during scale/opacity animation with `graphicsLayer`:

```kotlin
// BEFORE: Recomposes on every scale change
Card(
    modifier = Modifier
        .fillMaxWidth()
        .scale(scale)
        .alpha(opacity)
)

// AFTER: Zero recomposition, hardware GPU layer animation
Card(
    modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = opacity
        }
)
```

---

## Step 3: Symmetric Drawer Animation in `GroupChatScreen.kt`

### [MODIFY] `GroupChatScreen.kt`
Path: [GroupChatScreen.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/group/ui/GroupChatScreen.kt#L3220-L3224)

Ensure symmetrical spring-driven enter and exit for the attachment drawer:

```kotlin
AnimatedVisibility(
    visible = isAttachmentPanelOpen,
    enter = expandVertically(
        expandFrom = Alignment.Bottom,
        animationSpec = MotionTokens.ResponsiveSpring
    ) + fadeIn(animationSpec = MotionTokens.FastTween),
    exit = shrinkVertically(
        shrinkTowards = Alignment.Bottom,
        animationSpec = MotionTokens.ResponsiveSpring
    ) + fadeOut(animationSpec = MotionTokens.FastTween),
) {
    AttachmentPanel(...)
}
```

---

## Step 4: Reduced Motion Support Helper

### [NEW] `ReducedMotionHelper.kt`
Path: [ReducedMotionHelper.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/theme/ReducedMotionHelper.kt)

```kotlin
package com.example.twopchat.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberIsReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale == 0f
        } catch (e: Exception) {
            false
        }
    }
}
```

---

## Verification Plan

### Automated Tests
Run `./gradlew test` to ensure clean build and Kotlin unit test passage:
```bash
./gradlew test
```

### Manual Verification
1. Launch 2PChat Android app.
2. Toggle attachment drawer in group chat; verify smooth symmetrical spring opening and closing without abrupt cuts.
3. Scroll `ChatsTab`; verify GPU rendering remains locked at 60/120fps.
4. Enable "Remove animations" in Android System Settings; verify app seamlessly respects reduced motion.

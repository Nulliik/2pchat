# Plan 001: Motion & Animation Enhancements for 2PChat (WebUI & Android)

**Commit**: `HEAD`  
**Scope**: `webui/src/styles.css`, `webui/src/App.tsx`, `2PChat android/android/app/src/main/java/com/example/twopchat/ui/chat/SwipeToReplyContainer.kt`, `2PChat android/android/app/src/main/java/com/example/twopchat/ui/chat/ChatMessageBubble.kt`

---

## Executive Summary

Audit against `SKILLanim.md` and `SKILL.md` revealed that while 2PChat Android has solid motion primitives, it suffers from over-bouncy spring release physics in `SwipeToReplyContainer.kt` and exaggerated scale-in entry (`0.8f` -> `1.0f`) in `ChatMessageBubble.kt`. Meanwhile, `webui` currently has zero CSS transitions, causing teleporting message states and unresposive button feedback.

This plan details precise, high-leverage motion improvements across both clients.

---

## Step 1: WebUI Interactive Press Feedback & Smooth State Transitions

### Target File: `webui/src/styles.css`
Link: [styles.css](file:///Users/kodzy/Documents/GitHub/2pchat/webui/src/styles.css)

#### 1.1 Add Global Easing & Motion Tokens
```css
:root {
  --ease-out-quint: cubic-bezier(0.22, 1, 0.36, 1);
  --ease-sharp: cubic-bezier(0.2, 0, 0, 1);
  --duration-fast: 160ms;
  --duration-normal: 200ms;
}
```

#### 1.2 Add Button Press & Hover States (`Feedback` & `Tens/day`)
```css
.settings button,
.composer button {
  transition: transform var(--duration-fast) var(--ease-sharp),
              background-color var(--duration-fast) ease,
              box-shadow var(--duration-fast) ease;
  cursor: pointer;
}

@media (hover: hover) and (pointer: fine) {
  .settings button:hover,
  .composer button:hover {
    opacity: 0.92;
  }
}

.settings button:active,
.composer button:active {
  transform: scale(0.97);
}
```

#### 1.3 Add Status Badge Transition (`State Indication` & `Occasional`)
```css
.chip {
  border-radius: 999px;
  padding: 0.3rem 0.7rem;
  font-weight: bold;
  transition: background-color var(--duration-normal) var(--ease-sharp),
              color var(--duration-normal) ease;
}
```

#### 1.4 Add Chat Message Entry (`Preventing Jarring Change` & `Tens/day`)
```css
.chatlog p {
  margin: 0.4rem 0;
  padding: 0.4rem 0.6rem;
  border-radius: 6px;
  background: #f7f9fc;
  transition: opacity 180ms var(--ease-out-quint),
              transform 180ms var(--ease-out-quint);
}

@starting-style {
  .chatlog p {
    opacity: 0;
    transform: translateY(4px);
  }
}
```

---

## Step 2: Android Swipe-to-Reply Spring Physics Refinement

### Target File: `SwipeToReplyContainer.kt`
Link: [SwipeToReplyContainer.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/ui/chat/SwipeToReplyContainer.kt#L53)

#### Current Code Excerpt (Lines 53, 60):
```kotlin
offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
```

#### Target Code Replacement:
Replace `DampingRatioMediumBouncy` with non-bouncy damping (`1.0f`) to prevent distracting oscillations when releasing a swipe:
```kotlin
offsetX.animateTo(
    targetValue = 0f,
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
)
```

---

## Step 3: Android Message Arrival Scale Entrance Refinement

### Target File: `ChatMessageBubble.kt`
Link: [ChatMessageBubble.kt](file:///Users/kodzy/Documents/GitHub/2pchat/2PChat%20android/android/app/src/main/java/com/example/twopchat/ui/chat/ChatMessageBubble.kt)

#### Target Code Adjustment:
Ensure entrance scale starts at `0.96f` instead of `0.8f` or `0.0f`, paired with an expedited 180ms ease-out duration:
```kotlin
AnimatedVisibility(
    visibleState = transitionState,
    enter = fadeIn(animationSpec = tween(180, easing = LinearOutSlowInEasing)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            slideInVertically(initialOffsetY = { it / 6 }, animationSpec = tween(180, easing = FastOutSlowInEasing))
)
```

---

## Verification Plan

### Automated Tests
Run pytest in `messenger/` to ensure python backend integration remains intact:
```bash
pytest
```

### Manual Feel Check
1. WebUI: Click buttons to feel `scale(0.97)` instant 160ms response. Send messages and verify gentle 180ms vertical fade entry.
2. Android: Drag a message bubble to reply and release; confirm single smooth return to origin without bouncing overshoot.

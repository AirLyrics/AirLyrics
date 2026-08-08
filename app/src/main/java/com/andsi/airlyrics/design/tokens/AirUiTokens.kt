@file:Suppress("ConstPropertyName")

package com.andsi.airlyrics.design.tokens

/** Shared UI constants for the classic View-based screens and floating surface. */
object AirUiTokens {
    object Space {
        const val Xxs = 2
        const val Xs = 3
        const val Sm = 4
        const val Md = 5
        const val Lg = 6
        const val Xl = 8
        const val Xxl = 10
        const val ControlV = 11
        const val ButtonH = 16
        const val CardV = 16
        const val CardH = 18
        const val PageH = 20
        const val PageTop = 6
        const val PageBottom = 24
        const val DialogH = 22
        const val DialogTop = 20
        const val DialogBottom = 16
    }

    object Radius {
        const val Sm = 16
        const val Md = 18
        const val Card = 24
        const val Dialog = 28
        const val Pill = 99
    }

    object Stroke {
        const val Hairline = 1
    }

    object TextSize {
        const val Tiny = 11f
        const val Caption = 12f
        const val BodySmall = 13f
        const val Body = 14f
        const val Button = 15f
        const val Title = 20f
        const val DialogTitle = 21f
        const val PageTitle = 22f
    }

    object Motion {
        const val PressDownMs = 70L
        const val PressUpMs = 150L
        const val PulseUpMs = 80L
        const val PulseDownMs = 140L
        const val LayoutChangeMs = 170L
        const val PageEnterMs = 230L
        const val ChildEnterMs = 220L
        const val ChildDelayStepMs = 24L
        const val RefreshSpinMs = 420L
        const val FeedbackInMs = 160L
        const val HintOutMs = 120L
        const val FeedbackOutMs = 240L
        const val FeedbackHoldMs = 900L

        const val PressAlpha = 0.88f
        const val DefaultPressScale = 0.97f
        const val OptionPressScale = 0.96f
        const val StrongPressScale = 0.94f
        const val TinyPulseScale = 1.025f
        const val RestScale = 1f
        const val RestAlpha = 1f
        const val FloatingCardPressScale = 0.985f
        const val FloatingTilePressScale = 0.975f
        const val OvershootSoft = 0.52f
        const val OvershootTiny = 0.48f
    }

    object Layout {
        const val OptionColumns = 2
        const val SwatchColumns = 3
        const val ChildEnterMaxIndex = 8
        const val PageEnterDistance = 26
        const val ChildEnterDistance = 12
        const val StatusIconSize = 22
        const val DialogCloseSize = 36
        const val ThemeToggleSize = 42
        const val SettingsIconBubbleSize = 46
        const val FloatingTileIconSize = 40
        const val FloatingResetActionWidth = 72
        const val ColorSwatchHeight = 42
        const val FloatingTileHeight = 112
        const val FloatingPanelWidthInset = 72
        const val FloatingPanelMaxWidth = 360
        const val BottomBarHeight = 86
        const val BottomTabIconTextSp = 24
        const val BottomTabLabelTextSp = 10
        const val ChevronTextSp = 28f
        const val BottomTabFloatingPadding = 62
        const val BottomTabDefaultPadding = 58
        const val BottomTabMinWidth = 104
        const val BottomTabFloatingMaxWidth = 136
        const val BottomTabDefaultMaxWidth = 144
        const val BottomTabFloatingHeight = 56
        const val BottomTabDefaultHeight = 48
        const val DialogDimAmount = 0.28f
        const val TabTextSwapAlpha = 0.55f
        const val TabTextSwapScale = 0.92f
        const val TabSelectedScale = 1.02f
        const val TabQuickScale = 1.14f
        const val TabUnselectedAlpha = 0.86f
        const val TabAnimationMs = 190L
        const val NavTapDownMs = 70L
        const val NavTapUpMs = 180L
        const val NavTapOvershoot = 1.45f
        const val MediaDoneSettleMs = 130L
        const val MediaDoneRefreshMs = 260L
        const val MediaRefreshingMs = 650L
        const val FastFadeMs = 90L
        const val RestoreFadeMs = 180L
        const val LyricsFadeMs = 170L
        const val LyricsSlideMs = 190L
        const val LyricsScaleFadeMs = 180L
        const val LyricsSlideDistanceDp = 8
        const val LyricsScaleStart = 0.96f
        const val TabOvershoot = 1.08f
        const val MediaDoneRotation = -2f
        const val MediaDoneScale = 1.018f
        const val MediaDoneOvershoot = 0.65f
    }
}

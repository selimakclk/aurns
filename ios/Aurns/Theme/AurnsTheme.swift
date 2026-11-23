import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

/// Windows (Direct2D) ve Android (Compose) ile %100 birebir renk sistemi
struct AurnsPalette {
    let isLight: Bool
    let bgShell: Color
    let bgSurface: Color
    let bgCard: Color
    let bgCardHover: Color
    let bgElevated: Color
    let bgInput: Color
    let border: Color
    let borderSubtle: Color
    let divider: Color
    let heroTop: Color
    let nowPlayingTop: Color
    let miniPlayerBg: Color
    let textPri: Color
    let textDim: Color
    let textMuted: Color
    let greenAccent: Color
    let greenHover: Color
    let greenText: Color
    let onGreen: Color
    let playBg: Color
    let playFg: Color
    let selBg: Color
    let selFg: Color
    let greenSoftCard: Color
    let greenSoftPill: Color
    let activeRowBg: Color
    let danger: Color
    let dangerBg: Color
    let amber: Color
    let barTrack: Color

    static let dark = AurnsPalette(
        isLight: false,
        bgShell: Color(hex: 0x000000),
        bgSurface: Color(hex: 0x121212),
        bgCard: Color(hex: 0x181818),
        bgCardHover: Color(hex: 0x242424),
        bgElevated: Color(hex: 0x282828),
        bgInput: Color(hex: 0x242424),
        border: Color(hex: 0x2E2E2E),
        borderSubtle: Color(hex: 0x1F1F1F),
        divider: Color.white.opacity(0.08),
        heroTop: Color(hex: 0x0B183D),
        nowPlayingTop: Color(hex: 0x0C1A42),
        miniPlayerBg: Color(hex: 0x1E1E1E),
        textPri: Color(hex: 0xFFFFFF),
        textDim: Color(hex: 0xB3B3B3),
        textMuted: Color(hex: 0x727272),
        greenAccent: Color(hex: 0x16358A),
        greenHover: Color(hex: 0x2652D7),
        greenText: Color(hex: 0x6B99FF),
        onGreen: Color(hex: 0xFFFFFF),
        playBg: Color(hex: 0xFFFFFF),
        playFg: Color(hex: 0x000000),
        selBg: Color(hex: 0xFFFFFF),
        selFg: Color(hex: 0x000000),
        greenSoftCard: Color(hex: 0x0E1A36),
        greenSoftPill: Color(hex: 0x13224A),
        activeRowBg: Color(hex: 0x101B38),
        danger: Color(hex: 0xF15E6C),
        dangerBg: Color(hex: 0x34181C),
        amber: Color(hex: 0xFFB020),
        barTrack: Color.white.opacity(0.16)
    )

    static let light = AurnsPalette(
        isLight: true,
        bgShell: Color(hex: 0xEFF1F4),
        bgSurface: Color(hex: 0xFFFFFF),
        bgCard: Color(hex: 0xF4F6F9),
        bgCardHover: Color(hex: 0xE8EBF0),
        bgElevated: Color(hex: 0xDEE2E8),
        bgInput: Color(hex: 0xE4E7EC),
        border: Color.black.opacity(0.10),
        borderSubtle: Color.black.opacity(0.06),
        divider: Color.black.opacity(0.08),
        heroTop: Color(hex: 0xDDE6FC),
        nowPlayingTop: Color(hex: 0xD6E2FC),
        miniPlayerBg: Color(hex: 0xFFFFFF),
        textPri: Color(hex: 0x111114),
        textDim: Color(hex: 0x52565C),
        textMuted: Color(hex: 0x82868E),
        greenAccent: Color(hex: 0x16358A),
        greenHover: Color(hex: 0x2652D7),
        greenText: Color(hex: 0x153488),
        onGreen: Color(hex: 0xFFFFFF),
        playBg: Color(hex: 0x121214),
        playFg: Color(hex: 0xFFFFFF),
        selBg: Color(hex: 0x121214),
        selFg: Color(hex: 0xFFFFFF),
        greenSoftCard: Color(hex: 0xE6EDFE),
        greenSoftPill: Color(hex: 0xD4E0FD),
        activeRowBg: Color(hex: 0xE8EEFD),
        danger: Color(hex: 0xD22838),
        dangerBg: Color(hex: 0xFDE8EB),
        amber: Color(hex: 0xE09200),
        barTrack: Color(hex: 0xD2D6DC)
    )
}

private struct AurnsPaletteKey: EnvironmentKey {
    static let defaultValue: AurnsPalette = .dark
}

extension EnvironmentValues {
    var aurnsPalette: AurnsPalette {
        get { self[AurnsPaletteKey.self] }
        set { self[AurnsPaletteKey.self] = newValue }
    }
}

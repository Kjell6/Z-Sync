import Foundation

// MARK: - Wire format (Zen Spaces Sync collection, PR #13984 / #13598)

/// Every record in the `spaces` collection decrypts to
/// `{ id, kind, data }`. Unknown kinds are ignored (forward compatible).
enum ZenRecordKind: String {
    case container
    case space
    case tab
    case folder
    case split
    case layout
}

/// A single color / dot definition inside a Zen Space theme.
/// Matches Zen Browser's desktop `ZenGradientGenerator.mjs` data structure.
struct ZenThemeDot: Codable, Equatable, Hashable {
    var color: ZenColorValue
    var isCustom: Bool
    var isPrimary: Bool
    var algorithm: String?
    var lightness: Double?
    var positionX: Double?
    var positionY: Double?
    var type: String?

    init(
        color: ZenColorValue,
        isCustom: Bool = false,
        isPrimary: Bool = false,
        algorithm: String? = nil,
        lightness: Double? = nil,
        positionX: Double? = nil,
        positionY: Double? = nil,
        type: String? = nil
    ) {
        self.color = color
        self.isCustom = isCustom
        self.isPrimary = isPrimary
        self.algorithm = algorithm
        self.lightness = lightness
        self.positionX = positionX
        self.positionY = positionY
        self.type = type
    }

    private enum CodingKeys: String, CodingKey {
        case c, isCustom, isPrimary, algorithm, lightness, position, type
    }

    private enum PositionKeys: String, CodingKey {
        case x, y
    }

    init(from decoder: Decoder) throws {
        // 1. If the element is a plain string (e.g. "#4f46e5" or "rgb(1,2,3)")
        if let singleContainer = try? decoder.singleValueContainer() {
            if let str = try? singleContainer.decode(String.self),
               let val = ZenColorValue.from(hex: str) ?? ZenColorValue.from(rgbString: str) {
                self.init(color: val)
                return
            }
            if let arr = try? singleContainer.decode([Double].self), arr.count >= 3 {
                self.init(color: ZenColorValue(r255: arr[0], g255: arr[1], b255: arr[2]))
                return
            }
        }

        // 2. If the element is a dictionary object
        let c = try decoder.container(keyedBy: CodingKeys.self)

        // Decode color value 'c' which can be [Int], [Double], "#hex", or "rgb(...)"
        var resolvedColor = ZenColorValue(red: 0.5, green: 0.5, blue: 0.5)
        if let rgbArr = try? c.decode([Double].self, forKey: .c), rgbArr.count >= 3 {
            resolvedColor = ZenColorValue(r255: rgbArr[0], g255: rgbArr[1], b255: rgbArr[2])
        } else if let rgbArrInt = try? c.decode([Int].self, forKey: .c), rgbArrInt.count >= 3 {
            resolvedColor = ZenColorValue(r255: Double(rgbArrInt[0]), g255: Double(rgbArrInt[1]), b255: Double(rgbArrInt[2]))
        } else if let hexStr = try? c.decode(String.self, forKey: .c) {
            resolvedColor = ZenColorValue.from(hex: hexStr) ?? ZenColorValue.from(rgbString: hexStr) ?? resolvedColor
        }

        let custom = (try? c.decodeIfPresent(Bool.self, forKey: .isCustom)) ?? false
        let primary = (try? c.decodeIfPresent(Bool.self, forKey: .isPrimary)) ?? false
        let algo = try? c.decodeIfPresent(String.self, forKey: .algorithm)

        let light: Double?
        if let lDouble = try? c.decodeIfPresent(Double.self, forKey: .lightness) {
            light = lDouble
        } else if let lStr = try? c.decodeIfPresent(String.self, forKey: .lightness), let parsed = Double(lStr) {
            light = parsed
        } else {
            light = nil
        }

        var posX: Double?
        var posY: Double?
        if let posContainer = try? c.nestedContainer(keyedBy: PositionKeys.self, forKey: .position) {
            posX = try? posContainer.decodeIfPresent(Double.self, forKey: .x)
            posY = try? posContainer.decodeIfPresent(Double.self, forKey: .y)
        }

        let dotType = try? c.decodeIfPresent(String.self, forKey: .type)

        self.init(
            color: resolvedColor,
            isCustom: custom,
            isPrimary: primary,
            algorithm: algo,
            lightness: light,
            positionX: posX,
            positionY: posY,
            type: dotType
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(color.hexString, forKey: .c)
        try container.encode(isCustom, forKey: .isCustom)
        try container.encode(isPrimary, forKey: .isPrimary)
        try container.encodeIfPresent(algorithm, forKey: .algorithm)
        try container.encodeIfPresent(lightness, forKey: .lightness)
        if let x = positionX, let y = positionY {
            var posContainer = container.nestedContainer(keyedBy: PositionKeys.self, forKey: .position)
            try posContainer.encode(x, forKey: .x)
            try posContainer.encode(y, forKey: .y)
        }
        try container.encodeIfPresent(type, forKey: .type)
    }
}

/// Robust color representation supporting RGB components and Hex conversion.
struct ZenColorValue: Codable, Equatable, Hashable {
    var red: Double   // 0.0 ... 1.0
    var green: Double // 0.0 ... 1.0
    var blue: Double  // 0.0 ... 1.0
    var alpha: Double // 0.0 ... 1.0

    var hexString: String {
        let r = Int(round(red * 255))
        let g = Int(round(green * 255))
        let b = Int(round(blue * 255))
        return String(format: "#%02x%02x%02x", r, g, b)
    }

    init(red: Double, green: Double, blue: Double, alpha: Double = 1.0) {
        self.red = min(max(red, 0), 1)
        self.green = min(max(green, 0), 1)
        self.blue = min(max(blue, 0), 1)
        self.alpha = min(max(alpha, 0), 1)
    }

    init(r255: Double, g255: Double, b255: Double, alpha: Double = 1.0) {
        self.init(red: r255 / 255.0, green: g255 / 255.0, blue: b255 / 255.0, alpha: alpha)
    }

    static func from(hex: String) -> ZenColorValue? {
        var text = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6 || text.count == 8 || text.count == 3 else { return nil }
        if text.count == 3 {
            text = text.map { "\($0)\($0)" }.joined()
        }
        var value: UInt64 = 0
        guard Scanner(string: text).scanHexInt64(&value) else { return nil }
        if text.count == 8 {
            let r = Double((value >> 24) & 0xFF) / 255.0
            let g = Double((value >> 16) & 0xFF) / 255.0
            let b = Double((value >> 8) & 0xFF) / 255.0
            let a = Double(value & 0xFF) / 255.0
            return ZenColorValue(red: r, green: g, blue: b, alpha: a)
        } else {
            let r = Double((value >> 16) & 0xFF) / 255.0
            let g = Double((value >> 8) & 0xFF) / 255.0
            let b = Double(value & 0xFF) / 255.0
            return ZenColorValue(red: r, green: g, blue: b, alpha: 1.0)
        }
    }

    static func from(rgbString: String) -> ZenColorValue? {
        let digits = rgbString.components(separatedBy: CharacterSet.decimalDigits.inverted)
            .filter { !$0.isEmpty }
            .compactMap { Double($0) }
        guard digits.count >= 3 else { return nil }
        let a = digits.count >= 4 ? digits[3] : 1.0
        return ZenColorValue(r255: digits[0], g255: digits[1], b255: digits[2], alpha: a <= 1.0 ? a : a / 255.0)
    }
}

struct ZenSpaceTheme: Codable, Equatable, Hashable {
    var type: String?
    var dots: [ZenThemeDot]
    var opacity: Double?
    var texture: Double?
    var lightness: Double?

    var gradientColors: [String]? {
        let hexes = dots.map(\.color.hexString)
        return hexes.isEmpty ? nil : hexes
    }

    var isDarkTheme: Bool? {
        guard !dots.isEmpty else { return nil }
        var totalLuminance = 0.0
        for dot in dots {
            totalLuminance += 0.299 * dot.color.red + 0.587 * dot.color.green + 0.114 * dot.color.blue
        }
        let avg = totalLuminance / Double(dots.count)
        return avg < 0.50
    }

    private enum ThemeKeys: String, CodingKey {
        case type, gradientColors, opacity, texture, lightness
    }

    init(
        type: String? = nil,
        dots: [ZenThemeDot] = [],
        opacity: Double? = nil,
        texture: Double? = nil,
        lightness: Double? = nil
    ) {
        self.type = type
        self.dots = dots
        self.opacity = opacity
        self.texture = texture
        self.lightness = lightness
    }

    init(
        type: String? = nil,
        gradientColors: [String]?,
        opacity: Double? = nil,
        texture: Double? = nil,
        lightness: Double? = nil
    ) {
        self.type = type
        self.dots = (gradientColors ?? []).compactMap { hex in
            ZenColorValue.from(hex: hex).map { ZenThemeDot(color: $0) }
        }
        self.opacity = opacity
        self.texture = texture
        self.lightness = lightness
    }

    /// Tolerant decoding for all Zen Desktop and mobile variations.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: ThemeKeys.self)
        type = try? c.decodeIfPresent(String.self, forKey: .type)
        opacity = try? c.decodeIfPresent(Double.self, forKey: .opacity)
        texture = try? c.decodeIfPresent(Double.self, forKey: .texture)
        lightness = try? c.decodeIfPresent(Double.self, forKey: .lightness)

        if let typedDots = try? c.decodeIfPresent([ZenThemeDot].self, forKey: .gradientColors) {
            dots = typedDots
        } else if let hexes = try? c.decodeIfPresent([String].self, forKey: .gradientColors) {
            dots = hexes.compactMap { hex in
                ZenColorValue.from(hex: hex).map { ZenThemeDot(color: $0) }
            }
        } else {
            dots = []
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ThemeKeys.self)
        try container.encodeIfPresent(type, forKey: .type)
        try container.encode(dots, forKey: .gradientColors)
        try container.encodeIfPresent(opacity, forKey: .opacity)
        try container.encodeIfPresent(texture, forKey: .texture)
        try container.encodeIfPresent(lightness, forKey: .lightness)
    }
}

/// Any JSON value that always decodes; keeps strings, discards the rest.
enum FlexibleValue: Decodable {
    case string(String)
    case other

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let string = try? container.decode(String.self) {
            self = .string(string)
        } else {
            self = .other
        }
    }

    var stringValue: String? {
        if case .string(let string) = self { return string }
        return nil
    }
}

struct ZenSpaceRecord: Codable {
    var uuid: String
    var name: String?
    var icon: String?
    var theme: ZenSpaceTheme?
    var containerGuid: String?
    var children: [String]?

    private enum SpaceKeys: String, CodingKey {
        case uuid, name, icon, theme, containerGuid, children
    }

    init(
        uuid: String,
        name: String? = nil,
        icon: String? = nil,
        theme: ZenSpaceTheme? = nil,
        containerGuid: String? = nil,
        children: [String]? = nil
    ) {
        self.uuid = uuid
        self.name = name
        self.icon = icon
        self.theme = theme
        self.containerGuid = containerGuid
        self.children = children
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: SpaceKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        name = try? c.decodeIfPresent(String.self, forKey: .name)
        icon = try? c.decodeIfPresent(String.self, forKey: .icon)
        theme = try? c.decodeIfPresent(ZenSpaceTheme.self, forKey: .theme)
        containerGuid = try? c.decodeIfPresent(String.self, forKey: .containerGuid)
        children = try? c.decodeIfPresent([String].self, forKey: .children)
    }
}

/// Decodes a flag sent as either a JSON bool or a string ("true"/"1").
private func decodeBoolFlag<K: CodingKey>(_ c: KeyedDecodingContainer<K>, forKey key: K) -> Bool? {
    if let raw = try? c.decodeIfPresent(Bool.self, forKey: key) {
        return raw
    }
    return (try? c.decodeIfPresent(String.self, forKey: key))
        .flatMap { $0 == "true" || $0 == "1" ? true : ($0 == "false" || $0 == "0" ? false : nil) }
}

struct ZenTabRecord: Codable {
    var tabId: String
    var url: String
    var title: String?
    var icon: String?
    var containerGuid: String?
    var essential: Bool?
    /// `false` marks a normal (unpinned) tab. `true`/absent means pinned, so
    /// records from before the normal-tabs feature stay pinned.
    var pinned: Bool?
    var workspaceUuid: String?
    var folderId: String?
    var staticLabel: String?
    var hasStaticIcon: Bool?
    var defaultContainer: Bool?

    /// A record from Zen's `zen.spaces-sync.normal-tabs` projection: unpinned,
    /// non-essential, carrying a real `workspaceUuid`.
    var isNormalTab: Bool { pinned == false }

    private enum TabKeys: String, CodingKey {
        case tabId, url, title, icon, containerGuid, essential, pinned
        case workspaceUuid, folderId, staticLabel, hasStaticIcon, defaultContainer
    }

    init(
        tabId: String,
        url: String,
        title: String? = nil,
        icon: String? = nil,
        containerGuid: String? = nil,
        essential: Bool? = nil,
        pinned: Bool? = nil,
        workspaceUuid: String? = nil,
        folderId: String? = nil,
        staticLabel: String? = nil,
        hasStaticIcon: Bool? = nil,
        defaultContainer: Bool? = nil
    ) {
        self.tabId = tabId
        self.url = url
        self.title = title
        self.icon = icon
        self.containerGuid = containerGuid
        self.essential = essential
        self.pinned = pinned
        self.workspaceUuid = workspaceUuid
        self.folderId = folderId
        self.staticLabel = staticLabel
        self.hasStaticIcon = hasStaticIcon
        self.defaultContainer = defaultContainer
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: TabKeys.self)
        tabId = try c.decode(String.self, forKey: .tabId)
        url = try c.decode(String.self, forKey: .url)
        title = try? c.decodeIfPresent(String.self, forKey: .title)
        icon = try? c.decodeIfPresent(String.self, forKey: .icon)
        containerGuid = try? c.decodeIfPresent(String.self, forKey: .containerGuid)
        essential = decodeBoolFlag(c, forKey: .essential)
        pinned = decodeBoolFlag(c, forKey: .pinned)
        workspaceUuid = try? c.decodeIfPresent(String.self, forKey: .workspaceUuid)
        folderId = try? c.decodeIfPresent(String.self, forKey: .folderId)
        staticLabel = try? c.decodeIfPresent(String.self, forKey: .staticLabel)
        hasStaticIcon = try? c.decodeIfPresent(Bool.self, forKey: .hasStaticIcon)
        defaultContainer = try? c.decodeIfPresent(Bool.self, forKey: .defaultContainer)
    }
}

struct ZenFolderRecord: Codable {
    var folderId: String
    var name: String?
    var icon: String?
    var workspaceUuid: String?
    var parentFolderId: String?
    var children: [String]?

    private enum FolderKeys: String, CodingKey {
        case folderId, name, icon, workspaceUuid, parentFolderId, children
    }

    init(
        folderId: String,
        name: String? = nil,
        icon: String? = nil,
        workspaceUuid: String? = nil,
        parentFolderId: String? = nil,
        children: [String]? = nil
    ) {
        self.folderId = folderId
        self.name = name
        self.icon = icon
        self.workspaceUuid = workspaceUuid
        self.parentFolderId = parentFolderId
        self.children = children
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: FolderKeys.self)
        folderId = try c.decode(String.self, forKey: .folderId)
        name = try? c.decodeIfPresent(String.self, forKey: .name)
        icon = try? c.decodeIfPresent(String.self, forKey: .icon)
        workspaceUuid = try? c.decodeIfPresent(String.self, forKey: .workspaceUuid)
        parentFolderId = try? c.decodeIfPresent(String.self, forKey: .parentFolderId)
        children = try? c.decodeIfPresent([String].self, forKey: .children)
    }
}

/// A split-view group record: Zen puts multiple tabs side by side in one
/// group (up to `MAX_TABS = 4`). The group id appears in a space's (or
/// folder's) `children`; the member tabs themselves are NOT listed there.
struct ZenSplitRecord: Codable {
    var splitId: String
    var gridType: String?
    /// `false` marks a split of normal (unpinned) tabs (from the first
    /// member); `true`/absent means pinned.
    var pinned: Bool?
    var tabs: [String]?
    var workspaceUuid: String?
    var folderId: String?

    var isNormalSplit: Bool { pinned == false }

    private enum SplitKeys: String, CodingKey {
        case splitId, gridType, pinned, tabs, workspaceUuid, folderId
    }

    init(
        splitId: String,
        gridType: String? = nil,
        pinned: Bool? = nil,
        tabs: [String]? = nil,
        workspaceUuid: String? = nil,
        folderId: String? = nil
    ) {
        self.splitId = splitId
        self.gridType = gridType
        self.pinned = pinned
        self.tabs = tabs
        self.workspaceUuid = workspaceUuid
        self.folderId = folderId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: SplitKeys.self)
        splitId = try c.decode(String.self, forKey: .splitId)
        gridType = try? c.decodeIfPresent(String.self, forKey: .gridType)
        pinned = decodeBoolFlag(c, forKey: .pinned)
        tabs = try? c.decodeIfPresent([String].self, forKey: .tabs)
        workspaceUuid = try? c.decodeIfPresent(String.self, forKey: .workspaceUuid)
        folderId = try? c.decodeIfPresent(String.self, forKey: .folderId)
    }
}

struct ZenLayoutRecord: Codable {
    var spaces: [String]?
    /// Essentials grouped by container bucket ("default" when a tab has no
    /// container), each list in sidebar order.
    var essentials: [String: [String]]?

    private enum LayoutKeys: String, CodingKey {
        case spaces, essentials
    }

    init(spaces: [String]? = nil, essentials: [String: [String]]? = nil) {
        self.spaces = spaces
        self.essentials = essentials
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: LayoutKeys.self)
        spaces = try? c.decodeIfPresent([String].self, forKey: .spaces)
        if let strict = try? c.decodeIfPresent([String: [String]].self, forKey: .essentials) {
            essentials = strict
        } else if let flexible = try? c.decodeIfPresent([String: [FlexibleValue]].self, forKey: .essentials) {
            let mapped = flexible.mapValues { $0.compactMap(\.stringValue) }
            essentials = mapped.isEmpty ? nil : mapped
        }
    }
}

// MARK: - App-facing model

struct ZenTab: Identifiable, Hashable, Codable {
    /// Record id == zenSyncId on the desktop. Stable across syncs.
    let id: String
    let url: String
    let title: String
    /// Direct favicon URL when the source provides one (classic tabs sync).
    var iconURL: String?
    var icon: String?
    var hasStaticIcon: Bool?

    init(
        id: String,
        url: String,
        title: String,
        iconURL: String? = nil,
        icon: String? = nil,
        hasStaticIcon: Bool? = nil
    ) {
        self.id = id
        self.url = url
        self.title = title
        self.iconURL = iconURL
        self.icon = icon
        self.hasStaticIcon = hasStaticIcon
    }
}

/// A pinned folder inside a space; holds its pinned tabs and nested
/// sub-folders (from `parentFolderId` / `children`).
struct ZenFolder: Identifiable, Hashable, Codable {
    let id: String
    var name: String
    var icon: String?
    var tabs: [ZenTab]
    /// Folders nested inside this one, in sidebar order. Nil = none.
    var subfolders: [ZenFolder]?

    init(
        id: String,
        name: String,
        icon: String? = nil,
        tabs: [ZenTab] = [],
        subfolders: [ZenFolder]? = nil
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.tabs = tabs
        self.subfolders = subfolders
    }
}

/// A pinned split-view group inside a space (or folder). Members keep their
/// order; each one is shown side by side as an equal-width pane.
struct ZenSplit: Identifiable, Hashable, Codable {
    let id: String
    var gridType: String?
    var tabs: [ZenTab]
}

/// One entry of a space's pinned section.
enum ZenItem: Identifiable, Hashable, Codable {
    case tab(ZenTab)
    case folder(ZenFolder)
    case split(ZenSplit)

    var id: String {
        switch self {
        case .tab(let tab): return tab.id
        case .folder(let folder): return folder.id
        case .split(let split): return split.id
        }
    }
}

struct ZenSpace: Identifiable, Hashable, Codable {
    /// Space uuid. Stable across devices.
    let id: String
    var name: String
    /// Emoji icon as chosen in Zen, nil when the space has none.
    var icon: String?
    /// Container guid backing this space; nil for the default container.
    var containerGuid: String?
    /// Full theme metadata including dots, opacity, texture and lightness.
    var theme: ZenSpaceTheme?
    /// Theme accent colors (hex strings), empty when the space has no theme.
    var themeColors: [String] {
        theme?.gradientColors ?? []
    }
    var themeOpacity: Double? {
        theme?.opacity
    }
    /// Pinned tabs and pinned folders in sidebar order.
    var pinned: [ZenItem]
    /// Normal (unpinned) tabs synced via `zen.spaces-sync.normal-tabs`, in
    /// sidebar order. Empty when the option is off or unsupported.
    var tabs: [ZenItem]

    init(
        id: String,
        name: String,
        icon: String? = nil,
        containerGuid: String? = nil,
        theme: ZenSpaceTheme? = nil,
        pinned: [ZenItem] = [],
        tabs: [ZenItem] = []
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.containerGuid = containerGuid
        self.theme = theme
        self.pinned = pinned
        self.tabs = tabs
    }

    static let fallback = ZenSpace(id: "default", name: "Space", icon: nil, pinned: [], tabs: [])

    private enum CodingKeys: String, CodingKey {
        case id, name, icon, containerGuid, theme, themeColors, themeOpacity, pinned, tabs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        icon = try? c.decodeIfPresent(String.self, forKey: .icon)
        containerGuid = try? c.decodeIfPresent(String.self, forKey: .containerGuid)
        
        if let decodedTheme = try? c.decodeIfPresent(ZenSpaceTheme.self, forKey: .theme) {
            theme = decodedTheme
        } else if let hexes = try? c.decodeIfPresent([String].self, forKey: .themeColors), !hexes.isEmpty {
            let opacity = try? c.decodeIfPresent(Double.self, forKey: .themeOpacity)
            theme = ZenSpaceTheme(gradientColors: hexes, opacity: opacity)
        } else {
            theme = nil
        }
        pinned = (try? c.decodeIfPresent([ZenItem].self, forKey: .pinned)) ?? []
        tabs = (try? c.decodeIfPresent([ZenItem].self, forKey: .tabs)) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encodeIfPresent(icon, forKey: .icon)
        try c.encodeIfPresent(containerGuid, forKey: .containerGuid)
        try c.encodeIfPresent(theme, forKey: .theme)
        try c.encode(themeColors, forKey: .themeColors)
        try c.encodeIfPresent(themeOpacity, forKey: .themeOpacity)
        try c.encode(pinned, forKey: .pinned)
        try c.encode(tabs, forKey: .tabs)
    }
}

/// How essentials map onto spaces. Zen Desktop's
/// `zen.workspaces.separate-essentials` preference switches between
/// container-specific (a space shows only its container's bucket) and shared
/// (every space shows all essentials). `.automatic` follows the synced pref
/// when Zen sends it and otherwise infers from the wire buckets.
enum EssentialsGrouping: String, CaseIterable, Identifiable, Codable {
    case automatic
    case containerSpecific = "container-specific"
    case shared

    var id: String { rawValue }
}

/// Whether the synced Zen browser can author `pinned: false` records
/// (SPEC §7 "Normal-tabs capability (write gating)"). `enabled`/`disabled`
/// require a readable `prefs` record carrying the key (desktop registers the
/// pref only in versions that sync normal tabs); `absent` means support is
/// not proven. `disabled` still means "do not write normal tabs".
enum NormalTabsCapability: String, Codable, Equatable {
    case absent
    case disabled
    case enabled

    /// The capability as the persisted "save kind" setting can express it:
    /// both `absent` and `disabled` only allow pinned saves.
    var allowsNormalTabs: Bool { self == .enabled }
}

/// How a shared/saved tab is written to Zen: pinned (always supported) or
/// normal (needs the browser's `zen.spaces-sync.normal-tabs` opt-in).
enum SaveKind: String, Codable, CaseIterable, Equatable, Identifiable {
    case pinned
    case normal

    var id: String { rawValue }

    /// English display name for the settings row and save notices.
    var title: String {
        switch self {
        case .pinned: return String(localized: "settings.save_kind.pinned")
        case .normal: return String(localized: "settings.save_kind.normal")
        }
    }

    /// The default "save kind" the capability allows (used when the stored
    /// setting predates a capability change).
    init(capability: NormalTabsCapability) {
        self = capability == .enabled ? .normal : .pinned
    }
}

/// The outcome of `SpacesSyncService.addTab`: the kind actually written plus
/// whether an attempted normal save fell back to a pinned record.
struct AddTabOutcome: Equatable {
    let recordId: String
    let kind: SaveKind
    let fellBackToPinned: Bool

    static func pinned(recordId: String) -> AddTabOutcome {
        AddTabOutcome(recordId: recordId, kind: .pinned, fellBackToPinned: false)
    }

    static func normal(recordId: String) -> AddTabOutcome {
        AddTabOutcome(recordId: recordId, kind: .normal, fellBackToPinned: false)
    }

    static func fallback(recordId: String) -> AddTabOutcome {
        AddTabOutcome(recordId: recordId, kind: .pinned, fellBackToPinned: true)
    }
}

/// Two pages share one pinned essentials grid exactly when their effective
/// tab lists match — same-container spaces in container-specific mode, any
/// two spaces in shared mode.
func essentialsGridsMatch(_ lhs: [ZenTab], _ rhs: [ZenTab]) -> Bool {
    lhs.map(\.id) == rhs.map(\.id)
}

struct ZenSnapshot: Equatable, Codable {
    var spaces: [ZenSpace]
    /// Essential tabs per container bucket, sidebar order from the layout
    /// record. A space without a container reads the "default" bucket.
    var essentials: [String: [ZenTab]]
    /// Synced `zen.workspaces.separate-essentials` value. Zen does not sync
    /// that pref today, so this is normally nil and `.automatic` falls back
    /// to `inferContainerSpecific`.
    var separateEssentialsPref: Bool?
    /// Write-gating capability for `pinned: false` records (SPEC §7).
    /// Older caches decode as `.absent`.
    var normalTabsCapability: NormalTabsCapability
    var fetchedAt: Date

    static let empty = ZenSnapshot(spaces: [], fetchedAt: .distantPast)

    init(
        spaces: [ZenSpace],
        essentials: [String: [ZenTab]] = [:],
        separateEssentialsPref: Bool? = nil,
        normalTabsCapability: NormalTabsCapability = .absent,
        fetchedAt: Date
    ) {
        self.spaces = spaces
        self.essentials = essentials
        self.separateEssentialsPref = separateEssentialsPref
        self.normalTabsCapability = normalTabsCapability
        self.fetchedAt = fetchedAt
    }

    private enum CodingKeys: String, CodingKey {
        case spaces, essentials, separateEssentialsPref, normalTabsCapability, fetchedAt
    }

    /// Tolerant so caches written before `essentials`/`separateEssentialsPref`
    /// /`normalTabsCapability` existed still decode.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        spaces = try c.decodeIfPresent([ZenSpace].self, forKey: .spaces) ?? []
        essentials = try c.decodeIfPresent([String: [ZenTab]].self, forKey: .essentials) ?? [:]
        separateEssentialsPref = try c.decodeIfPresent(Bool.self, forKey: .separateEssentialsPref)
        normalTabsCapability = (try? c.decodeIfPresent(NormalTabsCapability.self, forKey: .normalTabsCapability)) ?? .absent
        fetchedAt = try c.decodeIfPresent(Date.self, forKey: .fetchedAt) ?? .distantPast
    }

    func space(id: String) -> ZenSpace? {
        spaces.first { $0.id == id }
    }

    /// True when the home screen has anything to show: a pinned tab (incl.
    /// pinned folders/splits), a normal tab, or an essential. When false while
    /// spaces exist, Zen's "Sync your sidebar across devices" is likely off on
    /// desktop.
    var hasSyncedTabs: Bool {
        spaces.contains { !$0.pinned.isEmpty || !$0.tabs.isEmpty }
            || essentials.values.contains { !$0.isEmpty }
    }

    // MARK: Essentials grouping

    /// Essentials to show on `space` under `grouping`.
    func essentials(for space: ZenSpace, grouping: EssentialsGrouping = .automatic) -> [ZenTab] {
        if isContainerSpecific(grouping: grouping) {
            return containerSpecificEssentials(for: space)
        }
        return sharedEssentials()
    }

    /// Whether container grouping applies under `grouping`. `.automatic` uses
    /// the synced pref when present, otherwise infers from the buckets.
    func isContainerSpecific(grouping: EssentialsGrouping) -> Bool {
        switch grouping {
        case .containerSpecific: return true
        case .shared: return false
        case .automatic:
            return separateEssentialsPref ?? Self.inferContainerSpecific(essentials)
        }
    }

    /// Separate essentials only when at least one lives in a real container
    /// bucket. All-default data (the common case when the pref is off) shows
    /// on every space.
    static func inferContainerSpecific(_ buckets: [String: [ZenTab]]) -> Bool {
        buckets.contains { key, tabs in key != "default" && !tabs.isEmpty }
    }

    /// Container-specific lookup mirrors desktop `_shouldShowTab`: a space
    /// with a container gets its own bucket; a container-less space gets the
    /// default bucket plus buckets no space uses (orphan containers), so
    /// those essentials stay reachable.
    private func containerSpecificEssentials(for space: ZenSpace) -> [ZenTab] {
        if let guid = space.containerGuid, !guid.isEmpty {
            return essentials[guid] ?? []
        }
        let usedContainers = Set(spaces.compactMap { $0.containerGuid }.filter { !$0.isEmpty })
        let orphanKeys = essentials.keys
            .filter { $0 != "default" && !usedContainers.contains($0) }
            .sorted()
        return mergedBuckets(["default"] + orphanKeys)
    }

    /// Shared grouping: every essential, default bucket first, remaining
    /// buckets in stable key order (the wire format loses cross-bucket order).
    private func sharedEssentials() -> [ZenTab] {
        let otherKeys = essentials.keys.filter { $0 != "default" }.sorted()
        return mergedBuckets(["default"] + otherKeys)
    }

    private func mergedBuckets(_ keys: [String]) -> [ZenTab] {
        var result: [ZenTab] = []
        var seen = Set<String>()
        for key in keys {
            for tab in essentials[key] ?? [] where seen.insert(tab.id).inserted {
                result.append(tab)
            }
        }
        return result
    }
}

// MARK: - Decoding

enum ZenSpacesDecoder {
    /// Turns one decrypted record payload into a typed value.
    /// Returns nil for tombstones, unknown kinds and malformed data.
    static func decode(id: String, cleartext: [String: Any]) -> DecodedRecord? {
        if (cleartext["deleted"] as? Bool) == true { return nil }
        guard let kindRaw = cleartext["kind"] as? String,
              let kind = ZenRecordKind(rawValue: kindRaw),
              let data = cleartext["data"] else { return nil }
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: data)
            switch kind {
            case .space:
                return .space(try JSONDecoder().decode(ZenSpaceRecord.self, from: jsonData))
            case .tab:
                return .tab(try JSONDecoder().decode(ZenTabRecord.self, from: jsonData))
            case .folder:
                return .folder(try JSONDecoder().decode(ZenFolderRecord.self, from: jsonData))
            case .split:
                return .split(try JSONDecoder().decode(ZenSplitRecord.self, from: jsonData))
            case .layout:
                return .layout(try JSONDecoder().decode(ZenLayoutRecord.self, from: jsonData))
            case .container:
                return nil
            }
        } catch {
            return nil
        }
    }

    enum DecodedRecord {
        case space(ZenSpaceRecord)
        case tab(ZenTabRecord)
        case folder(ZenFolderRecord)
        case split(ZenSplitRecord)
        case layout(ZenLayoutRecord)
    }
}

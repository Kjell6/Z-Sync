import SwiftUI
import UIKit

/// Where a bottom dock — the spaces screen's switcher and action bar — sits
/// relative to the screen edge.
///
/// The home indicator only occupies the bottom few points of the safe area, so
/// the dock shaves a hair off the inset instead of floating a full inset above
/// the edge. A view that ignores the safe area cannot read the inset from its
/// own `GeometryProxy`, so it is taken from the key window here. Devices
/// without an indicator fall back to 8pt.
@MainActor
enum BottomDock {
    static var padding: CGFloat {
        let inset = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.bottom ?? 0
        return max(8, inset - 4)
    }
}

extension View {
    /// Dodges the window controls on the given edges.
    ///
    /// iPadOS 26 renders the window controls ("traffic lights") over the
    /// window's top-leading corner whenever the app runs in a resizable window
    /// rather than full screen. Custom chrome — like the spaces screen's action
    /// bar — has to move out of the way itself; the system only relocates items
    /// that live in a real toolbar.
    ///
    /// `containerCornerOffset(_:sizeToFit:)` is Apple's API for exactly this: it
    /// repositions the view and, with `sizeToFit`, shrinks its proposal by the
    /// overlapping inset so following content isn't pushed off-screen.
    ///
    /// The offset is applied only while there are window controls: on iPad,
    /// in a window smaller than the screen. Full screen the corner insets still
    /// report the display's rounded corners, and iPhone Duo reports its side
    /// strip with the status bar and camera; both would push the content down
    /// for controls that are not there.
    func containerCornerOffsetCompat(_ edges: Edge.Set, sizeToFit: Bool = true) -> some View {
        modifier(WindowControlsOffset(edges: edges, sizeToFit: sizeToFit))
    }
}

private struct WindowControlsOffset: ViewModifier {
    let edges: Edge.Set
    let sizeToFit: Bool
    @State private var windowed = WindowControlsOffset.sceneIsWindowed()

    func body(content: Content) -> some View {
        Group {
            if #available(iOS 26.0, *), windowed {
                content.containerCornerOffset(edges, sizeToFit: sizeToFit)
            } else {
                content
            }
        }
        // The cue to look again: the corner insets appear and go with the
        // window controls even when this view keeps its size, as a capped
        // column does when the window narrows.
        .onGeometryChange(for: [CGFloat].self, of: Self.cue) { _ in
            windowed = Self.sceneIsWindowed()
        }
    }

    private static func cue(_ proxy: GeometryProxy) -> [CGFloat] {
        var cue = [proxy.size.width, proxy.size.height]
        if #available(iOS 26.0, *) {
            let corners = proxy.containerCornerInsets
            cue += [corners.topLeading.width, corners.topLeading.height,
                    corners.topTrailing.width, corners.topTrailing.height]
        }
        return cue
    }

    /// Whether the app runs on iPad in a window smaller than the screen, the
    /// only case with window controls over its corner.
    @MainActor
    static func sceneIsWindowed() -> Bool {
        guard UIDevice.current.userInterfaceIdiom == .pad else { return false }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else {
            return false
        }
        let window = scene.coordinateSpace.bounds.size
        let screen = scene.screen.bounds.size
        return window.width < screen.width - 1 || window.height < screen.height - 1
    }
}

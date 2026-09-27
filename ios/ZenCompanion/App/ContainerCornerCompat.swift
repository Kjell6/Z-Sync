import SwiftUI

extension View {
    /// Dodges the container's corner insets on the given edges.
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
    /// The corner insets are zero on iPhone, on iOS < 26, and whenever the
    /// window is full screen, so this is a no-op there and can be applied
    /// unconditionally.
    @ViewBuilder
    func containerCornerOffsetCompat(_ edges: Edge.Set, sizeToFit: Bool = true) -> some View {
        if #available(iOS 26.0, *) {
            self.containerCornerOffset(edges, sizeToFit: sizeToFit)
        } else {
            self
        }
    }
}

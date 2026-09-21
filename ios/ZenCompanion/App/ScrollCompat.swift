import SwiftUI

struct ScrollAnchorPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

struct ContentHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

extension View {
    /// Reports pager phase changes as (wasIdle, isIdle). iOS 18+: exact
    /// scroll-phase tracking. iOS 17: no phase API — never called, so the
    /// pager counts as always settled (previous behavior; the swipe
    /// fixes apply on iOS 18+).
    @ViewBuilder
    func onPagerSettleCompat(onPhase: @escaping (_ wasIdle: Bool, _ isIdle: Bool) -> Void) -> some View {
        if #available(iOS 18.0, *) {
            self.onScrollPhaseChange { oldPhase, newPhase, _ in
                onPhase(oldPhase == .idle, newPhase == .idle)
            }
        } else {
            self
        }
    }

    @ViewBuilder
    func onScrollGeometryChangeCompat(
        onOffsetChange: @escaping (CGFloat) -> Void,
        onContentHeightChange: @escaping (CGFloat) -> Void,
        onContainerHeightChange: @escaping (CGFloat) -> Void
    ) -> some View {
        if #available(iOS 18.0, *) {
            self
                .onScrollGeometryChange(for: CGFloat.self) { geo in
                    geo.contentOffset.y
                } action: { _, newOffset in
                    onOffsetChange(newOffset)
                }
                .onScrollGeometryChange(for: CGFloat.self) { geo in
                    geo.contentSize.height
                } action: { _, newHeight in
                    onContentHeightChange(newHeight)
                }
                .onScrollGeometryChange(for: CGFloat.self) { geo in
                    geo.containerSize.height
                } action: { _, newContainerHeight in
                    onContainerHeightChange(newContainerHeight)
                }
        } else {
            self
        }
    }
}

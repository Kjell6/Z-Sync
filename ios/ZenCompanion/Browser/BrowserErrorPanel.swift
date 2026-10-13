import Network
import SwiftUI

/// Covers the web view when a page can't load. The address bar and the bottom
/// bar stay, so a typo can be fixed in place. Offline waits for the network
/// to come back and then retries once by itself.
struct BrowserErrorPanel: View {
    let error: BrowserPageError
    /// Host of the failed address, for the title and the search button.
    let host: String
    let isLoading: Bool
    var onRetry: () -> Void
    var onSearch: () -> Void
    var onOpenExternally: () -> Void
    var onLeave: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: symbol)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(error == .insecure ? Color.red : Palette.coral(scheme))
                .frame(width: 56, height: 56)
                .liftCard(scheme, cornerRadius: 18)
                .padding(.bottom, 16)

            title
                .font(.system(size: 20, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .padding(.bottom, 6)

            message
                .font(.system(size: 15))
                .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                .padding(.bottom, 22)

            actions

            if error == .offline {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text("browser.error.offline.waiting")
                }
                .font(.system(size: 13))
                .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                .padding(.top, 14)
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: 420)
        .padding(.horizontal, 32)
        .padding(.top, 72)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.paper(scheme))
        .task(id: error) {
            guard error == .offline else { return }
            if await Self.connectionReturned() {
                onRetry()
            }
        }
    }

    private var symbol: String {
        switch error {
        case .offline: return "wifi.slash"
        case .hostNotFound: return "globe"
        case .notResponding: return "clock"
        case .insecure: return "exclamationmark.shield"
        case .crashed: return "exclamationmark.triangle"
        }
    }

    private var title: Text {
        switch error {
        case .offline: return Text("browser.error.offline.title")
        case .hostNotFound: return Text("browser.error.host_not_found.title \(host)")
        case .notResponding: return Text("browser.error.not_responding.title \(host)")
        case .insecure: return Text("browser.error.insecure.title")
        case .crashed: return Text("browser.error.crashed.title")
        }
    }

    private var message: Text {
        switch error {
        case .offline: return Text("browser.error.offline.message")
        case .hostNotFound: return Text("browser.error.host_not_found.message")
        case .notResponding: return Text("browser.error.not_responding.message")
        case .insecure: return Text("browser.error.insecure.message \(host)")
        case .crashed: return Text("browser.error.crashed.message")
        }
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 4) {
            switch error {
            case .offline:
                primaryButton("browser.error.try_again", action: onRetry)
            case .hostNotFound:
                primaryButton("browser.error.search_for \(host)", action: onSearch)
                secondaryButton("browser.error.try_again", action: onRetry)
            case .notResponding:
                primaryButton("browser.error.try_again", action: onRetry)
                secondaryButton("browser.open_in_browser", action: onOpenExternally)
            case .insecure:
                primaryButton("browser.error.go_back", action: onLeave)
            case .crashed:
                primaryButton("browser.error.reload", action: onRetry)
            }
        }
    }

    private func primaryButton(_ label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack {
                Text(label)
                    .lineLimit(1)
                    .opacity(isLoading ? 0 : 1)
                if isLoading {
                    ProgressView()
                        .tint(Palette.onCoral(scheme))
                }
            }
            .font(.system(size: 16, weight: .semibold, design: .rounded))
            .foregroundStyle(Palette.onCoral(scheme))
            .padding(.horizontal, 24)
            .frame(minHeight: 48)
            .background(Palette.coral(scheme), in: Capsule())
        }
        .buttonStyle(PressedButtonStyle())
        .disabled(isLoading)
    }

    private func secondaryButton(_ label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .lineLimit(1)
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.coral(scheme))
                .padding(.horizontal, 24)
                .frame(minHeight: 48)
                .contentShape(Capsule())
        }
        .buttonStyle(PressedButtonStyle())
    }

    /// True once the network is usable. A path that already reads as
    /// satisfied counts too: it may have come back before the panel
    /// appeared. The panel retries once per error, so a network that
    /// reads as up but still fails can't loop.
    private static func connectionReturned() async -> Bool {
        let monitor = NWPathMonitor()
        let statuses = AsyncStream<NWPath.Status> { continuation in
            monitor.pathUpdateHandler = { continuation.yield($0.status) }
            continuation.onTermination = { _ in
                monitor.pathUpdateHandler = nil
                monitor.cancel()
            }
            monitor.start(queue: DispatchQueue(label: "de.kjell.zencompanion.connectivity"))
        }
        for await status in statuses where status == .satisfied {
            return true
        }
        return false
    }
}

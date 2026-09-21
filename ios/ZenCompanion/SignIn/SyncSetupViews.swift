import SwiftUI

/// Shown when sync works but the account has zero spaces — Zen Sync is not
/// enabled on desktop yet. Actionable help instead of a generic error.
struct ZeroSpacesHelp: View {
    let reloading: Bool
    let scheme: ColorScheme
    let onSetup: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "square.on.square")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(0.35))

            Text("sync.zero.title")
                .font(.system(size: 19, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))

            Text("sync.zero.body")
                .font(.system(size: 15, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, 40)

            VStack(spacing: 10) {
                Button(action: onSetup) {
                    Label(String(localized: "sync.setup.open"), systemImage: "arrow.triangle.2.circlepath")
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .foregroundStyle(Palette.paper(scheme))
                        .background(Palette.ink(scheme), in: Capsule())
                }
                .buttonStyle(.plain)

                if reloading {
                    ProgressView()
                        .tint(Palette.coral(scheme))
                        .padding(.top, 4)
                } else {
                    Button(action: onRetry) {
                        Label(String(localized: "spaces.retry"), systemImage: "arrow.clockwise")
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                            .background(Palette.lift(scheme), in: Capsule())
                            .foregroundStyle(Palette.ink(scheme))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Compact main-screen card shown while spaces synced but nothing in them
/// did — the sidebar sync switch in Zen Browser is the usual cause.
struct SyncSetupHint: View {
    let scheme: ColorScheme
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.coral(scheme))
                        .frame(width: 34, height: 34)
                        .background(
                            Palette.coral(scheme).opacity(scheme == .dark ? 0.22 : 0.12),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                        )

                    VStack(alignment: .leading, spacing: 2) {
                        Text("sync.setup.hint.title")
                            .font(.system(size: 14, weight: .semibold, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme))
                        Text("sync.setup.hint.body")
                            .font(.system(size: 13, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 4)

                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.3))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.dismiss"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.vertical, 10)
        .background(
            Palette.lift(scheme),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Palette.ink(scheme).opacity(0.08), lineWidth: 1)
        )
    }
}

/// Step-by-step explanation of the two requirements: the same Mozilla
/// account in Zen Browser, and the sidebar sync switch that actually carries
/// workspaces, pinned tabs, and folders.
struct SyncSetupSheet: View {
    let scheme: ColorScheme
    let onRefresh: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("sync.setup.intro")
                        .font(.system(size: 15, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(alignment: .leading, spacing: 18) {
                        step(
                            number: 1,
                            title: "sync.setup.step1.title",
                            body: "sync.setup.step1.body"
                        )
                        step(
                            number: 2,
                            title: "sync.setup.step2.title",
                            body: "sync.setup.step2.body"
                        )
                        step(
                            number: 3,
                            title: "sync.setup.step3.title",
                            body: "sync.setup.step3.body"
                        )
                    }

                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "info.circle")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            .padding(.top, 1)
                        Text("sync.setup.note")
                            .font(.system(size: 13, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Button {
                        dismiss()
                        onRefresh()
                    } label: {
                        Label(String(localized: "sync.setup.refresh"), systemImage: "arrow.clockwise")
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .foregroundStyle(Palette.paper(scheme))
                            .background(Palette.ink(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                .padding(20)
            }
            .background(Palette.paper(scheme).ignoresSafeArea())
            .navigationTitle(Text("sync.setup.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done") { dismiss() }
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundStyle(Palette.coral(scheme))
                }
            }
        }
    }

    private func step(number: Int, title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.paper(scheme))
                .frame(width: 26, height: 26)
                .background(Palette.coral(scheme), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
                Text(body)
                    .font(.system(size: 14, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// One-time dismissable tip introducing the share extension, right after the
/// first sync moment. Not a screen — a callout where the user already is.
struct ShareExtensionTip: View {
    let scheme: ColorScheme
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "square.and.arrow.up")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.coral(scheme))

            VStack(alignment: .leading, spacing: 2) {
                Text("sync.tip.share.title")
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
                Text("sync.tip.share.body")
                    .font(.system(size: 13, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.dismiss"))
        }
        .padding(14)
        .background(
            Palette.paper(scheme),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Palette.ink(scheme).opacity(0.12), lineWidth: 1)
        )
        .shadow(color: .black.opacity(scheme == .dark ? 0.4 : 0.12), radius: 16, y: 6)
    }
}

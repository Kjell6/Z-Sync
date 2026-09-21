import SwiftUI
import UIKit

/// Bare favicon with rounded corners; a plain gray rounded square when no
/// icon is available. No background box, no fallback glyph.
struct Favicon: View {
    let urlString: String
    var directURL: String?
    var size: CGFloat = 26

    @State private var image: UIImage?

    init(urlString: String, directURL: String? = nil, size: CGFloat = 26) {
        self.urlString = urlString
        self.directURL = directURL
        self.size = size
        // Seed from the shared cache so a recreated pager page paints the
        // decoded icon on its first frame instead of reloading it.
        _image = State(initialValue: FaviconResolver
            .url(pageURL: urlString, directURL: directURL)
            .flatMap { FaviconLoader.shared.cached($0) })
    }

    private var resolved: URL? {
        FaviconResolver.url(pageURL: urlString, directURL: directURL)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
        let displayed = image ?? resolved.flatMap { FaviconLoader.shared.cached($0) }
        Group {
            if let displayed {
                Image(uiImage: displayed)
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fit)
                    .frame(width: size, height: size)
            } else {
                shape.fill(Color(UIColor.systemGray5))
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
        .task(id: resolved?.absoluteString ?? "") {
            guard let resolved else {
                image = nil
                return
            }
            // Cached icons are applied synchronously: no loader hop, no
            // placeholder flash when a page is rebuilt on a space switch.
            if let cached = FaviconLoader.shared.cached(resolved) {
                image = cached
                return
            }
            image = await FaviconLoader.shared.image(for: resolved)
        }
    }
}

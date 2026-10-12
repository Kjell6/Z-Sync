import SwiftUI
import UIKit

/// Bare favicon with rounded corners; a plain gray rounded square when no
/// icon is available. No background box, no fallback glyph.
struct Favicon: View {
    let urlString: String
    var directURL: String?
    var dataURL: String?
    var size: CGFloat = 26

    @State private var image: UIImage?

    init(urlString: String, directURL: String? = nil, dataURL: String? = nil, size: CGFloat = 26) {
        self.urlString = urlString
        self.directURL = directURL
        self.dataURL = dataURL
        self.size = size
        // Seed from the shared cache so a recreated pager page paints the
        // decoded icon on its first frame instead of reloading it.
        _image = State(initialValue: FaviconLoader.shared.cached(pageURL: urlString, directURL: directURL, dataURL: dataURL))
    }

    private var loadKey: String {
        FaviconResolver.cacheKey(pageURL: urlString, directURL: directURL, dataURL: dataURL) ?? ""
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
        let displayed = image ?? FaviconLoader.shared.cached(pageURL: urlString, directURL: directURL, dataURL: dataURL)
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
        .task(id: loadKey) {
            guard !loadKey.isEmpty else {
                image = nil
                return
            }
            // Cached icons are applied synchronously: no loader hop, no
            // placeholder flash when a page is rebuilt on a space switch.
            if let cached = FaviconLoader.shared.cached(pageURL: urlString, directURL: directURL, dataURL: dataURL) {
                image = cached
                return
            }
            image = await FaviconLoader.shared.image(pageURL: urlString, directURL: directURL, dataURL: dataURL)
        }
    }
}

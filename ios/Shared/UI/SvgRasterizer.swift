import UIKit
import WebKit

/// Rasterizes an SVG favicon to a `UIImage`. Many sites ship only an SVG icon,
/// and UIKit cannot decode SVG. WebKit renders it (paths, gradients, embedded
/// raster images, CSS) into a canvas, which we read back as PNG.
///
/// One renderer per call; the WebView is used purely as an SVG engine and
/// discarded. The result goes into the loader's normal in-memory cache, so this
/// runs at most once per icon per launch.
final class SvgRasterizer: NSObject, WKNavigationDelegate {
    private var continuation: CheckedContinuation<UIImage?, Never>?
    private var webView: WKWebView?
    private var size: CGFloat = 128
    private var finished = false

    static func rasterize(_ svg: String, size: CGFloat = 128) async -> UIImage? {
        await SvgRasterizer().render(svg, size: size)
    }

    private func render(_ svg: String, size: CGFloat) async -> UIImage? {
        self.size = size
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            DispatchQueue.main.async {
                guard self.continuation != nil else { return }
                let config = WKWebViewConfiguration()
                config.websiteDataStore = .nonPersistent()
                // The SVG is untrusted input: no page scripts, no persistent store.
                config.defaultWebpagePreferences.allowsContentJavaScript = false
                let web = WKWebView(frame: CGRect(x: 0, y: 0, width: size, height: size), configuration: config)
                web.isOpaque = false
                web.backgroundColor = .clear
                web.navigationDelegate = self
                self.webView = web
                let html = "<html><body style=\"margin:0\">\(Self.sanitize(svg))</body></html>"
                web.loadHTMLString(html, baseURL: nil)
                // A load that never reports finished must not hang the caller.
                DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
                    self?.complete(nil)
                }
            }
        }
    }

    /// Drop `<script>` blocks; the renderer only needs geometry and paint.
    private static func sanitize(_ svg: String) -> String {
        svg.replacingOccurrences(
            of: "<script[^>]*>[\\s\\S]*?</script>|<script[^>]*/>",
            with: "",
            options: [.regularExpression, .caseInsensitive]
        )
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let px = Int(size)
        // `evaluateJavaScript` does not await a returned Promise, so the
        // completion would receive an unresolved Promise object. `callAsyncJavaScript`
        // is the API that resolves it and hands back the drawn PNG.
        let js = """
        const svg = document.querySelector('svg');
        if (!svg) return null;
        svg.setAttribute('width', '\(px)');
        svg.setAttribute('height', '\(px)');
        const markup = new XMLSerializer().serializeToString(svg);
        const dataURL = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(markup)));
        const image = await new Promise(function (resolve, reject) {
          const img = new Image();
          img.onload = function () { resolve(img); };
          img.onerror = function () { reject(new Error('svg load failed')); };
          img.src = dataURL;
        });
        const canvas = document.createElement('canvas');
        canvas.width = \(px);
        canvas.height = \(px);
        canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
        return canvas.toDataURL('image/png');
        """
        webView.callAsyncJavaScript(
            js,
            arguments: [:],
            in: nil,
            in: .page
        ) { [weak self] result in
            var image: UIImage?
            if case .success(let value) = result,
               let dataURL = value as? String,
               let comma = dataURL.firstIndex(of: ","),
               let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...])) {
                image = UIImage(data: data)
            }
            self?.complete(image?.hasInk == true ? image : nil)
        }
    }

    private func complete(_ image: UIImage?) {
        guard !finished else { return }
        finished = true
        continuation?.resume(returning: image)
        continuation = nil
        webView?.navigationDelegate = nil
        webView = nil
    }
}

private extension UIImage {
    /// True when at least one pixel is not fully transparent. A blank render is
    /// treated as a failure so it is neither shown nor cached.
    var hasInk: Bool {
        guard let cg = cgImage else { return false }
        let w = min(cg.width, 32)
        let h = min(cg.height, 32)
        var found = false
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        pixels.withUnsafeMutableBytes { raw in
            guard let context = CGContext(
                data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
                found = true // cannot inspect: assume it has ink
                return
            }
            context.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        if found { return true }
        return stride(from: 3, to: pixels.count, by: 4).contains { pixels[$0] != 0 }
    }
}

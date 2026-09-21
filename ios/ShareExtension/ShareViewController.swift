import SwiftUI
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let root = ShareSheetView(
            itemProviders: extensionItemProviders,
            pageTitle: sharedPageTitle
        ) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        } cancel: { [weak self] in
            let error = NSError(domain: "de.kjell.zencompanion", code: NSUserCancelledError)
            self?.extensionContext?.cancelRequest(withError: error)
        }
        let host = UIHostingController(rootView: root)
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        host.didMove(toParent: self)
        preferredContentSize = CGSize(width: UIScreen.main.bounds.width, height: 560)
    }

    private var extensionItems: [NSExtensionItem] {
        (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
    }

    private var extensionItemProviders: [NSItemProvider] {
        extensionItems.flatMap { $0.attachments ?? [] }
    }

    private var sharedPageTitle: String {
        for item in extensionItems {
            if let title = item.attributedTitle?.string, !title.isEmpty { return title }
            if let title = item.attributedContentText?.string, !title.isEmpty { return title }
        }
        return ""
    }
}

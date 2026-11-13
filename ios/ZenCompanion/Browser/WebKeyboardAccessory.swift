import UIKit
import WebKit

/// The floating bar above the keyboard while a page's own text field is
/// focused: arrows to move between the page's fields, a checkmark to finish.
///
/// iOS 26 draws its own glass behind an input accessory, but a plain toolbar
/// fills that area edge to edge. Safari's bar is inset and rounded, so the
/// content here is a capsule of its own, with air around it.
final class WebKeyboardAccessory: UIView {
    private weak var webView: WKWebView?

    init(webView: WKWebView) {
        self.webView = webView
        // An input accessory is laid out from its frame, not its intrinsic
        // size: a zero frame is a zero-height bar, which is the same as none.
        super.init(frame: CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: 56))
        backgroundColor = .clear
        autoresizingMask = [.flexibleWidth]

        let bar = UIVisualEffectView(effect: Self.barEffect())
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.layer.cornerRadius = 22
        bar.layer.cornerCurve = .continuous
        bar.clipsToBounds = true
        addSubview(bar)

        let previous = button(systemName: "chevron.up", label: "browser.field_previous", action: #selector(focusPreviousField))
        let next = button(systemName: "chevron.down", label: "browser.field_next", action: #selector(focusNextField))
        let done = button(systemName: "checkmark", label: "common.done", action: #selector(finishEditing))

        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let stack = UIStackView(arrangedSubviews: [previous, next, spacer, done])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        bar.contentView.addSubview(stack)

        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            bar.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            bar.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            bar.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),

            stack.leadingAnchor.constraint(equalTo: bar.contentView.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: bar.contentView.trailingAnchor, constant: -10),
            stack.topAnchor.constraint(equalTo: bar.contentView.topAnchor),
            stack.bottomAnchor.constraint(equalTo: bar.contentView.bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: 56)
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: 56)
    }

    /// Glass on iOS 26, the closest chrome material before it.
    private static func barEffect() -> UIVisualEffect {
        if #available(iOS 26.0, *) {
            return UIGlassEffect(style: .regular)
        }
        return UIBlurEffect(style: .systemChromeMaterial)
    }

    private func button(systemName: String, label: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: systemName), for: .normal)
        button.tintColor = .label
        button.accessibilityLabel = String(localized: String.LocalizationValue(label))
        button.addTarget(self, action: action, for: .touchUpInside)
        // 34pt reads like Safari's glyphs, 44pt is what a finger needs.
        button.widthAnchor.constraint(equalToConstant: 44).isActive = true
        button.heightAnchor.constraint(equalToConstant: 44).isActive = true
        button.imageView?.contentMode = .scaleAspectFit
        button.setPreferredSymbolConfiguration(
            UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold),
            forImageIn: .normal
        )
        return button
    }

    @objc private func focusPreviousField() {
        move(by: -1)
    }

    @objc private func focusNextField() {
        move(by: 1)
    }

    @objc private func finishEditing() {
        webView?.endEditing(true)
        webView?.evaluateJavaScript(
            "document.activeElement && document.activeElement.blur()",
            completionHandler: nil
        )
    }

    /// Steps through the page's own fields. Browsers do not expose focus order
    /// to the app, so the page answers this one.
    private func move(by step: Int) {
        let script = """
        (function(step) {
          var fields = Array.prototype.slice.call(
            document.querySelectorAll('input, textarea, select, [contenteditable=""], [contenteditable="true"]')
          ).filter(function(field) {
            if (field.disabled || field.readOnly) return false;
            if (field.type === 'hidden') return false;
            var rect = field.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          });
          var index = fields.indexOf(document.activeElement);
          if (index < 0) return;
          var target = fields[index + step];
          if (!target) return;
          target.focus();
          if (target.scrollIntoView) target.scrollIntoView({ block: 'nearest' });
        })(\(step));
        """
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }
}

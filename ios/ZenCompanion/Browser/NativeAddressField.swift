import SwiftUI
import UIKit

/// The address editor, drawn by UIKit itself.
///
/// Safari's address field is a plain `UITextField` in a capsule, so the
/// native text field is what makes that state look standard: system caret and
/// selection, the system clear button while editing, the `.webSearch`
/// keyboard, dictation and the normal edit menu. Re-drawing any of that in
/// SwiftUI only approximates it.
struct NativeAddressField: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var ink: UIColor
    var caret: UIColor
    /// Owned by the screen: when this flips, the field takes or gives up
    /// first responder, so the keyboard follows the chrome.
    var isFocused: Bool
    var onSubmit: () -> Void
    var onFocusChange: (Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        field.delegate = context.coordinator
        field.borderStyle = .none
        field.backgroundColor = .clear
        field.clearButtonMode = .whileEditing
        field.returnKeyType = .go
        field.keyboardType = .webSearch
        field.textContentType = .URL
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.smartDashesType = .no
        field.smartQuotesType = .no
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        field.addTarget(
            context.coordinator,
            action: #selector(Coordinator.editingChanged(_:)),
            for: .editingChanged
        )
        apply(to: field)
        return field
    }

    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.parent = self
        apply(to: field)
        // Never fight the field's own text: writing while the user types would
        // move the caret to the end on every keystroke.
        if field.text != text {
            field.text = text
        }
        if isFocused, !field.isFirstResponder {
            field.becomeFirstResponder()
        } else if !isFocused, field.isFirstResponder {
            field.resignFirstResponder()
        }
    }

    private func apply(to field: UITextField) {
        field.textColor = ink
        field.tintColor = caret
        field.attributedPlaceholder = NSAttributedString(
            string: placeholder,
            attributes: [.foregroundColor: ink.withAlphaComponent(0.45)]
        )
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: NativeAddressField

        init(_ parent: NativeAddressField) {
            self.parent = parent
        }

        @objc func editingChanged(_ field: UITextField) {
            parent.text = field.text ?? ""
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            // Safari hands the whole address over, ready to be replaced. It has
            // to happen here: `becomeFirstResponder` resets the selection after
            // `updateUIView` set it.
            textField.selectAll(nil)
            parent.onFocusChange(true)
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            parent.onFocusChange(false)
        }

        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            parent.onSubmit()
            return false
        }

        func textFieldShouldClear(_ textField: UITextField) -> Bool {
            parent.text = ""
            return true
        }
    }
}

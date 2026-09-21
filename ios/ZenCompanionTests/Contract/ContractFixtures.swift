import Foundation

/// Loads the golden JSON fixtures from `shared/contract/fixtures/{crypto,wire,auth}/`
/// (copied into this test bundle as resources by XcodeGen, see `project.yml`).
///
/// The fixtures plus `shared/contract/SPEC.md` are the canonical
/// cross-platform sync contract (Contract-Version 1). Every load asserts the
/// declared `contract` version and that `id` matches the basename, so a
/// misplaced or renamed fixture fails loudly instead of silently testing the
/// wrong vector.
enum ContractFixtures {
    /// Every fixture basename under `shared/contract/fixtures/`. Keep this list
    /// in sync with the directory — `testEveryFixtureDeclaresContractVersion1`
    /// fails loudly when the list and the directory drift (test bundles
    /// cannot enumerate bundle resources, so the list is the source of truth).
    /// Adding a fixture means adding its name here, in exactly one place.
    static let all: [String] = [
        "auth-errno-103",
        "bso-ids-percent-encoding",
        "crypto-bso-envelope-tampered-hmac",
        "crypto-bso-envelope-valid",
        "crypto-client-state-bytes-kb-zero",
        "crypto-hkdf-rfc5869-case1",
        "crypto-sync-key-bundle-kb-zero",
        "crypto-token-material-session-token",
        "crypto-unbundle-account-keys",
        "hawk-authorization-resource",
        "hawk-payload-hash",
        "wire-deleted-string",
        "wire-folder-basic",
        "wire-folder-live-object",
        "wire-folder-missing-folderid",
        "wire-ignored-records",
        "wire-layout-basic",
        "wire-prefs-normal-tabs",
        "wire-prefs-normal-tabs-capability",
        "wire-space-basic",
        "wire-space-numeric-uuid",
        "wire-space-object-dots",
        "wire-space-rgb-dots",
        "wire-split-basic",
        "wire-split-normal-pinned-false",
        "wire-tab-normal-pinned-false",
        "wire-tab-pinned-default",
        "wire-tab-pinned-string-false",
    ]

    static func data(_ name: String) -> Data {
        let bundle = Bundle(for: BundleToken.self)
        guard let url = bundle.url(forResource: name, withExtension: "json") else {
            fatalError("Contract fixture '\(name).json' not found in test bundle at \(bundle.bundlePath)")
        }
        do {
            return try Data(contentsOf: url)
        } catch {
            fatalError("Contract fixture '\(name).json' unreadable at \(url.path): \(error)")
        }
    }

    static func json(_ name: String) -> [String: Any] {
        let raw: Any
        do {
            raw = try JSONSerialization.jsonObject(with: data(name), options: [.fragmentsAllowed])
        } catch {
            fatalError("Contract fixture '\(name).json' is not valid JSON: \(error)")
        }
        guard let obj = raw as? [String: Any] else {
            fatalError("Contract fixture '\(name).json' must be a JSON object")
        }
        guard let contract = obj["contract"] as? Int, contract == 1 else {
            fatalError("Contract fixture '\(name).json' must declare \"contract\": 1")
        }
        guard obj["id"] as? String == name else {
            fatalError("Contract fixture '\(name).json' declares id \(obj["id"] as? String ?? "nil")")
        }
        return obj
    }

    static func cases(_ name: String) -> [[String: Any]] {
        guard let cases = json(name)["cases"] as? [[String: Any]] else {
            fatalError("Contract fixture '\(name).json' must be a multi-case fixture with a \"cases\" array")
        }
        return cases
    }
}

/// Exists only so `Bundle(for:)` resolves to the test bundle.
private final class BundleToken {}

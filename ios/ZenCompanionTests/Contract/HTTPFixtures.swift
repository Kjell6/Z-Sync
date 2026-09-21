import Foundation

/// Loads the recorded HTTP exchanges from `shared/contract/http/` (copied
/// into this test bundle as resources by XcodeGen, see `project.yml`).
///
/// The recordings plus `shared/contract/SPEC.md` §7.3 are the canonical
/// cross-platform Sync storage contract (Contract-Version 1). Every load
/// asserts the declared `contract` version and that `id` matches the
/// basename, so a misplaced or renamed recording fails loudly instead of
/// silently testing the wrong exchange.
enum HTTPFixtures {
    /// Every recording basename in `shared/contract/http/`. Keep this list in
    /// sync with the directory — `testHTTPFixtureLoaderTracksDirectory` in
    /// `SyncTransportTests` fails loudly when the list and the directory drift
    /// (test bundles cannot enumerate bundle resources, so the list is the
    /// source of truth). Adding a recording means adding its name here, in
    /// exactly one place.
    static let all: [String] = [
        "http-get-404",
        "http-get-500-hawk",
        "http-get-pagination-page1",
        "http-get-pagination-page2",
        "http-get-stuck-offset",
        "http-keys-missing",
        "http-keys-present-default",
        "http-keys-present-per-collection",
        "http-post-200-success-failed",
        "http-put-200",
        "http-put-412",
    ]

    static func data(_ name: String) -> Data {
        let bundle = Bundle(for: HTTPFixtureBundleToken.self)
        guard let url = bundle.url(forResource: name, withExtension: "json") else {
            fatalError("HTTP recording '\(name).json' not found in test bundle at \(bundle.bundlePath)")
        }
        do {
            return try Data(contentsOf: url)
        } catch {
            fatalError("HTTP recording '\(name).json' unreadable at \(url.path): \(error)")
        }
    }

    static func json(_ name: String) -> [String: Any] {
        let raw: Any
        do {
            raw = try JSONSerialization.jsonObject(with: data(name), options: [.fragmentsAllowed])
        } catch {
            fatalError("HTTP recording '\(name).json' is not valid JSON: \(error)")
        }
        guard let obj = raw as? [String: Any] else {
            fatalError("HTTP recording '\(name).json' must be a JSON object")
        }
        guard let contract = obj["contract"] as? Int, contract == 1 else {
            fatalError("HTTP recording '\(name).json' must declare \"contract\": 1")
        }
        guard obj["id"] as? String == name else {
            fatalError("HTTP recording '\(name).json' declares id \(obj["id"] as? String ?? "nil")")
        }
        return obj
    }

    /// The `expect.response` object of a recording
    /// (`status`, `headers`, `body`, plus optional structural predicates).
    static func response(_ name: String) -> [String: Any] {
        guard let expect = json(name)["expect"] as? [String: Any],
              let response = expect["response"] as? [String: Any]
        else {
            fatalError("HTTP recording '\(name).json' must carry expect.response")
        }
        return response
    }
}

/// Exists only so `Bundle(for:)` resolves to the test bundle.
private final class HTTPFixtureBundleToken {}

import XCTest

@testable import ZenCompanion

@MainActor
final class SignInModelTests: XCTestCase {
    private var login: FxAWebLogin {
        FxAWebLogin(
            email: "a@b.c",
            uid: "login-uid",
            sessionToken: "login-session",
            keyFetchToken: "login-keyfetch",
            unwrapBKey: "login-unwrap"
        )
    }

    func testSuccessMapsCompletionIntoSnapshot() async {
        let completer = FakeFxALoginCompleter()
        completer.result = .success(
            FxALoginCompletion(uid: "uid-1", sessionToken: "aabb", kB: Data([0x01, 0x02]))
        )
        let model = SignInModel(completer: completer)

        let snapshot = await model.received(login: login)

        XCTAssertEqual(snapshot?.email, "a@b.c")
        XCTAssertEqual(snapshot?.uid, "uid-1")
        XCTAssertEqual(snapshot?.sessionTokenHex, "aabb")
        XCTAssertEqual(snapshot?.kBHex, "0102")
        XCTAssertEqual(model.pending?.uid, "login-uid")
        XCTAssertTrue(model.finishing)
        XCTAssertNil(model.error)
        XCTAssertNil(model.hint)
    }

    func testDoubleConfirmIsIgnoredWhileFinishing() async {
        let completer = FakeFxALoginCompleter()
        completer.result = .success(
            FxALoginCompletion(uid: "uid-1", sessionToken: "aa", kB: Data([0x01]))
        )
        completer.gate = true
        let model = SignInModel(completer: completer)

        let first = Task { await model.received(login: login) }
        while !completer.didStart { await Task.yield() }

        let second = await model.confirmPending()
        XCTAssertNil(second)
        XCTAssertEqual(completer.completionCount, 1, "a second confirm must not start another completion")

        completer.release()
        let firstSnapshot = await first.value
        XCTAssertNotNil(firstSnapshot)
        XCTAssertEqual(completer.completionCount, 1)
    }

    func testFailureResetsStateAndAllowsRetry() async {
        let completer = FakeFxALoginCompleter()
        completer.result = .failure(NSError(domain: "test", code: 1))
        let model = SignInModel(completer: completer)

        let snapshot = await model.received(login: login)

        XCTAssertNil(snapshot)
        XCTAssertFalse(model.finishing)
        XCTAssertNotNil(model.error)

        completer.result = .success(
            FxALoginCompletion(uid: "uid-2", sessionToken: "cc", kB: Data([0x03]))
        )
        let retry = await model.confirmPending()

        XCTAssertNotNil(retry)
        XCTAssertEqual(retry?.uid, "uid-2")
        XCTAssertEqual(completer.completionCount, 2)
    }
}

// MARK: - Fake

@MainActor
private final class FakeFxALoginCompleter: FxALoginCompleting {
    var result: Result<FxALoginCompletion, Error> = .failure(NSError(domain: "test", code: 0))
    private(set) var completionCount = 0
    var gate = false
    private(set) var didStart = false
    private var continuation: CheckedContinuation<Void, Never>?

    func complete(login: FxAWebLogin) async throws -> FxALoginCompletion {
        completionCount += 1
        if gate {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
                self.didStart = true
            }
        }
        return try result.get()
    }

    func release() {
        continuation?.resume()
        continuation = nil
    }
}

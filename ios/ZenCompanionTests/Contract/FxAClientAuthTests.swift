import XCTest

@testable import ZenCompanion

/// Transport-driven tests for `FxAClient.syncCredentials` (SPEC §7.1): an
/// errno 103 failure on the primary OAuth/oldsync path must surface as
/// `.totpRequired` and must not fall through to the BrowserID flow. Error
/// bodies come from `auth-errno-103.json` via `ContractFixtures` instead of
/// being re-typed here.
final class FxAClientAuthTests: XCTestCase {
    private let sessionToken = String(repeating: "01", count: 32)
    private let kB = Data(repeating: 0xAB, count: 32)

    /// `input.body` of a named case in the `auth-errno-103` fixture.
    private func errnoBody(caseId: String) throws -> Data {
        let fixtureCase = try XCTUnwrap(
            ContractFixtures.cases("auth-errno-103").first { $0["id"] as? String == caseId },
            "fixture case \(caseId) missing"
        )
        let input = try XCTUnwrap(fixtureCase["input"] as? [String: Any])
        let body = try XCTUnwrap(input["body"] as? [String: Any])
        return try JSONSerialization.data(withJSONObject: body)
    }

    private func json(_ object: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: object)
    }

    /// HTTP 401 + errno 103 from `scoped-key-data` — the OAuth token call
    /// succeeds first, so the failure lands on the primary path — must throw
    /// `.totpRequired` and stop. The fallback stub is a sentinel that is only
    /// consumed if the BrowserID flow runs.
    func testSyncCredentialsRethrowsTotpRequiredWithoutBrowserIDFallback() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [
            .init(status: 200, body: try json(["access_token": "access-token"])),
            .init(status: 401, body: try errnoBody(caseId: "errno-103-totp")),
            .init(status: 401, body: try errnoBody(caseId: "errno-103-totp")),
        ]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected .totpRequired")
        } catch {
            guard let syncError = error as? SyncError, case .totpRequired = syncError else {
                return XCTFail("expected .totpRequired, got \(error)")
            }
        }

        XCTAssertEqual(transport.requests.count, 2, "BrowserID fallback must not be attempted")
        XCTAssertEqual(transport.requests[0].url.host, "oauth.accounts.firefox.com")
        XCTAssertEqual(transport.requests[1].url.host, "api.accounts.firefox.com")
        XCTAssertEqual(transport.requests[1].url.path, "/v1/account/scoped-key-data")
    }

    /// A non-TOTP auth failure (errno 104, fixture case `errno-104-plain-auth`)
    /// still falls back to BrowserID, mirroring Android's conditional rethrow.
    func testSyncCredentialsFallsBackToBrowserIDForNonTotpFailure() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [
            .init(status: 200, body: try json(["access_token": "access-token"])),
            .init(status: 401, body: try errnoBody(caseId: "errno-104-plain-auth")),
            .init(status: 200, body: try json(["cert": "cert"])),
            .init(status: 200, body: try json([
                "uid": "uid-1",
                "api_endpoint": "https://sync.example.com/1.0/sync/1.5",
                "id": "hawk-id",
                "key": "hawk-key",
                "duration": 3600,
            ])),
        ]
        let client = FxAClient(transport: transport)

        let creds = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)

        XCTAssertEqual(creds.uid, "uid-1")
        XCTAssertEqual(transport.requests.count, 4)
        XCTAssertEqual(transport.requests[1].url.path, "/v1/account/scoped-key-data")
        XCTAssertEqual(transport.requests[2].url.path, "/v1/certificate/sign")
        XCTAssertEqual(transport.requests[3].url, FxAClient.tokenServer)
        XCTAssertTrue(
            transport.requests[3].headers["Authorization"]?.hasPrefix("BrowserID ") == true,
            transport.requests[3].headers["Authorization"] ?? "missing"
        )
    }

    /// A 401 from the auth server means the stored session token is dead
    /// (e.g. the Mozilla account password changed elsewhere). The wrapped
    /// message must keep that verdict so the app signs out instead of showing
    /// a passive "try again" line.
    func testRejectedSessionTokenFlagsSessionInvalid() async throws {
        let transport = FakeSyncTransport()
        let body = try json(["errno": 110, "message": "Invalid authentication token"])
        transport.stubs = [
            .init(status: 401, body: body),
            .init(status: 401, body: body),
            .init(status: 401, body: body),
        ]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected .auth")
        } catch {
            guard let sync = error as? SyncError, case .auth(_, let sessionInvalid) = sync else {
                return XCTFail("expected .auth, got \(error)")
            }
            XCTAssertTrue(sessionInvalid)
        }
    }

    /// A device clock that is far off makes FxA reject the Hawk timestamp
    /// (401 errno 111). Signing in again would not fix that, so the user must
    /// not be signed out.
    func testClockSkewDoesNotFlagSessionInvalid() async throws {
        let transport = FakeSyncTransport()
        let body = try json(["errno": 111, "message": "Invalid timestamp in request signature"])
        transport.stubs = [
            .init(status: 401, body: body),
            .init(status: 401, body: body),
            .init(status: 401, body: body),
        ]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected .auth")
        } catch {
            guard let sync = error as? SyncError, case .auth(_, let sessionInvalid) = sync else {
                return XCTFail("expected .auth, got \(error)")
            }
            XCTAssertFalse(sessionInvalid)
        }
    }

    /// A token-server rejection after the auth server minted credentials is
    /// not proof that the account session was revoked (it can be an expired
    /// assertion or an outage). It must not sign the user out; the auth-server
    /// errno 110 is the authoritative signal.
    func testTokenServerRejectionDoesNotFlagSessionInvalid() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [
            .init(status: 200, body: try json(["access_token": "access-token"])),
            .init(status: 200, body: try json([
                "https://identity.mozilla.com/apps/oldsync": ["keyRotationTimestamp": 0],
            ])),
            .init(status: 401, body: try json(["status": "invalid-credentials"])),
            .init(status: 200, body: try json(["cert": "cert"])),
            .init(status: 401, body: try json(["status": "invalid-credentials"])),
        ]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected .auth")
        } catch {
            guard let sync = error as? SyncError, case .auth(_, let sessionInvalid) = sync else {
                return XCTFail("expected .auth, got \(error)")
            }
            XCTAssertFalse(sessionInvalid)
        }
    }

    /// A server outage (HTTP 5xx) is temporary: the user must not be signed
    /// out for it.
    func testAuthServerOutageDoesNotFlagSessionInvalid() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [
            .init(status: 503, body: try json(["error": "Service unavailable"])),
            .init(status: 503, body: try json(["error": "Service unavailable"])),
            .init(status: 503, body: try json(["error": "Service unavailable"])),
        ]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected .auth")
        } catch {
            guard let sync = error as? SyncError, case .auth(_, let sessionInvalid) = sync else {
                return XCTFail("expected .auth, got \(error)")
            }
            XCTAssertFalse(sessionInvalid)
        }
    }

    /// A cancelled request is not a failed sign-in. It must come out as the
    /// cancellation it is, without a BrowserID attempt, so the caller can
    /// retry instead of showing "Sync sign-in failed".
    func testSyncCredentialsPassesACancelledRequestOnUnwrapped() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [.init(error: URLError(.cancelled))]
        let client = FxAClient(transport: transport)

        do {
            _ = try await client.syncCredentials(sessionToken: sessionToken, kB: kB)
            return XCTFail("expected the cancellation")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .cancelled, "got \(error)")
        }
        XCTAssertEqual(transport.requests.count, 1, "BrowserID fallback must not be attempted")
    }
}

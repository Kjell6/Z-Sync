import CoreLocation
import WebKit

/// The page's location API, answered by the browser.
///
/// WKWebView asks the system for the app's location access first and offers no
/// public way to put the browser's own question ahead of it. A first visit
/// could then raise the system dialog for a site the reader never agreed to.
/// So `navigator.geolocation` goes through this bridge: the browser asks per
/// site, and only an allowed site reaches `CLLocationManager`.
enum BrowserGeolocation {
    static let handlerName = "zenGeolocation"

    static func addScript(to controller: WKUserContentController) {
        controller.addUserScript(WKUserScript(
            source: script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false,
            in: .page
        ))
    }

    /// One reply per request. A watch asks again after each fix. A page's
    /// `timeout` runs from the answer to the permission on: the reader may
    /// take as long as they like with the two questions before it.
    static let script = #"""
    (function () {
      if (!window.isSecureContext || !window.Geolocation || !window.webkit) return;
      var handler = window.webkit.messageHandlers.zenGeolocation;
      if (!handler) return;
      var post = handler.postMessage.bind(handler);
      var WATCH_INTERVAL_MS = 3000;
      var nextWatch = 1;
      var watches = {};

      function failure(code, message) {
        return {
          code: code,
          message: message || '',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3
        };
      }

      function deliver(reply, success, error) {
        if (reply && reply.coords) {
          if (typeof success === 'function') success({ coords: reply.coords, timestamp: reply.timestamp });
          return true;
        }
        if (typeof error === 'function') {
          error(failure(reply ? reply.code : 2, reply ? reply.message : ''));
        }
        return false;
      }

      function request(options) {
        return post({ op: 'fix', highAccuracy: !!(options && options.enableHighAccuracy) });
      }

      function permission() {
        return post({ op: 'permission' });
      }

      Geolocation.prototype.getCurrentPosition = function (success, error, options) {
        permission().then(function (allowed) {
          if (!allowed || !allowed.granted) {
            deliver(allowed, success, error);
            return;
          }
          var settled = false;
          var timeout = options && options.timeout;
          var timer = typeof timeout === 'number' && isFinite(timeout) && timeout >= 0
            ? setTimeout(function () {
                if (settled) return;
                settled = true;
                deliver({ code: 3 }, success, error);
              }, timeout)
            : null;
          request(options).then(function (reply) {
            if (settled) return;
            settled = true;
            if (timer !== null) clearTimeout(timer);
            deliver(reply, success, error);
          }, function () {
            if (settled) return;
            settled = true;
            if (timer !== null) clearTimeout(timer);
            deliver({ code: 2 }, success, error);
          });
        }, function () {
          deliver({ code: 2 }, success, error);
        });
      };

      Geolocation.prototype.watchPosition = function (success, error, options) {
        var id = nextWatch++;
        watches[id] = true;
        function tick() {
          if (!watches[id]) return;
          request(options).then(function (reply) {
            if (!watches[id]) return;
            if (deliver(reply, success, error)) setTimeout(tick, WATCH_INTERVAL_MS);
            else delete watches[id];
          }, function () {
            if (!watches[id]) return;
            delete watches[id];
            deliver({ code: 2 }, success, error);
          });
        }
        permission().then(function (allowed) {
          if (allowed && allowed.granted) {
            tick();
          } else if (watches[id]) {
            delete watches[id];
            deliver(allowed, success, error);
          }
        }, function () {
          if (!watches[id]) return;
          delete watches[id];
          deliver({ code: 2 }, success, error);
        });
        return id;
      };

      Geolocation.prototype.clearWatch = function (id) {
        delete watches[id];
      };
    })();
    """#

    /// Only a secure page gets this far in the script; the origin is checked
    /// here as well, because a page can post to the handler by itself.
    static func isSecureOrigin(protocol scheme: String, host: String) -> Bool {
        scheme == "https" || host == "localhost" || host == "127.0.0.1"
    }

    static func reply(for location: CLLocation) -> [String: Any] {
        func optional(_ value: Double, valid: Bool) -> Any {
            valid ? value : NSNull()
        }
        return [
            "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
            "coords": [
                "latitude": location.coordinate.latitude,
                "longitude": location.coordinate.longitude,
                "accuracy": location.horizontalAccuracy,
                "altitude": optional(location.altitude, valid: location.verticalAccuracy >= 0),
                "altitudeAccuracy": optional(location.verticalAccuracy, valid: location.verticalAccuracy >= 0),
                "heading": optional(location.course, valid: location.course >= 0),
                "speed": optional(location.speed, valid: location.speed >= 0),
            ] as [String: Any],
        ]
    }

    static func errorReply(code: Int) -> [String: Any] {
        ["code": code, "message": ""]
    }

    /// Weak to the model, which owns the web view holding this handler.
    final class Handler: NSObject, WKScriptMessageHandlerWithReply {
        private weak var model: MiniBrowserModel?
        /// Replies waiting on the question that is open for a site. A page
        /// that asks again before the reader answered - a fix and a watch at
        /// once, or a loop - shares that answer instead of queueing the same
        /// dialog over and over.
        private var waitingForAnswer: [String: [@MainActor @Sendable (Any?, String?) -> Void]] = [:]

        init(model: MiniBrowserModel) {
            self.model = model
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage,
            replyHandler: @escaping @MainActor @Sendable (Any?, String?) -> Void
        ) {
            let origin = message.frameInfo.securityOrigin
            guard let model, BrowserGeolocation.isSecureOrigin(protocol: origin.protocol, host: origin.host) else {
                replyHandler(BrowserGeolocation.errorReply(code: 1), nil)
                return
            }
            let body = message.body as? [String: Any]
            guard body?["op"] as? String == "fix" else {
                let site = origin.host.lowercased()
                if waitingForAnswer[site] != nil {
                    waitingForAnswer[site]?.append(replyHandler)
                    return
                }
                waitingForAnswer[site] = [replyHandler]
                model.requestPermission([.location], host: origin.host) { [weak self] granted in
                    let replies = self?.waitingForAnswer.removeValue(forKey: site) ?? [replyHandler]
                    guard granted else {
                        replies.forEach { $0(BrowserGeolocation.errorReply(code: 1), nil) }
                        return
                    }
                    BrowserLocation.shared.authorize { allowed in
                        let reply: [String: Any] = allowed ? ["granted": true] : BrowserGeolocation.errorReply(code: 1)
                        replies.forEach { $0(reply, nil) }
                    }
                }
                return
            }
            // A page can post here by itself, so the answer is read again.
            guard model.sitePermissions.decision(for: [.location], host: origin.host) == .grant else {
                replyHandler(BrowserGeolocation.errorReply(code: 1), nil)
                return
            }
            let highAccuracy = body?["highAccuracy"] as? Bool ?? false
            BrowserLocation.shared.fix(highAccuracy: highAccuracy) { result in
                switch result {
                case .success(let location):
                    replyHandler(BrowserGeolocation.reply(for: location), nil)
                case .failure(let failure):
                    replyHandler(BrowserGeolocation.errorReply(code: failure == .denied ? 1 : 2), nil)
                }
            }
        }
    }
}

/// One position at a time for the pages that are allowed one. The system's
/// question comes from here, so it can only follow the browser's.
@MainActor
final class BrowserLocation: NSObject, CLLocationManagerDelegate {
    static let shared = BrowserLocation()

    enum Failure: Error {
        case denied
        case unavailable
    }

    private let manager = CLLocationManager()
    private var waiting: [(Result<CLLocation, Failure>) -> Void] = []
    private var authorizing: [(Bool) -> Void] = []

    private override init() {
        super.init()
        manager.delegate = self
    }

    /// The app as a whole was refused, so asking again would show nothing.
    var isOffForApp: Bool {
        switch manager.authorizationStatus {
        case .denied, .restricted: true
        default: false
        }
    }

    /// Raises the system's question if it has not been answered yet.
    func authorize(_ completion: @escaping (Bool) -> Void) {
        switch manager.authorizationStatus {
        case .notDetermined:
            authorizing.append(completion)
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            completion(true)
        default:
            completion(false)
        }
    }

    func fix(highAccuracy: Bool, _ completion: @escaping (Result<CLLocation, Failure>) -> Void) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            waiting.append(completion)
            manager.desiredAccuracy = highAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
            manager.requestLocation()
        default:
            completion(.failure(.denied))
        }
    }

    private func finish(_ result: Result<CLLocation, Failure>) {
        let answers = waiting
        waiting = []
        answers.forEach { $0(result) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated {
            let status = manager.authorizationStatus
            guard status != .notDetermined else { return }
            let answers = authorizing
            authorizing = []
            let allowed = status == .authorizedAlways || status == .authorizedWhenInUse
            answers.forEach { $0(allowed) }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated {
            guard let location = locations.last else { return }
            finish(.success(location))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated {
            finish(.failure((error as? CLError)?.code == .denied ? .denied : .unavailable))
        }
    }
}

// Entry point for the cookie banner script both apps inject into web pages.
// Built by build-autoconsent.sh into one file per platform. Runs in the page's
// own world, so autoconsent's eval rules work without a background script.
import AutoConsent, { filterCompactRules } from '@duckduckgo/autoconsent';
import compactRules from '@duckduckgo/autoconsent/rules/compact-rules.json';

if (!window.__zsyncAutoconsent) {
    Object.defineProperty(window, '__zsyncAutoconsent', { value: true });
    const consent = new AutoConsent(async () => {}, null, null);
    consent.initialize(
        {
            enabled: true,
            autoAction: 'optOut',
            isMainWorld: true,
            enableHeuristicDetection: true,
            heuristicMode: 'tier2',
            logs: { lifecycle: false, rulesteps: false, detectionsteps: false, evals: false, errors: false, messages: false, waits: false },
        },
        {
            autoconsent: [],
            compact: filterCompactRules(compactRules, {
                url: window.location.href,
                mainFrame: window.top === window,
            }),
        },
    );
}

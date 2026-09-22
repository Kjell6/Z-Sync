// Feature switcher: an accessible tablist.
//
// Two jobs: pick the active feature by click or arrow keys, and make sure at
// most one clip is ever loading. Clips carry preload="none", so nothing is
// fetched until this section is actually on screen; the IntersectionObserver
// below starts the active clip when the media area scrolls into view and
// pauses it again when it leaves. Under prefers-reduced-motion nothing is
// played at all, and the poster frame stays.
(function () {
  var list = document.querySelector('.feature-list');
  if (!list) return;

  var tabs = Array.prototype.slice.call(list.querySelectorAll('.feature'));
  var panels = tabs.map(function (tab) {
    return document.getElementById(tab.getAttribute('aria-controls'));
  });

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var mediaArea = document.querySelector('.feature-media');

  // Two independent reasons to stay paused. Keeping them separate means a
  // return to a visible browser tab can resume, which a single flag could not.
  var sectionOnScreen = false;
  var pageVisible = true;

  function canPlay() {
    return sectionOnScreen && pageVisible && !reduceMotion;
  }

  function videosIn(panel) {
    if (!panel) return [];
    return Array.prototype.slice.call(panel.querySelectorAll('video'));
  }

  // Bring every clip in line with the current state: only the active panel's
  // clip may play, and only while it is worth watching.
  function syncPlayback() {
    var allowed = canPlay();
    tabs.forEach(function (tab, i) {
      var active = tab.classList.contains('is-active');
      videosIn(panels[i]).forEach(function (video) {
        if (active && allowed) {
          var played = video.play();
          if (played && played.catch) played.catch(function () {});
        } else {
          video.pause();
        }
      });
    });
  }

  function activate(index, focusTab) {
    tabs.forEach(function (tab, i) {
      var active = i === index;
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
      tab.setAttribute('tabindex', active ? '0' : '-1');

      var panel = panels[i];
      if (!panel) return;
      panel.classList.toggle('is-active', active);
      if (active) {
        panel.removeAttribute('hidden');
        // Restart the clip that is coming into view.
        videosIn(panel).forEach(function (video) { video.currentTime = 0; });
      } else {
        panel.setAttribute('hidden', '');
      }
    });

    syncPlayback();
    if (focusTab) tabs[index].focus();
  }

  tabs.forEach(function (tab, i) {
    tab.addEventListener('click', function () { activate(i); });
  });

  list.addEventListener('keydown', function (event) {
    var current = tabs.indexOf(document.activeElement);
    if (current === -1) return;

    var next = null;
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
      next = (current + 1) % tabs.length;
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
      next = (current - 1 + tabs.length) % tabs.length;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = tabs.length - 1;
    }

    if (next === null) return;
    event.preventDefault();
    activate(next, true);
  });

  // Only load and play video bytes while the section is worth watching.
  // Without IntersectionObserver support we stay paused rather than guess.
  if (mediaArea && 'IntersectionObserver' in window) {
    new IntersectionObserver(function (entries) {
      sectionOnScreen = entries[0].isIntersecting;
      syncPlayback();
    }, { threshold: 0.25 }).observe(mediaArea);
  }

  // A clip looping in a background tab costs battery for nothing.
  document.addEventListener('visibilitychange', function () {
    pageVisible = !document.hidden;
    syncPlayback();
  });

  syncPlayback();
})();

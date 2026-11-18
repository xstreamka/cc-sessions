(function () {
  'use strict';
  var items = document.getElementById('items');
  var q = document.getElementById('q');
  var fThinking = document.getElementById('f-thinking');
  var fTools = document.getElementById('f-tools');
  var fPrompts = document.getElementById('f-only-prompts');

  function nearBottom() {
    return window.innerHeight + window.scrollY >= document.body.scrollHeight - 60;
  }

  // ---------- клики по файлам → IDE ----------
  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-file]');
    if (!el) return;
    e.preventDefault();
    e.stopPropagation();
    var payload = JSON.stringify({ file: el.getAttribute('data-file'), line: el.getAttribute('data-line') || '' });
    if (window.ccBridge) window.ccBridge(payload);
  }, true);

  // ---------- фильтры и поиск ----------
  function clearMarks(root) {
    var marks = root.querySelectorAll('mark');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      m.replaceWith(document.createTextNode(m.textContent));
    }
    root.normalize();
  }

  function highlight(node, needle) {
    if (!needle) return;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null);
    var texts = [];
    while (walker.nextNode()) texts.push(walker.currentNode);
    texts.forEach(function (t) {
      var s = t.nodeValue, low = s.toLowerCase(), idx = low.indexOf(needle);
      if (idx < 0) return;
      var frag = document.createDocumentFragment(), pos = 0;
      while (idx >= 0) {
        frag.appendChild(document.createTextNode(s.slice(pos, idx)));
        var m = document.createElement('mark');
        m.textContent = s.slice(idx, idx + needle.length);
        frag.appendChild(m);
        pos = idx + needle.length;
        idx = low.indexOf(needle, pos);
      }
      frag.appendChild(document.createTextNode(s.slice(pos)));
      t.parentNode.replaceChild(frag, t);
    });
  }

  function apply() {
    var needle = (q.value || '').trim().toLowerCase();
    clearMarks(items);
    var shown = 0;
    var children = items.children;
    for (var i = 0; i < children.length; i++) {
      var el = children[i];
      var hide = false;
      if (el.classList.contains('thinking') && !fThinking.checked) hide = true;
      if (el.classList.contains('tool') && !fTools.checked) hide = true;
      if (fPrompts.checked && !el.classList.contains('user') && !el.classList.contains('cmd') && !el.classList.contains('day')) hide = true;
      if (!hide && needle && !el.classList.contains('day')) {
        hide = el.textContent.toLowerCase().indexOf(needle) < 0;
        if (!hide) {
          if (el.tagName === 'DETAILS') el.open = true;
          highlight(el, needle);
        }
      }
      el.classList.toggle('hidden-by-filter', hide);
      if (!hide && !el.classList.contains('day')) shown++;
    }
    document.getElementById('empty-search').hidden = !(needle && shown === 0);
  }

  var timer = null;
  q.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(apply, 150); });
  q.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      var first = items.querySelector('mark');
      if (first) first.scrollIntoView({ block: 'center' });
    }
    if (e.key === 'Escape') { q.value = ''; apply(); }
  });
  [fThinking, fTools, fPrompts].forEach(function (c) { c.addEventListener('change', apply); });

  document.getElementById('b-expand').onclick = function () {
    items.querySelectorAll('details').forEach(function (d) { d.open = true; });
  };
  document.getElementById('b-collapse').onclick = function () {
    items.querySelectorAll('details').forEach(function (d) { d.open = false; });
  };
  document.getElementById('b-top').onclick = function () { window.scrollTo(0, 0); };
  document.getElementById('b-bottom').onclick = function () { window.scrollTo(0, document.body.scrollHeight); };

  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); q.focus(); q.select(); }
  });

  // ---------- инкрементальное обновление из IDE ----------
  window.ccUpdate = function (headerHtml, itemsHtml) {
    var stick = nearBottom();
    var open = {};
    items.querySelectorAll('details[id]').forEach(function (d) { open[d.id] = d.open; });
    var y = window.scrollY;
    document.getElementById('hdr').innerHTML = headerHtml;
    items.innerHTML = itemsHtml;
    items.querySelectorAll('details[id]').forEach(function (d) {
      if (Object.prototype.hasOwnProperty.call(open, d.id)) d.open = open[d.id];
    });
    apply();
    if (stick) window.scrollTo(0, document.body.scrollHeight); else window.scrollTo(0, y);
  };

  window.ccHeader = function (headerHtml) {
    document.getElementById('hdr').innerHTML = headerHtml;
  };

  window.ccScrollTo = function (id) {
    var el = document.getElementById(id);
    if (!el) return;
    if (el.tagName === 'DETAILS') el.open = true;
    el.scrollIntoView({ block: 'center' });
    el.classList.add('flash');
    setTimeout(function () { el.classList.remove('flash'); }, 1300);
  };

  window.ccSetQuery = function (s) { q.value = s; apply(); var m = items.querySelector('mark'); if (m) m.scrollIntoView({ block: 'center' }); };

  apply();
  window.scrollTo(0, document.body.scrollHeight);
})();

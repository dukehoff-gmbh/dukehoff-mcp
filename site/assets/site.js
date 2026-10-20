/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
// Install tabs: without script all panels show, one after another.
document.querySelectorAll('[data-tabs]').forEach(function (group) {
  var buttons = group.querySelectorAll('[role="tab"]');
  var panels = document.querySelectorAll('[data-panel]');
  function select(id) {
    buttons.forEach(function (b) { b.setAttribute('aria-selected', b.dataset.tab === id ? 'true' : 'false'); });
    panels.forEach(function (p) { p.hidden = p.dataset.panel !== id; });
  }
  buttons.forEach(function (b) { b.addEventListener('click', function () { select(b.dataset.tab); }); });
  group.hidden = false;
  select(buttons[0].dataset.tab);
});

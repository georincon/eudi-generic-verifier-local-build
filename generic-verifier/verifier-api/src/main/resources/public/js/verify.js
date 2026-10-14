(function () {
  'use strict';

  var base = document.body.getAttribute('data-base') || '';
  var api = base + '/api/academic/requests';
  var POLL_MS = 2000;

  var el = function (id) { return document.getElementById(id); };
  var steps = { start: el('step-start'), wait: el('step-wait'), result: el('step-result') };
  var transactionId = null;
  var pollTimer = null;
  var countdownTimer = null;
  var deadline = 0;

  function show(step) {
    Object.keys(steps).forEach(function (k) { steps[k].classList.toggle('cv-hidden', k !== step); });
  }

  function stop() {
    clearTimeout(pollTimer);
    clearInterval(countdownTimer);
    pollTimer = null;
    countdownTimer = null;
  }

  function reset() {
    stop();
    transactionId = null;
    el('start-error').classList.add('cv-hidden');
    el('btn-start').disabled = false;
    show('start');
  }

  function startCountdown(seconds) {
    deadline = Date.now() + seconds * 1000;
    var tick = function () {
      var left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
      el('countdown').textContent = String(Math.floor(left / 60)).padStart(2, '0') + ':' + String(left % 60).padStart(2, '0');
      if (left === 0) { expire(); }
    };
    tick();
    countdownTimer = setInterval(tick, 1000);
  }

  function expire() {
    stop();
    showResult({ verdict: 'EXPIRED', headline: 'La solicitud venció. Genere un nuevo código QR.', attributes: [] });
  }

  function showResult(r) {
    stop();
    var box = el('result-box');
    box.className = 'cv-result ' + (r.verdict === 'TITLE_ACCREDITED' ? 'accredited' : r.verdict === 'VALID_BUT_NOT_GRADUATED' || r.verdict === 'INCOMPLETE' ? 'partial' : 'failed');
    el('result-headline').textContent = r.headline;
    var dl = el('result-attrs');
    dl.textContent = '';
    (r.attributes || []).forEach(function (a) {
      var dt = document.createElement('dt'); dt.textContent = a.label;
      var dd = document.createElement('dd'); dd.textContent = a.value;
      dl.appendChild(dt); dl.appendChild(dd);
    });
    show('result');
  }

  function poll() {
    fetch(api + '/' + encodeURIComponent(transactionId) + '/status', { headers: { 'Accept': 'application/json' } })
      .then(function (res) { if (!res.ok) { throw new Error('status ' + res.status); } return res.json(); })
      .then(function (s) {
        if (s.status === 'WALLET_CONNECTED') { el('status-text').textContent = 'Billetera conectada. Esperando su autorización…'; }
        if (s.status === 'RECEIVED') { return loadResult(); }
        if (s.status === 'EXPIRED') { return expire(); }
        pollTimer = setTimeout(poll, POLL_MS);
      })
      .catch(function () { pollTimer = setTimeout(poll, POLL_MS * 2); });
  }

  function loadResult() {
    return fetch(api + '/' + encodeURIComponent(transactionId) + '/result', { headers: { 'Accept': 'application/json' } })
      .then(function (res) { if (!res.ok) { throw new Error('result ' + res.status); } return res.json(); })
      .then(showResult)
      .catch(function () { showResult({ verdict: 'ERROR', headline: 'No se pudo leer el resultado de la verificación.', attributes: [] }); });
  }

  el('btn-start').addEventListener('click', function () {
    var btn = el('btn-start');
    btn.disabled = true;
    fetch(api, { method: 'POST', headers: { 'X-Requested-With': 'verifier-ui', 'Accept': 'application/json' } })
      .then(function (res) { if (!res.ok) { throw new Error('create ' + res.status); } return res.json(); })
      .then(function (created) {
        transactionId = created.transactionId;
        el('qr').src = 'data:image/png;base64,' + created.qrPngBase64;
        el('same-device').setAttribute('href', created.authorizationRequestUri);
        el('status-text').textContent = 'Esperando a la billetera…';
        show('wait');
        startCountdown(created.expiresInSeconds);
        poll();
      })
      .catch(function () {
        btn.disabled = false;
        var err = el('start-error');
        err.textContent = 'No se pudo generar la solicitud. Intente de nuevo en unos instantes.';
        err.classList.remove('cv-hidden');
      });
  });

  el('btn-cancel').addEventListener('click', reset);
  el('btn-again').addEventListener('click', reset);
})();

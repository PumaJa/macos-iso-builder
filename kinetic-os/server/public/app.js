/* Kinetic OS dashboard: vanilla JS, no build step. Read-only monitoring; no motor control. */
(function () {
  'use strict';
  var API = '/api/v1';
  var ONLINE_MS = 60000;
  var $ = function (id) { return document.getElementById(id); };
  var S = { token: null, user: null, vehicles: [], vsel: null, live: {}, geofences: [], groups: [], gsel: null, ws: null, wsTry: 0, markers: {}, gmarkers: {}, regMode: false };

  function store(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) { /* storage blocked */ } }
  function load(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

  // ---- tiny DOM helper (always textContent: no HTML injection) ----
  function h(tag, attrs) {
    var el = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (k === 'class') el.className = attrs[k]; else if (k.indexOf('on') === 0) el.addEventListener(k.slice(2), attrs[k]); else el.setAttribute(k, attrs[k]);
    });
    for (var i = 2; i < arguments.length; i++) { var c = arguments[i]; if (c == null) continue; el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
    return el;
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
  function fmtTime(ms) { return ms ? new Date(ms).toLocaleString() : 'never'; }
  function fmt(v, d, unit) { return v == null || isNaN(v) ? '--' : Number(v).toFixed(d) + (unit || ''); }
  function toast(msg) { var t = $('toast'); t.textContent = msg; t.classList.remove('hidden'); clearTimeout(toast._t); toast._t = setTimeout(function () { t.classList.add('hidden'); }, 5000); }

  function api(path, opts) {
    opts = opts || {};
    var headers = { 'Content-Type': 'application/json' };
    if (S.token) headers.Authorization = 'Bearer ' + S.token;
    return fetch(API + path, { method: opts.method || 'GET', headers: headers, body: opts.body ? JSON.stringify(opts.body) : undefined }).then(function (r) {
      if (r.status === 204) return null;
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (r.status === 401 && S.token && path !== '/auth/login') { logout(); }
        if (!r.ok) { var e = new Error(j.message || ('HTTP ' + r.status)); e.status = r.status; throw e; }
        return j;
      });
    });
  }

  // ---- auth ----
  function showAuth() { $('auth').classList.remove('hidden'); $('app').classList.add('hidden'); }
  function setRegMode(on) {
    S.regMode = on; $('dn-row').classList.toggle('hidden', !on);
    $('auth-submit').textContent = on ? 'Register' : 'Log in';
    $('auth-toggle').textContent = on ? 'Have an account? Log in' : 'No account? Register';
  }
  $('auth-toggle').addEventListener('click', function () { setRegMode(!S.regMode); });
  $('auth-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var f = ev.target; $('auth-error').textContent = '';
    var body = { username: f.username.value.trim(), password: f.password.value };
    if (S.regMode) body.displayName = f.displayName.value.trim() || body.username;
    api(S.regMode ? '/auth/register' : '/auth/login', { method: 'POST', body: body }).then(function (r) {
      S.token = r.token; S.user = r.user; store('kinetic_token', r.token); f.password.value = ''; start();
    }).catch(function (e) { $('auth-error').textContent = e.message; });
  });
  function logout() {
    S.token = null; S.user = null; store('kinetic_token', null);
    if (S.ws) { S.ws.onclose = null; S.ws.close(); S.ws = null; }
    S.live = {}; S.vehicles = []; S.vsel = null; showAuth();
  }
  $('logout').addEventListener('click', logout);

  // ---- tabs ----
  var maps = {};
  function mkMap(id) {
    var m = L.map(id).setView([48.14, 11.58], 4);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' }).addTo(m);
    return m;
  }
  function getMap(id) { if (!maps[id]) maps[id] = mkMap(id); return maps[id]; }
  document.querySelectorAll('#tabs button').forEach(function (b) {
    b.addEventListener('click', function () { showTab(b.getAttribute('data-tab')); });
  });
  function showTab(name) {
    document.querySelectorAll('#tabs button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === name); });
    document.querySelectorAll('.tab').forEach(function (t) { t.classList.add('hidden'); });
    $('tab-' + name).classList.remove('hidden');
    var mid = { vehicles: 'map', rides: 'ride-map', groups: 'group-map' }[name];
    if (mid) setTimeout(function () { getMap(mid).invalidateSize(); }, 50);
    if (name === 'alerts') loadAlerts();
    if (name === 'rides') loadRides();
    if (name === 'groups') loadGroups();
  }

  // ---- vehicles ----
  function liveOnline(v) {
    var l = S.live[v]; if (!l) return false;
    return l.serverOnline && l.state.vescConnected && (Date.now() - l.localAt) < ONLINE_MS;
  }
  function setLive(vehicleId, state, receivedAt, online) {
    S.live[vehicleId] = { state: state, receivedAt: receivedAt, serverOnline: online, localAt: Date.now() };
  }
  function loadVehicles() {
    return api('/vehicles').then(function (vs) {
      S.vehicles = vs;
      renderVehicleList();
      $('no-vehicles').classList.toggle('hidden', vs.length > 0);
      vs.forEach(function (v) {
        wsSend({ type: 'subscribe_vehicle', vehicleId: v.id });
        api('/telematics/' + v.id + '/state').then(function (r) { setLive(v.id, r.state, r.receivedAt, r.online); renderVehicleList(); if (S.vsel === v.id) renderLive(); }).catch(function () {});
      });
      if (!S.vsel && vs.length) selectVehicle(vs[0].id);
    });
  }
  function renderVehicleList() {
    var ul = $('vehicle-list'); clear(ul);
    S.vehicles.forEach(function (v) {
      var on = liveOnline(v.id), l = S.live[v.id];
      var li = h('li', { class: 'click' + (S.vsel === v.id ? ' sel' : ''), onclick: function () { selectVehicle(v.id); } },
        h('span', null, v.name + (v.make ? ' (' + v.make + ')' : '')),
        h('span', null, l && l.state.source === 'simulated' ? h('span', { class: 'badge sim' }, 'SIM ') : null, h('span', { class: 'badge ' + (on ? 'on' : 'off') }, on ? 'ONLINE' : 'OFFLINE')));
      ul.appendChild(li);
    });
  }
  function selectVehicle(id) {
    S.vsel = id; renderVehicleList();
    var v = S.vehicles.filter(function (x) { return x.id === id; })[0];
    $('vehicle-name').textContent = v ? v.name : 'Vehicle';
    var m = getMap('map');
    if (S.trail) { m.removeLayer(S.trail); S.trail = null; }
    if (S.marker) { m.removeLayer(S.marker); S.marker = null; }
    api('/telematics/' + id + '/history?limit=300').then(function (hs) {
      if (S.vsel !== id) return;
      var pts = hs.filter(function (s) { return s.lat != null; }).map(function (s) { return [s.lat, s.lon]; });
      if (pts.length) S.trail = L.polyline(pts, { color: '#00e0ff', weight: 3, opacity: 0.6 }).addTo(m);
    }).catch(function () {});
    loadGeofences();
    renderLive(true);
  }
  function stat(k, v, cls) { return h('div', { class: 'stat ' + (cls || '') }, h('div', { class: 'v' }, v), h('div', { class: 'k' }, k)); }
  function renderLive(fit) {
    var id = S.vsel, l = id && S.live[id], banner = $('banner'), st = l && l.state;
    var sBox = $('stats'); clear(sBox);
    $('sim-badge').classList.toggle('hidden', !(st && st.source === 'simulated'));
    var lb = $('live-badge');
    if (!id) { banner.classList.add('hidden'); return; }
    if (!st) {
      banner.className = 'banner offline'; banner.textContent = 'OFFLINE – no telemetry received yet for this vehicle.';
      lb.classList.add('hidden'); $('lastseen').textContent = ''; return;
    }
    var on = liveOnline(id);
    lb.className = 'badge ' + (on ? 'on' : 'off'); lb.textContent = on ? 'ONLINE' : 'OFFLINE';
    if (!on) { banner.className = 'banner offline'; banner.textContent = 'OFFLINE – last known state from ' + fmtTime(l.receivedAt) + (st.source === 'simulated' ? ' (SIMULATION)' : ''); }
    else if (st.source === 'simulated') { banner.className = 'banner sim'; banner.textContent = 'SIMULATION – this data is simulated, not from a real vehicle.'; }
    else banner.className = 'banner hidden';
    var soc = st.socPct;
    sBox.appendChild(stat('Speed', fmt(st.speedKmh, 1, ' km/h')));
    sBox.appendChild(stat('Battery', fmt(soc, 0, ' %'), soc != null && soc < 20 ? 'bad' : ''));
    sBox.appendChild(stat('Voltage', fmt(st.voltage, 1, ' V')));
    sBox.appendChild(stat('Power', fmt(st.powerW, 0, ' W')));
    sBox.appendChild(stat('MOSFET temp', fmt(st.tempMosC, 0, ' °C'), st.tempMosC > 80 ? 'warn' : ''));
    sBox.appendChild(stat('Motor temp', fmt(st.tempMotorC, 0, ' °C'), st.tempMotorC > 90 ? 'warn' : ''));
    sBox.appendChild(stat('Odometer', fmt(st.odometerKm, 1, ' km')));
    sBox.appendChild(stat('Fault', st.faultCode ? String(st.faultCode) : 'none', st.faultCode ? 'bad' : ''));
    if (st.locked !== undefined && st.locked !== null) sBox.appendChild(stat('Lock', st.locked ? (st.tamper ? 'LOCKED – MOVED!' : 'LOCKED') : 'unlocked', st.tamper ? 'bad' : (st.locked ? 'ok' : '')));
    $('lastseen').textContent = 'Last seen: ' + fmtTime(l.receivedAt) + ' · sample time ' + fmtTime(st.ts) + ' · source: ' + st.source + (st.charging ? ' · charging' : '');
    var m = getMap('map');
    if (st.lat != null) {
      var ll = [st.lat, st.lon];
      if (!S.marker) S.marker = L.circleMarker(ll, { radius: 9, color: '#00e0ff', fillColor: '#00e0ff', fillOpacity: 0.8 }).addTo(m); else S.marker.setLatLng(ll);
      S.marker.setStyle({ color: on ? '#00e0ff' : '#8b98a5', fillColor: on ? '#00e0ff' : '#8b98a5' });
      if (S.trail && on) S.trail.addLatLng(ll);
      if (fit) m.setView(ll, 15);
    }
  }
  setInterval(function () { if ($('app').classList.contains('hidden')) return; renderVehicleList(); renderLive(false); }, 1000);

  // ---- geofences ----
  var geoLayers = [];
  function loadGeofences() {
    var id = S.vsel; if (!id) return;
    api('/telematics/' + id + '/geofences').then(function (gs) {
      S.geofences = gs; var ul = $('geo-list'); clear(ul);
      var m = getMap('map'); geoLayers.forEach(function (c) { m.removeLayer(c); }); geoLayers = [];
      gs.forEach(function (g) {
        geoLayers.push(L.circle([g.lat, g.lon], { radius: g.radiusM, color: '#ffb020', weight: 2, fillOpacity: 0.08 }).addTo(m));
        ul.appendChild(h('li', null, h('span', null, g.name + ' · ' + g.radiusM + ' m · alert on ' + g.alertOn),
          h('button', { class: 'btn small danger', onclick: function () { if (confirm('Delete geofence "' + g.name + '"?')) api('/telematics/' + id + '/geofences/' + g.id, { method: 'DELETE' }).then(loadGeofences).catch(function (e) { toast(e.message); }); } }, 'Delete')));
      });
    }).catch(function (e) { toast(e.message); });
  }
  $('geo-form').addEventListener('submit', function (ev) {
    ev.preventDefault(); var f = ev.target; if (!S.vsel) return toast('Select a vehicle first');
    api('/telematics/' + S.vsel + '/geofences', { method: 'POST', body: { name: f.name.value.trim(), lat: +f.lat.value, lon: +f.lon.value, radiusM: +f.radiusM.value, alertOn: f.alertOn.value } })
      .then(function () { f.name.value = ''; loadGeofences(); }).catch(function (e) { toast(e.message); });
  });

  // ---- alerts ----
  function loadAlerts() {
    var ul = $('alert-list'); clear(ul);
    if (!S.vsel) { ul.appendChild(h('li', null, 'Select a vehicle on the Vehicles tab first.')); return; }
    var v = S.vehicles.filter(function (x) { return x.id === S.vsel; })[0]; $('alerts-vehicle').textContent = v ? '· ' + v.name : '';
    api('/telematics/' + S.vsel + '/alerts').then(function (as) {
      if (!as.length) ul.appendChild(h('li', null, 'No alerts.'));
      as.forEach(function (a) { ul.appendChild(alertItem(a)); });
    }).catch(function (e) { toast(e.message); });
  }
  function alertItem(a) {
    return h('li', null, h('div', null, h('div', { class: 'alert-kind' + (a.kind === 'theft' ? ' bad' : '') }, a.kind.replace('_', ' ') + (a.state && a.state.source === 'simulated' ? ' · SIMULATION' : '')), h('div', null, a.message)), h('span', { class: 'muted small' }, fmtTime(a.ts)));
  }

  // ---- rides ----
  function parseGpx(gpx) {
    var out = [], re = /<trkpt[^>]*?lat="([-\d.]+)"[^>]*?lon="([-\d.]+)"/g, m;
    while ((m = re.exec(gpx || ''))) out.push([+m[1], +m[2]]);
    return out;
  }
  var rideLayer = null;
  function loadRides() {
    var ul = $('ride-list'); clear(ul);
    api('/rides' + (S.vsel ? '?vehicleId=' + S.vsel : '')).then(function (rs) {
      if (!rs.length) ul.appendChild(h('li', null, 'No rides recorded.'));
      rs.forEach(function (r) {
        ul.appendChild(h('li', { class: 'click', onclick: function () { showRide(r.id); } }, h('span', null, fmtTime(r.startedAt)), h('span', { class: 'muted small' }, (r.distanceM / 1000).toFixed(1) + ' km')));
      });
    }).catch(function (e) { toast(e.message); });
  }
  function showRide(id) {
    api('/rides/' + id).then(function (r) {
      var m = getMap('ride-map'); if (rideLayer) m.removeLayer(rideLayer);
      var pts = parseGpx(r.gpx);
      if (pts.length) { rideLayer = L.polyline(pts, { color: '#00e0ff', weight: 4 }).addTo(m); m.fitBounds(rideLayer.getBounds(), { padding: [20, 20] }); }
      $('ride-info').textContent = fmtTime(r.startedAt) + ' → ' + fmtTime(r.endedAt) + ' · ' + (r.distanceM / 1000).toFixed(2) + ' km · max ' + fmt(r.maxSpeedKmh, 1, ' km/h') + ' · ' + fmt(r.energyWh, 0, ' Wh') + (pts.length ? '' : ' · no GPS track stored');
    }).catch(function (e) { toast(e.message); });
  }

  // ---- groups ----
  function loadGroups() {
    api('/groups').then(function (gs) {
      S.groups = gs; var ul = $('group-list'); clear(ul);
      gs.forEach(function (g) { ul.appendChild(h('li', { class: 'click' + (S.gsel === g.id ? ' sel' : ''), onclick: function () { selectGroup(g.id); } }, h('span', null, g.name), h('span', { class: 'badge' }, g.visibility))); });
      if (!gs.length) ul.appendChild(h('li', null, 'No groups yet.'));
    }).catch(function (e) { toast(e.message); });
    api('/groups/public').then(function (gs) {
      var ul = $('public-group-list'); clear(ul);
      gs.forEach(function (g) { ul.appendChild(h('li', null, h('span', null, g.name), h('button', { class: 'btn small', onclick: function () { api('/groups/join', { method: 'POST', body: { code: g.code } }).then(function () { loadGroups(); }).catch(function (e) { toast(e.message); }); } }, 'Join'))); });
    }).catch(function () {});
  }
  $('group-form').addEventListener('submit', function (ev) {
    ev.preventDefault(); var f = ev.target;
    api('/groups', { method: 'POST', body: { name: f.name.value.trim(), visibility: f.visibility.value } }).then(function (g) { f.name.value = ''; loadGroups(); selectGroup(g.id); }).catch(function (e) { toast(e.message); });
  });
  $('join-form').addEventListener('submit', function (ev) {
    ev.preventDefault(); var f = ev.target;
    api('/groups/join', { method: 'POST', body: { code: f.code.value.trim() } }).then(function (g) { f.code.value = ''; loadGroups(); selectGroup(g.id); }).catch(function (e) { toast(e.message); });
  });
  function selectGroup(id) {
    if (S.gsel && S.gsel !== id) wsSend({ type: 'leave_group', groupId: S.gsel });
    var m = getMap('group-map');
    Object.keys(S.gmarkers).forEach(function (k) { m.removeLayer(S.gmarkers[k]); }); S.gmarkers = {};
    if (S.groupRoute) { m.removeLayer(S.groupRoute); S.groupRoute = null; }
    S.gsel = id;
    api('/groups/' + id).then(function (g) {
      $('group-name').textContent = g.name; var c = $('group-code'); c.textContent = 'Code ' + g.code; c.classList.remove('hidden');
      var ul = $('member-list'); clear(ul);
      g.members.forEach(function (mb) { ul.appendChild(h('li', { id: 'm-' + mb.userId }, h('span', null, mb.displayName + ' (' + mb.role + ')'), h('span', { class: 'badge ' + (mb.online ? 'on' : 'off') }, mb.online ? 'ONLINE' : 'OFFLINE'))); });
      var mine = g.ownerId === S.user.id;
      $('group-leave').classList.toggle('hidden', mine); $('group-delete').classList.toggle('hidden', !mine);
      wsSend({ type: 'join_group', groupId: id });
      api('/groups/' + id + '/route').then(function (r) { var pts = parseGpx(r.gpx); if (pts.length && S.gsel === id) { S.groupRoute = L.polyline(pts, { color: '#ffb020', weight: 3, dashArray: '6 6' }).addTo(m); m.fitBounds(S.groupRoute.getBounds()); } }).catch(function () {});
      loadGroups(); setTimeout(function () { m.invalidateSize(); }, 50);
    }).catch(function (e) { toast(e.message); });
  }
  $('group-leave').addEventListener('click', function () { if (S.gsel && confirm('Leave this group?')) api('/groups/' + S.gsel + '/members/me', { method: 'DELETE' }).then(function () { S.gsel = null; $('group-name').textContent = 'Select a group'; clear($('member-list')); loadGroups(); }).catch(function (e) { toast(e.message); }); });
  $('group-delete').addEventListener('click', function () { if (S.gsel && confirm('Delete this group for everyone?')) api('/groups/' + S.gsel, { method: 'DELETE' }).then(function () { S.gsel = null; $('group-name').textContent = 'Select a group'; clear($('member-list')); loadGroups(); }).catch(function (e) { toast(e.message); }); });
  function memberPosition(m) {
    if (m.groupId !== S.gsel) return;
    var map = getMap('group-map'), ll = [m.lat, m.lon];
    if (!S.gmarkers[m.userId]) { S.gmarkers[m.userId] = L.circleMarker(ll, { radius: 8, color: '#00e0ff', fillOpacity: 0.8 }).bindTooltip(m.displayName + (m.vehicleName ? ' · ' + m.vehicleName : ''), { permanent: true, direction: 'top' }).addTo(map); }
    else S.gmarkers[m.userId].setLatLng(ll);
  }

  // ---- websocket ----
  function wsSend(o) { if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify(o)); }
  function connectWs() {
    if (!S.token) return;
    var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    var ws = new WebSocket(proto + location.host + '/ws?token=' + encodeURIComponent(S.token));
    S.ws = ws;
    ws.onopen = function () {
      S.wsTry = 0; $('ws-dot').classList.add('on');
      S.vehicles.forEach(function (v) { wsSend({ type: 'subscribe_vehicle', vehicleId: v.id }); });
      if (S.gsel) wsSend({ type: 'join_group', groupId: S.gsel });
    };
    ws.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      switch (m.type) {
        case 'ping': wsSend({ type: 'pong' }); break;
        case 'vehicle_state': setLive(m.vehicleId, m.state, m.receivedAt || Date.now(), m.online !== false); renderVehicleList(); if (m.vehicleId === S.vsel) renderLive(false); break;
        case 'alert':
          toast(m.alert.message);
          if (!$('tab-alerts').classList.contains('hidden') && m.vehicleId === S.vsel) $('alert-list').insertBefore(alertItem(m.alert), $('alert-list').firstChild);
          break;
        case 'member_position': memberPosition(m); break;
        case 'presence': var li = $('m-' + m.userId); if (li) { var b = li.querySelector('.badge'); b.className = 'badge ' + (m.online ? 'on' : 'off'); b.textContent = m.online ? 'ONLINE' : 'OFFLINE'; } break;
        case 'error': toast(m.message || m.error); break;
        default: break;
      }
    };
    ws.onclose = function () {
      $('ws-dot').classList.remove('on');
      if (S.token && S.ws === ws) setTimeout(connectWs, Math.min(30000, 1000 * Math.pow(2, S.wsTry++)));
    };
  }

  // ---- boot ----
  function start() {
    $('auth').classList.add('hidden'); $('app').classList.remove('hidden');
    $('who').textContent = S.user ? S.user.displayName : '';
    getMap('map').on('click', function (e) { var f = $('geo-form'); f.lat.value = e.latlng.lat.toFixed(6); f.lon.value = e.latlng.lng.toFixed(6); });
    connectWs();
    loadVehicles().catch(function (e) { toast(e.message); });
  }
  S.token = load('kinetic_token');
  if (S.token) {
    api('/me').then(function (u) { S.user = u; start(); }).catch(function () { S.token = null; showAuth(); });
  } else showAuth();
})();

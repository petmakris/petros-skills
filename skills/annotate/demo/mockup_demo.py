#!/usr/bin/env python3
"""Stand up a live annotate page showcasing the `mockup` block kind.

Usage:
    PYTHONPATH=. python3 skills/annotate/demo/mockup_demo.py <port>

Talks to an already-running annotate server on <port>, creates a session, and
writes a blocks.json with an intro + two high-fidelity mockup blocks (one
interactive). Prints the session URL to open in a browser.
"""
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

PORT = sys.argv[1]
BASE = f"http://localhost:{PORT}"


def post(path, payload):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read().decode())


# --- Mockup A: a polished analytics dashboard with hover states + working tabs.
DASHBOARD = r"""
<style>
  *{box-sizing:border-box} body{font:14px/1.5 ui-sans-serif,system-ui;color:#1b2030;background:#eef1f8}
  .app{display:grid;grid-template-columns:200px 1fr;min-height:520px}
  .side{background:linear-gradient(180deg,#4f46e5,#7c3aed);color:#fff;padding:20px 14px}
  .brand{font-weight:800;font-size:18px;letter-spacing:.02em;margin-bottom:24px;display:flex;gap:8px;align-items:center}
  .nav a{display:flex;gap:10px;align-items:center;padding:10px 12px;border-radius:9px;color:#e7e3ff;text-decoration:none;margin-bottom:4px;transition:.15s}
  .nav a:hover{background:rgba(255,255,255,.14)}
  .nav a.on{background:#fff;color:#4f46e5;font-weight:600}
  .main{padding:24px 28px}
  .head{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}
  .head h1{margin:0;font-size:22px}
  .avatar{width:38px;height:38px;border-radius:50%;background:linear-gradient(135deg,#f59e0b,#ef4444)}
  .stats{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:22px}
  .card{background:#fff;border-radius:14px;padding:18px;box-shadow:0 1px 3px rgba(20,30,60,.08);transition:.18s;cursor:pointer}
  .card:hover{transform:translateY(-4px);box-shadow:0 12px 30px rgba(79,70,229,.18)}
  .card .lbl{color:#6b7280;font-size:12px;text-transform:uppercase;letter-spacing:.06em}
  .card .val{font-size:28px;font-weight:800;margin-top:6px}
  .card .delta{font-size:12px;font-weight:600;margin-top:6px}
  .up{color:#10b981}.down{color:#ef4444}
  .tabs{display:flex;gap:6px;margin-bottom:14px}
  .tab{padding:8px 16px;border-radius:9px;border:0;background:#fff;color:#4b5563;font-weight:600;cursor:pointer;transition:.15s}
  .tab.on{background:#4f46e5;color:#fff}
  .panel{background:#fff;border-radius:14px;padding:20px;box-shadow:0 1px 3px rgba(20,30,60,.08)}
  .chart{display:flex;align-items:flex-end;gap:14px;height:180px;padding-top:10px}
  .bar{flex:1;border-radius:8px 8px 0 0;background:linear-gradient(180deg,#818cf8,#4f46e5);position:relative}
  .bar span{position:absolute;bottom:-22px;left:0;right:0;text-align:center;font-size:11px;color:#9ca3af}
  .hidden{display:none}
</style>
<div class="app">
  <aside class="side" data-annotate-id="sidebar">
    <div class="brand">📊 Acme</div>
    <nav class="nav">
      <a class="on" href="#">▦ Dashboard</a>
      <a href="#">👥 Customers</a>
      <a href="#">💳 Billing</a>
      <a href="#">⚙ Settings</a>
    </nav>
  </aside>
  <main class="main">
    <div class="head"><h1>Overview</h1><div class="avatar"></div></div>
    <div class="stats" data-annotate-id="stat-cards">
      <div class="card"><div class="lbl">Revenue</div><div class="val">$48.2k</div><div class="delta up">▲ 12.4% vs last week</div></div>
      <div class="card"><div class="lbl">Active users</div><div class="val">9,310</div><div class="delta up">▲ 4.1%</div></div>
      <div class="card"><div class="lbl">Churn</div><div class="val">1.8%</div><div class="delta down">▼ 0.3%</div></div>
    </div>
    <div class="tabs" data-annotate-id="chart-tabs">
      <button class="tab on" onclick="showTab(0,this)">Traffic</button>
      <button class="tab" onclick="showTab(1,this)">Revenue</button>
    </div>
    <div class="panel">
      <div class="chart" id="t0">
        <div class="bar" style="height:55%"><span>Mon</span></div>
        <div class="bar" style="height:72%"><span>Tue</span></div>
        <div class="bar" style="height:48%"><span>Wed</span></div>
        <div class="bar" style="height:88%"><span>Thu</span></div>
        <div class="bar" style="height:66%"><span>Fri</span></div>
      </div>
      <div class="chart hidden" id="t1">
        <div class="bar" style="height:40%;background:linear-gradient(180deg,#34d399,#059669)"><span>Mon</span></div>
        <div class="bar" style="height:62%;background:linear-gradient(180deg,#34d399,#059669)"><span>Tue</span></div>
        <div class="bar" style="height:78%;background:linear-gradient(180deg,#34d399,#059669)"><span>Wed</span></div>
        <div class="bar" style="height:95%;background:linear-gradient(180deg,#34d399,#059669)"><span>Thu</span></div>
        <div class="bar" style="height:83%;background:linear-gradient(180deg,#34d399,#059669)"><span>Fri</span></div>
      </div>
    </div>
  </main>
</div>
<script>
  function showTab(i, btn){
    document.querySelectorAll('.tab').forEach(t=>t.classList.remove('on'));
    btn.classList.add('on');
    document.getElementById('t0').classList.toggle('hidden', i!==0);
    document.getElementById('t1').classList.toggle('hidden', i!==1);
  }
</script>
"""

# --- Mockup B: a settings card with a real working toggle (proves <script>).
SETTINGS = r"""
<style>
  body{font:14px/1.5 ui-sans-serif,system-ui;color:#111827;background:#f9fafb;margin:0;padding:24px}
  .card{max-width:520px;background:#fff;border-radius:16px;box-shadow:0 4px 20px rgba(0,0,0,.06);overflow:hidden}
  .card h2{margin:0;padding:20px 22px;border-bottom:1px solid #f0f1f4;font-size:17px}
  .row{display:flex;justify-content:space-between;align-items:center;padding:18px 22px;border-bottom:1px solid #f5f6f8}
  .row:last-child{border-bottom:0}
  .row .t{font-weight:600}.row .d{color:#6b7280;font-size:12.5px}
  .sw{width:46px;height:26px;border-radius:20px;background:#d1d5db;position:relative;cursor:pointer;transition:.2s;border:0}
  .sw::after{content:"";position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:.2s;box-shadow:0 1px 3px rgba(0,0,0,.3)}
  .sw.on{background:#4f46e5}.sw.on::after{left:23px}
  .seg{display:inline-flex;background:#f3f4f6;border-radius:10px;padding:3px}
  .seg button{border:0;background:transparent;padding:7px 14px;border-radius:8px;font-weight:600;color:#6b7280;cursor:pointer}
  .seg button.on{background:#fff;color:#111827;box-shadow:0 1px 2px rgba(0,0,0,.1)}
</style>
<div class="card" data-annotate-id="settings-card">
  <h2>Notification preferences</h2>
  <div class="row">
    <div><div class="t">Email digest</div><div class="d">A weekly summary every Monday</div></div>
    <button class="sw on" onclick="this.classList.toggle('on')"></button>
  </div>
  <div class="row">
    <div><div class="t">Push notifications</div><div class="d">Real-time alerts on this device</div></div>
    <button class="sw" onclick="this.classList.toggle('on')"></button>
  </div>
  <div class="row">
    <div><div class="t">Frequency</div><div class="d">How often we bundle alerts</div></div>
    <div class="seg" data-annotate-id="frequency">
      <button onclick="seg(this)">Live</button>
      <button class="on" onclick="seg(this)">Hourly</button>
      <button onclick="seg(this)">Daily</button>
    </div>
  </div>
</div>
<script>
  function seg(b){ b.parentNode.querySelectorAll('button').forEach(x=>x.classList.remove('on')); b.classList.add('on'); }
</script>
"""

INTRO = (
    "# Mockup kind — live demo\n\n"
    "Below are two **real, sandboxed-iframe mockups** rendered by the new "
    "`kind: \"mockup\"`. The dashboard has hover-lift cards and **working tabs**; "
    "the settings panel has a **working toggle and segmented control** — proof "
    "that `<style>` and `<script>` run, fully isolated from this page.\n\n"
    "Click a tagged region inside a mockup (the sidebar, a card, the settings "
    "panel) to comment on **just that region**, or hover the block and use the "
    "comment button to comment on the whole mockup."
)

project = Path(tempfile.mkdtemp(prefix="annotate-mockup-demo-"))
sess = post("/api/sessions", {"cwd": str(project)})
response_dir = Path(sess["response_dir"])

doc = {
    "response_id": "mockup-demo",
    "title": "Mockup kind — live demo",
    "blocks": [
        {"id": "b-0", "markdown": INTRO, "version": 1},
        {"id": "b-1", "kind": "mockup", "version": 1,
         "spec": {"title": "Analytics dashboard", "html": DASHBOARD}},
        {"id": "b-2", "kind": "mockup", "version": 1,
         "spec": {"title": "Settings panel", "html": SETTINGS}},
    ],
}
tmp = response_dir / "blocks.json.tmp"
tmp.write_text(json.dumps(doc))
os.replace(tmp, response_dir / "blocks.json")

print("RESPONSE_DIR=" + str(response_dir))
print("OPEN_URL=" + sess["url"])

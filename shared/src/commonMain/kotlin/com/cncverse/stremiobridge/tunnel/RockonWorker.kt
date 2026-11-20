package com.cncverse.stremiobridge.tunnel

/**
 * The Rockon relay for Cloudflare Workers (Rockon repo, worker/rockon-worker.js), shipped in the app so it can be copied
 * from Settings > Fixed link > "Your Worker" and pasted into a new Worker on a phone.
 */
object RockonWorker {
    /** What to do in the Cloudflare dashboard, step by step. */
    val STEPS: List<String> = listOf(
        "Open dash.cloudflare.com (free account) > Workers & Pages > Create > Create Worker. Name it rockon and tap Deploy.",
        "Tap Edit code. Select all the sample code, delete it, paste the Worker code copied from here, and tap Deploy.",
        "Go to Storage & Databases > KV > Create a namespace. Name it rockon.",
        "Open the rockon Worker > Settings > Bindings > Add > KV namespace. Variable name RELAY (capitals), namespace rockon, then Save.",
        "Open the Worker's address (Settings > Domains, like https://rockon.yourname.workers.dev). It should say \"Rockon relay is running.\"",
        "Paste that address below and tap Save Worker address. Restart the tunnel, then install the add-on again with the new fixed link.",
    )

    const val CODE: String = """// Rockon relay for Cloudflare Workers - a fixed link for the CNCVerse bridge's free tunnel.
//
// The bridge registers its current https://....trycloudflare.com link under a permanent id, and
//   https://<this worker>/<id>/<anything>?<query>   redirects to   https://<current tunnel>/<anything>?<query>
//
// Setup in the Cloudflare dashboard:
//   1. Workers & Pages > Create > Create Worker > name it "rockon" > Deploy.
//      Then "Edit code", select all of the sample code, paste this whole file over it, and Deploy.
//   2. Storage & Databases > KV > Create a namespace, name it "rockon".
//   3. Back in the Worker: Settings > Bindings > Add > KV namespace.
//      Variable name: RELAY   KV namespace: rockon   > Save (the Worker redeploys).
//   4. Copy the Worker's address (https://rockon.<your-name>.workers.dev) into the bridge:
//      Settings > Fixed link > Relay "Your Worker" > Save Worker address.
//
// Free plan: 100,000 requests a day; KV 100,000 reads and 1,000 writes a day. Reads are remembered for
// 30 s and a registration writes only when the tunnel link changed, so this uses very little.
// Nothing here lists the ids or shows where a link leads, except the redirect itself.

var ID_RE = /^[a-z0-9]{4,32}$/;
var RESERVED = ['api', 'links', 'register', 'tunnel', 'favicon', 'robots', 'index'];
var MEMO_MS = 5000; // how long this Worker trusts what it read (short: after a tunnel restart a stale answer leads to the dead tunnel)
var MISS_MS = 15000; // an unknown id is not looked up again for this long
var memo = new Map(); // id -> { url, at } (url null = not registered)

export default {
  async fetch(request, env) {
    try {
      return await handle(request, env || {});
    } catch (err) {
      return text('Rockon relay error: ' + (err && err.message ? err.message : String(err)), 500);
    }
  },
};

async function handle(request, env) {
  var url = new URL(request.url);
  var parts = url.pathname.replace(/^\/+/, '').split('/');
  var first = safeDecode(parts[0] || '');

  if (first === '' || first === 'index.html') {
    return text(env.RELAY ? 'Rockon relay is running.' : 'Rockon relay: add a KV namespace binding named RELAY (Settings > Bindings).', 200);
  }
  if (first === 'robots.txt') return text('User-agent: *\nDisallow: /\n', 200);
  if (!env.RELAY) return text('Rockon relay: add a KV namespace binding named RELAY (Settings > Bindings).', 503);

  if (first === 'api' && parts[1] === 'tunnel' && parts[2] === 'register') return register(request, env, url);
  if (first === 'api' && parts[1] === 'tunnel' && parts[2]) return status(env, parts[2]);

  var id = cleanId(first);
  if (!id) return text('Not found', 404);
  var target = await lookup(env, id);
  if (!target) return text('Not found', 404);

  // the rest of the path stays exactly as it was sent (still encoded), and the query is passed on
  var rest = parts.slice(1).join('/');
  var location = target.replace(/\/+$/, '') + (rest ? '/' + rest : '') + url.search;
  return new Response(null, { status: 302, headers: headers({ Location: location }) });
}

function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch (e) {
    return s;
  }
}

function cleanId(value) {
  var id = String(value || '').trim().toLowerCase();
  return ID_RE.test(id) && RESERVED.indexOf(id) < 0 ? id : null;
}

function allowedHost(host, env) {
  var list = String(env.ALLOWED_TUNNEL_HOSTS || 'trycloudflare.com')
    .split(',')
    .map(function (h) { return h.trim().toLowerCase().replace(/^\*?\./, ''); })
    .filter(Boolean);
  if (list.indexOf('*') >= 0) return true;
  host = host.toLowerCase();
  return list.some(function (d) { return host === d || host.endsWith('.' + d); });
}

function cleanUrl(value, env) {
  var u;
  try {
    u = new URL(String(value).trim());
  } catch (e) {
    return null;
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  if (u.username || u.password || !allowedHost(u.hostname, env)) return null;
  return (u.origin + u.pathname).replace(/\/+$/, '');
}

async function lookup(env, id) {
  var now = Date.now();
  var hit = memo.get(id);
  if (hit && now - hit.at < (hit.url ? MEMO_MS : MISS_MS)) return hit.url;
  // cacheTtl lets KV answer from this Cloudflare location for 30 s
  var found = await env.RELAY.get(id, { cacheTtl: 30 });
  memo.set(id, { url: found || null, at: now });
  if (memo.size > 1000) memo.clear();
  return found || null;
}

function authorised(request, env, url) {
  var token = env.REGISTER_TOKEN;
  if (!token) return true;
  var auth = request.headers.get('Authorization') || '';
  var given = request.headers.get('X-Register-Token') || (auth.indexOf('Bearer ') === 0 ? auth.slice(7) : '') || url.searchParams.get('key') || '';
  return given === token;
}

async function register(request, env, url) {
  if (request.method !== 'POST') return json({ ok: false, error: 'use POST' }, 405);
  if (!authorised(request, env, url)) return json({ ok: false, error: 'bad or missing key' }, 401);

  var body = {};
  var type = request.headers.get('Content-Type') || '';
  try {
    if (type.indexOf('application/json') >= 0) body = (await request.json()) || {};
    else if (type.indexOf('form') >= 0) body = Object.fromEntries(await request.formData());
  } catch (e) {
    body = {};
  }
  function field(name) {
    var v = body[name];
    if (v !== undefined && v !== null && v !== '') return v;
    return url.searchParams.get(name);
  }

  var id = cleanId(field('shortId') || field('id'));
  if (!id) return json({ ok: false, error: 'shortId must be 4-32 lower-case letters or digits' }, 400);

  var raw = field('tunnelUrl');
  if (raw === null || raw === undefined) raw = field('url');
  if (raw === '' || raw === null || raw === undefined) {
    // the bridge sends "" when its tunnel stops: the fixed address should not lead somewhere dead
    await env.RELAY.delete(id);
    memo.set(id, { url: null, at: Date.now() });
    return json({ ok: true, shortId: id, cleared: true });
  }

  var target = cleanUrl(raw, env);
  if (!target) return json({ ok: false, error: 'tunnelUrl must be an http(s) link on an allowed host (trycloudflare.com)' }, 400);

  // KV allows 1,000 writes a day on the free plan: only write when the link really changed
  var current = await env.RELAY.get(id);
  if (current !== target) await env.RELAY.put(id, target);
  memo.set(id, { url: target, at: Date.now() });
  return json({ ok: true, shortId: id, tunnelUrl: target, changed: current !== target });
}

async function status(env, rawId) {
  var id = cleanId(safeDecode(rawId));
  if (!id) return json({ ok: false, error: 'bad id' }, 400);
  // only whether it is registered: the target is never shown
  var found = await lookup(env, id);
  return found ? json({ shortId: id, registered: true }) : json({ ok: false, error: 'not registered' }, 404);
}

function headers(extra) {
  var h = { 'X-Robots-Tag': 'noindex', 'Referrer-Policy': 'no-referrer', 'Cache-Control': 'no-store' };
  for (var k in extra) h[k] = extra[k];
  return h;
}

function json(data, code) {
  return new Response(JSON.stringify(data), { status: code || 200, headers: headers({ 'Content-Type': 'application/json; charset=utf-8' }) });
}

function text(body, code) {
  return new Response(body, { status: code, headers: headers({ 'Content-Type': 'text/plain; charset=utf-8' }) });
}
"""
}

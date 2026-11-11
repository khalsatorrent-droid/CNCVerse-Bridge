package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.writeTextAtomic
import com.cncverse.stremiobridge.network.DohHttp
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Simkl on the website: link a Simkl account (PIN login, no password passes through the bridge), show the library, and mark
 * a movie / episode as watched. The app's Client ID (a free app made at simkl.com/settings/developer) and the account's token
 * are kept here on the bridge, in simkl.json, never in the browser.
 */
object WebSimkl {
    @Serializable
    private data class Saved(val clientId: String = "", val token: String = "")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var saved = Saved()
    private var file: File? = null
    private const val API = "https://api.simkl.com"
    private val STATUSES = setOf("watching", "plantowatch", "completed", "hold", "dropped")
    private val CLIENT_ID = Regex("[A-Za-z0-9]{16,100}")
    private val PIN = Regex("[A-Za-z0-9]{3,24}")
    /** Built-in app (works without making one): used while no own Client ID is saved. Simkl wants the app named on every call. */
    private const val DEFAULT_CLIENT_ID = "dc20e0db975583b15096267cee79cd23b1f56d4bd301ce3c51e4a96a49c834a6"
    private const val APP_PARAMS = "app-name=nuvio&app-version="

    /** The Client ID in use: the one saved in Settings, else the built-in one. */
    private fun clientId(): String = saved.clientId.ifEmpty { DEFAULT_CLIENT_ID }

    fun load(cacheDir: String) {
        val f = File(cacheDir, "simkl.json")
        file = f
        saved = runCatching { json.decodeFromString<Saved>(f.readText()) }.getOrDefault(Saved())
        val sf = File(cacheDir, "simkl_seen.json")
        seenFile = sf
        seen = runCatching { json.decodeFromString<Seen>(sf.readText()) }.getOrDefault(Seen())
    }

    private fun save(s: Saved) {
        // another account / app: what was watched belongs to the old one
        if (s.token != saved.token || s.clientId != saved.clientId) { seen = Seen(); runCatching { seenFile?.delete() } }
        saved = s
        libCache.clear()
        runCatching { file?.writeTextAtomic(json.encodeToString(s)) }.onFailure { ServerState.warn("Failed to save simkl.json: ${it.message}") }
    }

    private fun headers(withToken: Boolean): Map<String, String> {
        val h = linkedMapOf("simkl-api-key" to clientId(), "User-Agent" to "CNCVerse-Bridge")
        if (withToken && saved.token.isNotEmpty()) h["Authorization"] = "Bearer " + saved.token
        return h
    }

    private suspend fun sk(method: String, path: String, body: String? = null, withToken: Boolean = true): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            // every call names the app: client_id (when the path has none) + app-name / app-version
            val q = (if (path.contains("client_id=")) "" else "client_id=${clientId()}&") + APP_PARAMS
            DohHttp.request(method, API + path + (if (path.contains('?')) "&" else "?") + q, body, headers(withToken))
        }

    private fun parse(t: String): JsonObject? = runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    private class Lib(val at: Long, val body: String)
    private val libCache = ConcurrentHashMap<String, Lib>()

    /** One library entry for the page: the card of the site (TMDB id, so it opens like any title), with the watched count as its note. */
    private fun entries(root: JsonObject?, key: String, inner: String, type: String, skipped: IntArray): List<Pair<String, JsonObject>> {
        val list = root?.get(key) as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val m = o.obj(inner) ?: return@mapNotNull null
            val tmdb = m.obj("ids")?.str("tmdb")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 10 }
            if (tmdb == null) { skipped[0]++; return@mapNotNull null }
            val title = m.str("title") ?: return@mapNotNull null
            val watched = o.str("watched_episodes_count")?.toIntOrNull()
            val total = o.str("total_episodes_count")?.toIntOrNull()
            val sub = if (type == "tv" && watched != null && total != null && total > 0) "$watched/$total watched" else ""
            (o.str("last_watched_at") ?: o.str("added_to_watchlist_at") ?: "") to buildJsonObject {
                put("id", tmdb); put("type", type); put("title", title)
                put("year", m.str("year") ?: "")
                m.str("poster")?.takeIf { it.isNotBlank() }?.let { put("poster", "https://simkl.in/posters/${it}_m.jpg") }
                if (sub.isNotEmpty()) put("sub", sub)
            }
        }
    }

    private suspend fun library(status: String): String {
        libCache[status]?.let { if (System.currentTimeMillis() - it.at < 120_000) return it.body }
        val skipped = IntArray(1)
        val parts = coroutineScope {
            listOf("movies", "shows", "anime").map { t ->
                async {
                    val (code, body) = sk("GET", "/sync/all-items/$t/$status?extended=full")
                    if (code == 401) { save(saved.copy(token = "")); return@async null }
                    if (code != 200) return@async null
                    t to parse(body)
                }
            }.awaitAll()
        }
        if (saved.token.isEmpty()) return """{"linked":false,"items":[]}"""
        val all = ArrayList<Pair<String, JsonObject>>()
        var failed = 0
        for (p in parts) {
            if (p == null) { failed++; continue }
            val (t, root) = p
            when (t) {
                "movies" -> all += entries(root, "movies", "movie", "movie", skipped)
                "shows" -> all += entries(root, "shows", "show", "tv", skipped)
                else -> all += entries(root, "anime", "show", "tv", skipped)
            }
        }
        val body = buildJsonObject {
            put("linked", true)
            put("items", buildJsonArray { all.sortedByDescending { it.first }.distinctBy { it.second.str("type") + it.second.str("id") }.forEach { add(it.second) } })
            put("hidden", skipped[0])
            put("failed", failed)
        }.toString()
        if (failed == 0) libCache[status] = Lib(System.currentTimeMillis(), body)
        return body
    }

    /** Marks a movie / an episode as watched on Simkl (TMDB ids). Returns {"ok":..,"found":..}. */
    private suspend fun markWatched(type: String, id: String, s: Int?, e: Int?): String {
        val now = java.time.Instant.now().toString()
        val body = buildJsonObject {
            if (type == "movie") put("movies", buildJsonArray { add(buildJsonObject { put("watched_at", now); put("ids", buildJsonObject { put("tmdb", id.toLong()) }) }) })
            else put("shows", buildJsonArray {
                add(buildJsonObject {
                    put("ids", buildJsonObject { put("tmdb", id.toLong()) })
                    put("seasons", buildJsonArray {
                        add(buildJsonObject {
                            put("number", s ?: 1)
                            put("episodes", buildJsonArray { add(buildJsonObject { put("number", e ?: 1); put("watched_at", now) }) })
                        })
                    })
                })
            })
        }.toString()
        val (code, text) = sk("POST", "/sync/history", body)
        if (code == 401) { save(saved.copy(token = "")); return """{"ok":false,"linked":false}""" }
        if (code !in 200..299) return """{"ok":false,"linked":true}"""
        libCache.clear()
        val nf = parse(text)?.obj("not_found")
        val missing = nf != null && nf.values.any { (it as? JsonArray)?.isNotEmpty() == true }
        ServerState.info("[Simkl] marked watched: $type $id" + (if (type == "tv") " S$s E$e" else "") + if (missing) " (Simkl does not know this title)" else "")
        return """{"ok":true,"linked":true,"found":${!missing}}"""
    }

    // ── What was already watched on Simkl (marks the site's episodes / movies as watched) ──
    //
    // Simkl's rule for apps that sync: never download the whole library on a timer. So /sync/activities is asked first (one
    // small answer) and the library is only read when its "all" time changed - after the first time, only the items changed
    // since the last read (date_from). Asked at most once every 5 minutes, whatever the pages do.

    /** item ("movie:<tmdb>" / "tv:<tmdb>") -> its watched episodes ("s:e"; empty for a movie) */
    @Serializable
    private data class Seen(val all: String = "", val syncedAt: String = "", val items: Map<String, List<String>> = emptyMap())

    @Volatile private var seen = Seen()
    private var seenFile: File? = null
    @Volatile private var seenCheckedAt = 0L
    private val seenLock = kotlinx.coroutines.sync.Mutex()
    private const val SEEN_EVERY_MS = 5 * 60_000L

    private fun watchedOf(o: JsonObject, inner: String, type: String, out: MutableMap<String, List<String>>) {
        val m = o.obj(inner) ?: return
        val tmdb = m.obj("ids")?.str("tmdb")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 10 } ?: return
        val completed = o.str("status") == "completed"
        if (type == "movie") {
            if (completed || o.str("last_watched_at") != null) out["movie:$tmdb"] = emptyList() else out.remove("movie:$tmdb")
            return
        }
        val eps = ArrayList<String>()
        (o["seasons"] as? JsonArray)?.forEach { se ->
            val so = se as? JsonObject ?: return@forEach
            val sn = so.str("number")?.toIntOrNull() ?: return@forEach
            (so["episodes"] as? JsonArray)?.forEach { ep ->
                val eo = ep as? JsonObject ?: return@forEach
                val en = eo.str("number")?.toIntOrNull() ?: return@forEach
                // a completed show lists all its episodes; otherwise only the ones with a watch time were watched
                if (completed || eo.str("watched_at") != null) eps += "$sn:$en"
            }
        }
        if (eps.isEmpty()) out.remove("tv:$tmdb") else out["tv:$tmdb"] = eps
    }

    private suspend fun syncSeen() = seenLock.withLock {
        if (saved.token.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        if (now - seenCheckedAt < SEEN_EVERY_MS) return@withLock
        seenCheckedAt = now
        val (ac, at) = runCatching { sk("GET", "/sync/activities") }.getOrNull() ?: return@withLock
        if (ac == 401) { save(saved.copy(token = "")); return@withLock }
        val all = parse(at)?.str("all") ?: return@withLock
        if (ac != 200 || (all == seen.all && seen.syncedAt.isNotEmpty())) return@withLock
        val since = seen.syncedAt.takeIf { it.isNotEmpty() }
        val startedAt = java.time.Instant.now().toString()
        val path = "/sync/all-items?extended=full&episode_watched_at=yes&include_all_episodes=yes" +
            (since?.let { "&date_from=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val (code, body) = runCatching { sk("GET", path) }.getOrNull() ?: return@withLock
        if (code != 200) return@withLock
        val root = parse(body)
        val items = if (since == null) HashMap() else HashMap(seen.items)
        (root?.get("movies") as? JsonArray)?.forEach { (it as? JsonObject)?.let { o -> watchedOf(o, "movie", "movie", items) } }
        (root?.get("shows") as? JsonArray)?.forEach { (it as? JsonObject)?.let { o -> watchedOf(o, "show", "tv", items) } }
        (root?.get("anime") as? JsonArray)?.forEach { (it as? JsonObject)?.let { o ->
            // anime movies are movies; anime series use Simkl's season numbers (most match TMDB's)
            if (o.obj("show")?.str("anime_type") == "movie" || o.obj("movie") != null) watchedOf(o, if (o.obj("movie") != null) "movie" else "show", "movie", items)
            else watchedOf(o, "show", "tv", items)
        } }
        seen = Seen(all, startedAt, items)
        runCatching { seenFile?.writeTextAtomic(json.encodeToString(seen)) }
        ServerState.info("[Simkl] watched history ${if (since == null) "read" else "updated"}: ${items.count { it.key.startsWith("movie:") }} movie(s), " +
            "${items.values.sumOf { it.size }} episode(s)")
    }

    /** The site's watched keys ("tmdb:<id>" for a movie, "tmdb:<id>:<s>:<e>" for an episode). */
    private fun seenKeys(): List<String> {
        val out = ArrayList<String>()
        for ((k, eps) in seen.items) {
            val id = k.substringAfter(':')
            if (k.startsWith("movie:")) out += "tmdb:$id" else eps.forEach { out += "tmdb:$id:$it" }
        }
        return out
    }

    fun Route.simklRoutes(guard: suspend ApplicationCall.() -> Boolean) {
        get("/web/api/simkl/seen") {
            if (!call.guard()) return@get
            if (saved.token.isEmpty()) return@get call.respondText("""{"linked":false,"keys":[]}""", ContentType.Application.Json)
            // a slow Simkl answer must not hold the page: what is known so far goes out, the rest comes next time
            kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { syncSeen() } }
            call.respondText(buildJsonObject {
                put("linked", saved.token.isNotEmpty())
                put("at", seen.syncedAt)
                put("keys", buildJsonArray { seenKeys().forEach { add(it) } })
            }.toString(), ContentType.Application.Json)
        }
        get("/web/api/simkl/status") {
            if (!call.guard()) return@get
            call.respondText("""{"keyed":true,"own":${saved.clientId.isNotEmpty()},"linked":${saved.token.isNotEmpty()}}""", ContentType.Application.Json)
        }
        post("/web/api/simkl/config") {
            if (!call.guard()) return@post
            val id = call.receiveParameters()["client_id"].orEmpty().trim()
            if (id.isNotEmpty() && !CLIENT_ID.matches(id)) return@post call.respond(HttpStatusCode.BadRequest)
            // another app's id: the old token does not belong to it
            if (id != saved.clientId) save(Saved(id, ""))
            call.respondText("""{"keyed":true,"own":${saved.clientId.isNotEmpty()}}""", ContentType.Application.Json)
        }
        post("/web/api/simkl/pin") {
            if (!call.guard()) return@post
            val (code, text) = runCatching { sk("GET", "/oauth/pin?client_id=${clientId()}", withToken = false) }.getOrNull() ?: (0 to "")
            val o = parse(text)
            val user = o?.str("user_code")
            if (code != 200 || user == null) return@post call.respond(HttpStatusCode.BadGateway)
            call.respondText(buildJsonObject {
                put("user_code", user)
                put("verification_url", o?.str("verification_url") ?: "https://simkl.com/pin/")
                put("expires_in", o?.str("expires_in")?.toIntOrNull() ?: 900)
                put("interval", (o?.str("interval")?.toIntOrNull() ?: 5).coerceIn(3, 30))
            }.toString(), ContentType.Application.Json)
        }
        get("/web/api/simkl/poll") {
            if (!call.guard()) return@get
            val pin = call.request.queryParameters["code"].orEmpty()
            if (!PIN.matches(pin)) return@get call.respond(HttpStatusCode.BadRequest)
            val (_, text) = runCatching { sk("GET", "/oauth/pin/$pin?client_id=${clientId()}", withToken = false) }.getOrNull() ?: (0 to "")
            val token = parse(text)?.str("access_token")?.takeIf { it.isNotBlank() }
            if (token != null) { save(saved.copy(token = token)); ServerState.info("[Simkl] account linked") }
            call.respondText("""{"linked":${saved.token.isNotEmpty()}}""", ContentType.Application.Json)
        }
        post("/web/api/simkl/unlink") {
            if (!call.guard()) return@post
            save(saved.copy(token = ""))
            call.respondText("""{"linked":false}""", ContentType.Application.Json)
        }
        get("/web/api/simkl/library") {
            if (!call.guard()) return@get
            val status = call.request.queryParameters["status"].orEmpty().ifEmpty { "watching" }
            if (status !in STATUSES) return@get call.respond(HttpStatusCode.BadRequest)
            if (saved.token.isEmpty()) return@get call.respondText("""{"linked":false,"items":[]}""", ContentType.Application.Json)
            call.respondText(library(status), ContentType.Application.Json)
        }
        post("/web/api/simkl/watched") {
            if (!call.guard()) return@post
            val p = call.receiveParameters()
            val type = p["type"]
            val id = p["id"].orEmpty()
            val s = p["s"]?.toIntOrNull()
            val e = p["e"]?.toIntOrNull()
            if ((type != "movie" && type != "tv") || id.isEmpty() || !id.all(Char::isDigit) || id.length > 10) return@post call.respond(HttpStatusCode.BadRequest)
            if (type == "tv" && (s == null || e == null || s < 0 || e < 0 || s > 999 || e > 9999)) return@post call.respond(HttpStatusCode.BadRequest)
            if (saved.token.isEmpty()) return@post call.respondText("""{"ok":false,"linked":false}""", ContentType.Application.Json)
            val r = runCatching { markWatched(type, id, s, e) }.getOrElse { """{"ok":false,"linked":true}""" }
            call.respondText(r, ContentType.Application.Json)
        }
    }

    val JS = """
/* ----- Simkl: link the account in Settings, library page, mark watched at 85% ----- */
var simkl={keyed:true,own:false,linked:false},skSent={},skBusy={};
try{skSent=JSON.parse(localStorage.getItem('cncSimklSent')||'{}')}catch(e){skSent={}}
function simklInit(){api('/web/api/simkl/status').then(function(s){simkl=s;g('navsk').classList.toggle('hide',!s.linked);if(s.linked)simklSeen()}).catch(function(){})}
/* Episodes / movies already watched on Simkl are marked watched here too. Only keys not merged before are added, so one
   unmarked by hand on the site stays unmarked. The bridge asks Simkl at most every 5 minutes. */
var skSeenT=null;
function simklSeen(){if(!simkl.linked)return;api('/web/api/simkl/seen').then(function(r){if(!r||!r.linked)return;
 var had={};try{had=JSON.parse(localStorage.getItem('cncSimklSeen')||'{}')}catch(e){had={}}
 var d=S('done',{})||{},n=0,next={};(r.keys||[]).forEach(function(k){next[k]=1;if(!had[k]&&!d[k]){d[k]=1;n++}});
 if(n){Sset('done',d);if(window.refreshMarks)refreshMarks();if(window.contUpd)contUpd()}
 try{localStorage.setItem('cncSimklSeen',JSON.stringify(next))}catch(e){}
}).catch(function(){});clearTimeout(skSeenT);skSeenT=setTimeout(simklSeen,10*60*1000)}
function simklMark(key,c){if(!simkl.linked||!c||(c.type!=='movie'&&c.type!=='tv')||!c.id||skSent[key]||skBusy[key])return;
 if(c.type==='tv'&&(c.s==null||c.e==null))return;skBusy[key]=1;
 var b='type='+c.type+'&id='+encodeURIComponent(c.id)+(c.type==='tv'?'&s='+c.s+'&e='+c.e:'');
 fetch('/web/api/simkl/watched',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json()}).then(function(r){
  delete skBusy[key];if(r.linked===false){simkl.linked=false;g('navsk').classList.add('hide');return}
  if(r.ok){skSent[key]=1;var k=Object.keys(skSent);if(k.length>400)delete skSent[k[0]];try{localStorage.setItem('cncSimklSent',JSON.stringify(skSent))}catch(e){}}
 }).catch(function(){delete skBusy[key]})}
function simklSettings(d){var box=el('div','setr');var t=el('div');t.style.width='100%';t.appendChild(el('b',null,'Simkl'));
 var sm=el('small',null,'');t.appendChild(sm);var area=el('div');area.style.marginTop='8px';t.appendChild(area);box.appendChild(t);d.appendChild(box);
 function draw(){area.textContent='';
  if(simkl.linked){sm.textContent='Linked. Your library is in the Simkl tab, and a movie or episode is marked watched there once 85% of it has played.';
   var u=el('button','quiet','Unlink Simkl');u.type='button';u.onclick=function(){fetch('/web/api/simkl/unlink',{method:'POST',credentials:'same-origin'}).then(function(){simkl.linked=false;g('navsk').classList.add('hide');draw()})};area.appendChild(u);return}
  sm.textContent='Press Link and enter the code Simkl shows. A Client ID is built in; to use your own Simkl app instead (simkl.com/settings/developer), paste its Client ID below (empty = built-in).';
  var row=el('div','btns');var inp=document.createElement('input');inp.type='text';inp.placeholder='Simkl Client ID';inp.autocomplete='off';inp.spellcheck=false;inp.style.flex='1 1 220px';row.appendChild(inp);
  var sv=el('button','quiet','Save own ID');sv.type='button';row.appendChild(sv);area.appendChild(row);
  var msg=el('div','kbd','');area.appendChild(msg);var lk=el('button',null,'Link my Simkl account');lk.type='button';lk.style.marginTop='8px';lk.disabled=!simkl.keyed;area.appendChild(lk);
  if(simkl.own){inp.placeholder='Own Client ID saved (paste another, or save empty for the built-in one)'}else{inp.placeholder='Own Simkl Client ID (optional)'}
  sv.onclick=function(){fetch('/web/api/simkl/config',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'client_id='+encodeURIComponent(inp.value.trim())}).then(function(r){
   if(!r.ok){msg.textContent='That does not look like a Client ID.';return}return r.json().then(function(s){simkl.keyed=!!s.keyed;simkl.own=!!s.own;lk.disabled=!simkl.keyed;msg.textContent=s.own?'Saved: your own app is used.':'Using the built-in app.'})})};
  lk.onclick=function(){lk.disabled=true;msg.textContent='Asking Simkl for a code';
   fetch('/web/api/simkl/pin',{method:'POST',credentials:'same-origin'}).then(function(r){if(!r.ok)throw new Error('pin');return r.json()}).then(function(p){
    msg.textContent='';var a=document.createElement('a');a.href=p.verification_url;a.target='_blank';a.rel='noopener';a.textContent=p.verification_url;a.style.color='var(--lamp)';
    msg.appendChild(document.createTextNode('Open '));msg.appendChild(a);msg.appendChild(document.createTextNode(' and enter the code '));var cb=el('b',null,p.user_code);cb.style.fontSize='18px';msg.appendChild(cb);msg.appendChild(document.createTextNode('. Waiting for you to confirm'));
    var until=Date.now()+(p.expires_in||900)*1000;
    (function poll(){if(!document.body.contains(area)||simkl.linked)return;if(Date.now()>until){msg.textContent='The code expired. Try again.';lk.disabled=false;return}
     fetch('/web/api/simkl/poll?code='+encodeURIComponent(p.user_code),{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(s){if(s.linked){simkl.linked=true;g('navsk').classList.remove('hide');draw()}else setTimeout(poll,(p.interval||5)*1000)}).catch(function(){setTimeout(poll,(p.interval||5)*1000)})})()
   }).catch(function(){msg.textContent='Simkl did not answer (is the Client ID right?). Try again.';lk.disabled=!simkl.keyed})}}
 draw()}
var SK_TABS=[['watching','Watching'],['plantowatch','Plan to watch'],['completed','Completed'],['hold','On hold'],['dropped','Dropped']];
function simklPage(){var my=++xrun;document.title='Simkl - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));
 var head=el('div','tvh');head.appendChild(el('h2',null,'Simkl'));var cnt=el('span','cnt');head.appendChild(cnt);m.appendChild(head);
 var chips=el('div','chips');chips.style.padding='0 4vw 10px';m.appendChild(chips);var info=el('p','msg','Loading');m.appendChild(info);var gr=el('div','grid');m.appendChild(gr);
 var cur=S('skTab','watching'),run=0;
 function load(){var r=++run;gr.textContent='';info.textContent='Loading';info.classList.remove('hide');cnt.textContent='';
  Array.prototype.forEach.call(chips.children,function(c){c.setAttribute('aria-pressed',String(c.dataset.s===cur))});
  api('/web/api/simkl/library?status='+cur).then(function(d){if(my!==xrun||r!==run)return;
   if(!d.linked){info.textContent='Simkl is not linked. Link your account in Settings.';return}
   var it=d.items||[];it.forEach(function(x){gr.appendChild(card(x))});cnt.textContent=it.length+' title(s)';
   info.textContent=it.length?(d.hidden?d.hidden+' title(s) without a TMDB id are not shown.':''):(d.failed?'Simkl did not answer. Try again in a moment.':'Nothing in this list.');if(!info.textContent)info.classList.add('hide')
  }).catch(function(e){if(e.message!=='auth'&&r===run)info.textContent='Could not load your Simkl library. Try again.'})}
 SK_TABS.forEach(function(t){var c=el('button','chip',t[1]);c.type='button';c.dataset.s=t[0];c.onclick=function(){cur=t[0];Sset('skTab',cur);load()};chips.appendChild(c)});
 load()}
"""
}

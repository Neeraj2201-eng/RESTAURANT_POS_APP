package com.restopos.app

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Areas and their tables / rooms, in the order shown in the dropdown. */
object Areas {
    val all: LinkedHashMap<String, List<String>> = linkedMapOf(
        "Dining area" to (1..8).map { "T$it" },
        "Ground Floor" to (101..107).map { "R-$it" },
        "First Floor" to (201..219).map { "R-$it" }
    )
}

val PAY_MODES = listOf("Cash", "UPI", "Room", "Pending")

/** Business day runs from 2:00 AM to 2:00 AM, so a 1 AM sale belongs to the previous day. */
fun bizDayKey(t: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t - 2L * 3600L * 1000L))

fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0

data class MenuItem(
    val id: Long,
    val name: String,
    val price: Double,
    val category: String
)

data class OrderLine(
    val itemId: Long,
    val name: String,
    val price: Double,
    val qty: Int,
    val ord: Long = 0L
)

/** One settled bill, kept for daily / monthly reports. */
data class Sale(
    val id: Long,
    val time: Long,
    val dayKey: String,
    val billNo: Int,
    val table: String,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val mode: String,
    val paid: Double,
    val customer: String,
    val lines: List<OrderLine>
) {
    val balance: Double get() = maxOf(0.0, r2(total - paid))
}

// ---------- JSON helpers (same format locally and in the cloud) ----------

private fun linesToJson(l: List<OrderLine>): JSONObject {
    val o = JSONObject()
    l.forEach {
        o.put(
            "i${it.itemId}",
            JSONObject().put("id", it.itemId).put("n", it.name).put("p", it.price)
                .put("q", it.qty).put("o", it.ord)
        )
    }
    return o
}

private fun jsonToLines(o: JSONObject?): List<OrderLine> {
    if (o == null) return emptyList()
    val out = ArrayList<OrderLine>()
    for (k in o.keys()) {
        val x = o.optJSONObject(k) ?: continue
        out.add(OrderLine(x.getLong("id"), x.getString("n"), x.getDouble("p"), x.getInt("q"), x.optLong("o", 0L)))
    }
    return out.sortedBy { it.ord }
}

private fun menuToJson(m: List<MenuItem>): JSONObject {
    val o = JSONObject()
    m.forEach {
        o.put("m${it.id}", JSONObject().put("id", it.id).put("n", it.name).put("p", it.price).put("c", it.category))
    }
    return o
}

private fun jsonToMenu(o: JSONObject): List<MenuItem> {
    val out = ArrayList<MenuItem>()
    for (k in o.keys()) {
        val x = o.optJSONObject(k) ?: continue
        out.add(MenuItem(x.getLong("id"), x.getString("n"), x.getDouble("p"), x.optString("c", "General")))
    }
    return out.sortedBy { it.id }
}

private fun saleToJson(s: Sale): JSONObject =
    JSONObject().put("id", s.id).put("t", s.time).put("d", s.dayKey).put("b", s.billNo)
        .put("tb", s.table).put("s", s.subtotal).put("x", s.tax).put("tt", s.total)
        .put("m", s.mode).put("pd", s.paid).put("c", s.customer).put("l", linesToJson(s.lines))

private fun saleFromJson(x: JSONObject): Sale {
    val t = x.getLong("t")
    return Sale(
        x.getLong("id"), t, x.optString("d", bizDayKey(t)), x.optInt("b", 0), x.optString("tb", ""),
        x.optDouble("s", 0.0), x.optDouble("x", 0.0), x.optDouble("tt", 0.0),
        x.optString("m", "Cash"), x.optDouble("pd", x.optDouble("tt", 0.0)), x.optString("c", ""),
        jsonToLines(x.optJSONObject("l"))
    )
}

/**
 * Holds everything the app needs (menu, open orders per table, sales, settings).
 * Everything is saved on the phone. If a Firebase database URL is set in Settings,
 * orders, menu, shared settings, bill numbers and sales are also synced between devices.
 */
class PosStore(context: Context) {
    private val prefs = context.getSharedPreferences("restopos", Context.MODE_PRIVATE)

    val menu = mutableStateListOf<MenuItem>()
    val orders = mutableStateMapOf<String, List<OrderLine>>()
    val sales = mutableStateListOf<Sale>()

    var area by mutableStateOf(Areas.all.keys.first())

    var restaurantName by mutableStateOf("MY RESTAURANT")
    var address by mutableStateOf("")
    var phone by mutableStateOf("")
    var footer by mutableStateOf("Thank you! Visit again")
    var currency by mutableStateOf("Rs")
    var taxPercent by mutableStateOf(0.0)
    var charsPerLine by mutableStateOf(32) // 32 = 2 inch (58mm) printer
    var printerAddress by mutableStateOf("")
    var printerName by mutableStateOf("")
    var billNo by mutableStateOf(1)

    var cloudUrl by mutableStateOf("")
    var cloudAuth by mutableStateOf("")
    var syncStatus by mutableStateOf("Local only")

    @Volatile var active = true

    // ---- sync internals ----
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val dirtyTables = ConcurrentHashMap<String, Int>()
    private val menuVer = AtomicInteger(0)
    private val menuPushed = AtomicInteger(0)
    private val unsent = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var gen = 0
    private var etag: String? = null
    private var lastRemoteSettings: String? = null

    init {
        load()
        startSync()
    }

    // ---------- formatting ----------
    fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    fun money(v: Double): String = "$currency ${fmt2(v)}"

    // ---------- order operations ----------
    fun addItem(table: String, item: MenuItem) {
        val cur = orders[table].orEmpty()
        val idx = cur.indexOfFirst { it.itemId == item.id }
        orders[table] = if (idx >= 0) {
            cur.toMutableList().also { it[idx] = it[idx].copy(qty = it[idx].qty + 1) }
        } else {
            cur + OrderLine(item.id, item.name, item.price, 1, System.currentTimeMillis())
        }
        touchTable(table)
    }

    fun changeQty(table: String, itemId: Long, delta: Int) {
        val updated = orders[table].orEmpty().mapNotNull {
            if (it.itemId == itemId) {
                val q = it.qty + delta
                if (q <= 0) null else it.copy(qty = q)
            } else it
        }
        setOrder(table, updated)
    }

    fun removeLine(table: String, itemId: Long) {
        setOrder(table, orders[table].orEmpty().filter { it.itemId != itemId })
    }

    private fun setOrder(table: String, lines: List<OrderLine>) {
        if (lines.isEmpty()) orders.remove(table) else orders[table] = lines
        touchTable(table)
    }

    private fun touchTable(table: String) {
        save()
        pushTable(table)
    }

    fun subtotal(table: String): Double = r2(orders[table].orEmpty().sumOf { it.price * it.qty })

    fun tax(table: String): Double = r2(subtotal(table) * taxPercent / 100.0)

    fun total(table: String): Double = r2(subtotal(table) + tax(table))

    fun itemCount(table: String): Int = orders[table].orEmpty().sumOf { it.qty }

    /** Close the table after payment, record the sale, and move to the next bill number. */
    fun settle(table: String, mode: String, paidIn: Double, customer: String) {
        val lines = orders[table].orEmpty()
        if (lines.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val sub = subtotal(table)
            val t = tax(table)
            val total = r2(sub + t)
            val paid = r2(minOf(maxOf(paidIn, 0.0), total))
            val sale = Sale(
                now * 1000L + (0..999).random(), now, bizDayKey(now), billNo, table,
                sub, t, total, mode, paid, customer.trim(), lines
            )
            sales.add(sale)
            saveSales()
            if (cloudUrl.isNotBlank()) {
                unsent.add(sale.id)
                saveUnsent()
                bg { flushUnsent(it) }
            }
        }
        orders.remove(table)
        billNo += 1
        save()
        pushTable(table)
        pushBillNo()
    }

    fun deleteSale(s: Sale) {
        sales.removeAll { it.id == s.id }
        unsent.remove(s.id)
        saveSales()
        saveUnsent()
        bg { it.put("sales/${s.dayKey}/${s.id}", "null") }
    }

    // ---------- menu operations ----------
    fun addMenuItem(name: String, price: Double, category: String) {
        val id = (menu.maxOfOrNull { it.id } ?: 0L) + 1L
        menu.add(MenuItem(id, name, price, category))
        menuChanged()
    }

    fun updateMenuItem(id: Long, name: String, price: Double, category: String) {
        val i = menu.indexOfFirst { it.id == id }
        if (i >= 0) {
            menu[i] = MenuItem(id, name, price, category)
            menuChanged()
        }
    }

    fun deleteMenuItem(id: Long) {
        menu.removeAll { it.id == id }
        menuChanged()
    }

    private fun menuChanged() {
        save()
        if (cloudUrl.isNotBlank()) {
            menuVer.incrementAndGet()
            bg { tryPushMenu(it) }
        }
    }

    // ---------- cloud sync ----------
    private fun rtdb(): Rtdb? = if (cloudUrl.isBlank()) null else Rtdb(cloudUrl, cloudAuth)

    private fun bg(block: (Rtdb) -> Unit) {
        val rt = rtdb() ?: return
        io.execute {
            try {
                synchronized(lock) { block(rt) }
            } catch (e: Exception) {
                syncStatus = "Offline - will retry"
            }
        }
    }

    private fun pushTable(table: String) {
        if (cloudUrl.isBlank()) return
        dirtyTables.merge(table, 1) { a, b -> a + b }
        bg { tryPushTable(it, table) }
    }

    private fun tryPushTable(rt: Rtdb, table: String) {
        val ver = dirtyTables[table] ?: return
        val lines = orders[table].orEmpty()
        val body = if (lines.isEmpty()) "null" else linesToJson(lines).toString()
        rt.put("live/orders/$table", body)
        dirtyTables.remove(table, ver)
    }

    private fun tryPushMenu(rt: Rtdb) {
        val v = menuVer.get()
        rt.put("live/menu", menuToJson(menu.toList()).toString())
        menuPushed.set(v)
    }

    private fun pushBillNo() {
        bg { it.put("live/billNo", billNo.toString()) }
    }

    private fun settingsJson(): JSONObject =
        JSONObject().put("n", restaurantName).put("a", address).put("p", phone)
            .put("f", footer).put("c", currency).put("t", taxPercent)

    /** Share name / address / phone / footer / currency / tax with the other devices. */
    fun pushSettings() {
        save()
        val js = settingsJson()
        lastRemoteSettings = js.toString()
        bg { it.put("live/settings", js.toString()) }
    }

    private fun flushDirty(rt: Rtdb) {
        for (k in dirtyTables.keys.toList()) tryPushTable(rt, k)
        if (menuVer.get() != menuPushed.get()) tryPushMenu(rt)
    }

    private fun flushUnsent(rt: Rtdb) {
        for (id in unsent.toList()) {
            val s = sales.firstOrNull { it.id == id }
            if (s == null) {
                unsent.remove(id)
                continue
            }
            rt.put("sales/${s.dayKey}/${s.id}", saleToJson(s).toString())
            unsent.remove(id)
        }
        saveUnsent()
    }

    /** Called when the database URL / secret is saved in Settings. */
    fun setCloud(url: String, auth: String) {
        val u = url.trim().trimEnd('/')
        val wasBlank = cloudUrl.isBlank()
        cloudUrl = u
        cloudAuth = auth.trim()
        save()
        if (u.isNotBlank() && wasBlank) {
            sales.forEach { unsent.add(it.id) }
            saveUnsent()
        }
        lastRemoteSettings = null
        startSync()
    }

    private fun startSync() {
        gen++
        val my = gen
        etag = null
        if (cloudUrl.isBlank()) {
            syncStatus = "Local only"
            return
        }
        syncStatus = "Connecting..."
        thread(isDaemon = true) {
            while (my == gen) {
                if (active) {
                    try {
                        val rt = rtdb() ?: break
                        synchronized(lock) {
                            flushDirty(rt)
                            val res = rt.get("live", etag)
                            if (res.code == 200) {
                                etag = res.etag
                                applyRemote(res.body)
                            }
                            flushUnsent(rt)
                        }
                        syncStatus = "Synced"
                    } catch (e: Exception) {
                        syncStatus = "Offline: ${e.message ?: "no connection"}"
                    }
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    private fun applyRemote(body: String) {
        val root = if (body.isBlank() || body == "null") JSONObject() else JSONObject(body)
        var changed = false

        // orders (one entry per table / room)
        val ro = root.optJSONObject("orders")
        val seen = HashSet<String>()
        if (ro != null) {
            for (k in ro.keys()) {
                seen.add(k)
                if (dirtyTables.containsKey(k)) continue
                val lines = jsonToLines(ro.optJSONObject(k))
                if (lines.isEmpty()) {
                    if (orders.containsKey(k)) {
                        orders.remove(k)
                        changed = true
                    }
                } else if (orders[k] != lines) {
                    orders[k] = lines
                    changed = true
                }
            }
        }
        for (k in orders.keys.toList()) {
            if (k !in seen && !dirtyTables.containsKey(k)) {
                orders.remove(k)
                changed = true
            }
        }

        // menu
        val rm = root.optJSONObject("menu")
        if (rm != null) {
            if (menuVer.get() == menuPushed.get()) {
                val nm = jsonToMenu(rm)
                if (nm != menu.toList()) {
                    menu.clear()
                    menu.addAll(nm)
                    changed = true
                }
            }
        } else if (menu.isNotEmpty()) {
            menuVer.incrementAndGet()
        }

        // shared settings
        val rs = root.optJSONObject("settings")
        if (rs != null) {
            val str = rs.toString()
            if (str != lastRemoteSettings) {
                lastRemoteSettings = str
                restaurantName = rs.optString("n", restaurantName)
                address = rs.optString("a", address)
                phone = rs.optString("p", phone)
                footer = rs.optString("f", footer)
                currency = rs.optString("c", currency)
                taxPercent = rs.optDouble("t", taxPercent)
                changed = true
            }
        } else {
            pushSettings()
        }

        // bill number (never goes backwards)
        val rb = if (root.has("billNo")) root.optInt("billNo", -1) else -1
        if (rb > billNo) {
            billNo = rb
            changed = true
        } else if (rb < billNo) {
            pushBillNo()
        }

        if (changed) save()
    }

    /** Loads the sales of one month ("yyyy-MM") from the cloud and merges them in. */
    fun fetchMonth(monthKey: String) {
        val rt = rtdb() ?: return
        try {
            val res = rt.get(
                "sales", null,
                "orderBy=%22%24key%22&startAt=%22$monthKey-01%22&endAt=%22$monthKey-31%22"
            )
            val remote = ArrayList<Sale>()
            if (res.body.isNotBlank() && res.body != "null") {
                val root = JSONObject(res.body)
                for (d in root.keys()) {
                    val day = root.optJSONObject(d) ?: continue
                    for (k in day.keys()) {
                        day.optJSONObject(k)?.let { remote.add(saleFromJson(it)) }
                    }
                }
            }
            synchronized(lock) {
                val ids = remote.map { it.id }.toHashSet()
                sales.removeAll { it.dayKey.startsWith(monthKey) && it.id !in ids && !unsent.contains(it.id) }
                val have = sales.map { it.id }.toHashSet()
                remote.filter { it.id !in have }.forEach { sales.add(it) }
            }
            saveSales()
        } catch (e: Exception) {
        }
    }

    // ---------- local storage ----------
    private fun saveUnsent() {
        prefs.edit().putString("unsent", unsent.joinToString(",")).apply()
    }

    private fun saveSales() {
        val a = JSONArray()
        sales.toList().forEach { a.put(saleToJson(it)) }
        prefs.edit().putString("sales_v2", a.toString()).apply()
    }

    fun save() {
        val o = JSONObject()
        orders.toMap().forEach { (table, lines) ->
            if (lines.isNotEmpty()) o.put(table, linesToJson(lines))
        }
        prefs.edit()
            .putString("menu_v2", menuToJson(menu.toList()).toString())
            .putString("orders_v2", o.toString())
            .putString("name", restaurantName)
            .putString("address", address)
            .putString("phone", phone)
            .putString("footer", footer)
            .putString("currency", currency)
            .putFloat("tax", taxPercent.toFloat())
            .putInt("width", charsPerLine)
            .putString("printerAddress", printerAddress)
            .putString("printerName", printerName)
            .putInt("billNo", billNo)
            .putString("cloudUrl", cloudUrl)
            .putString("cloudAuth", cloudAuth)
            .apply()
    }

    private fun load() {
        restaurantName = prefs.getString("name", restaurantName) ?: restaurantName
        address = prefs.getString("address", address) ?: address
        phone = prefs.getString("phone", phone) ?: phone
        footer = prefs.getString("footer", footer) ?: footer
        currency = prefs.getString("currency", currency) ?: currency
        taxPercent = prefs.getFloat("tax", 0f).toDouble()
        charsPerLine = prefs.getInt("width", charsPerLine)
        printerAddress = prefs.getString("printerAddress", "") ?: ""
        printerName = prefs.getString("printerName", "") ?: ""
        billNo = prefs.getInt("billNo", 1)
        cloudUrl = prefs.getString("cloudUrl", "") ?: ""
        cloudAuth = prefs.getString("cloudAuth", "") ?: ""

        prefs.getString("unsent", "")?.split(",")?.forEach { s ->
            s.toLongOrNull()?.let { unsent.add(it) }
        }

        try {
            val ms = prefs.getString("menu_v2", null)
            if (ms == null) seedMenu() else menu.addAll(jsonToMenu(JSONObject(ms)))
            val os = prefs.getString("orders_v2", null)
            if (os != null) {
                val o = JSONObject(os)
                for (k in o.keys()) {
                    val lines = jsonToLines(o.optJSONObject(k))
                    if (lines.isNotEmpty()) orders[k] = lines
                }
            }
            val ss = prefs.getString("sales_v2", null)
            if (ss != null) {
                val a = JSONArray(ss)
                for (i in 0 until a.length()) sales.add(saleFromJson(a.getJSONObject(i)))
            }
        } catch (e: Exception) {
            if (menu.isEmpty()) seedMenu()
        }
    }

    private fun seedMenu() {
        val seed = listOf(
            Triple("Tea", 15.0, "Beverages"),
            Triple("Coffee", 20.0, "Beverages"),
            Triple("Cold Drink", 30.0, "Beverages"),
            Triple("Water Bottle", 20.0, "Beverages"),
            Triple("Veg Sandwich", 60.0, "Snacks"),
            Triple("Paneer Tikka", 180.0, "Starters"),
            Triple("Dal Fry", 120.0, "Main Course"),
            Triple("Veg Biryani", 150.0, "Main Course"),
            Triple("Chicken Biryani", 220.0, "Main Course"),
            Triple("Butter Naan", 35.0, "Breads")
        )
        seed.forEachIndexed { i, t -> menu.add(MenuItem((i + 1).toLong(), t.first, t.second, t.third)) }
        save()
    }
}

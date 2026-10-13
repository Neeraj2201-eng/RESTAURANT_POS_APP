@file:OptIn(ExperimentalMaterial3Api::class)

package com.restopos.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private fun timeStr(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
private fun monthLabel(c: Calendar): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
private fun csv(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
private fun linesText(s: Sale): String = s.lines.joinToString("; ") { "${it.qty}x ${it.name}" }

private class Sum(val bills: Int, val sub: Double, val tax: Double, val total: Double,
                  val cash: Double, val upi: Double, val room: Double, val due: Double)

private fun sum(l: List<Sale>) = Sum(
    l.size,
    l.sumOf { it.subtotal }, l.sumOf { it.tax }, l.sumOf { it.total },
    l.filter { it.mode == "Cash" }.sumOf { it.paid },
    l.filter { it.mode == "UPI" }.sumOf { it.paid },
    l.filter { it.mode == "Room" }.sumOf { it.paid },
    l.sumOf { it.balance }
)

/** Builds a CSV report (opens in Excel / Google Sheets). */
private fun buildCsv(store: PosStore, title: String, sales: List<Sale>, withDaily: Boolean): String {
    val f = store::fmt2
    val sb = StringBuilder("﻿")
    sb.append(csv(store.restaurantName)).append("\n")
    sb.append(csv(title)).append("\n")
    sb.append("Business day = 2:00 AM to 2:00 AM\n\n")

    if (withDaily) {
        sb.append("DAILY SALES\n")
        sb.append("Date,Bills,Total,Cash,UPI,Room,Balance due\n")
        sales.groupBy { it.dayKey }.toSortedMap().forEach { (d, list) ->
            val s = sum(list)
            sb.append("$d,${s.bills},${f(s.total)},${f(s.cash)},${f(s.upi)},${f(s.room)},${f(s.due)}\n")
        }
        val s = sum(sales)
        sb.append("TOTAL,${s.bills},${f(s.total)},${f(s.cash)},${f(s.upi)},${f(s.room)},${f(s.due)}\n\n")
    } else {
        val s = sum(sales)
        sb.append("SUMMARY\n")
        sb.append("Bills,Total,Cash,UPI,Room,Balance due\n")
        sb.append("${s.bills},${f(s.total)},${f(s.cash)},${f(s.upi)},${f(s.room)},${f(s.due)}\n\n")
    }

    sb.append("BILLS\n")
    sb.append("Business day,Time,Bill No,Table,Items,Subtotal,Tax,Total,Mode,Paid,Balance,Customer\n")
    sales.sortedBy { it.time }.forEach { s ->
        sb.append("${s.dayKey},${timeStr(s.time)},${s.billNo},${csv(s.table)},${csv(linesText(s))},")
            .append("${f(s.subtotal)},${f(s.tax)},${f(s.total)},${s.mode},${f(s.paid)},${f(s.balance)},${csv(s.customer)}\n")
    }

    sb.append("\nITEM-WISE SALES\n")
    sb.append("Item,Qty,Amount\n")
    val items = LinkedHashMap<String, Pair<Int, Double>>()
    for (s in sales) for (l in s.lines) {
        val old = items[l.name] ?: (0 to 0.0)
        items[l.name] = (old.first + l.qty) to (old.second + l.qty * l.price)
    }
    items.entries.sortedByDescending { it.value.second }.forEach { (n, v) ->
        sb.append("${csv(n)},${v.first},${f(v.second)}\n")
    }
    return sb.toString()
}

/** Saves into the phone's Downloads folder (Android 10+). Returns true on success. */
private fun saveToDownloads(ctx: Context, name: String, text: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    } catch (e: Exception) {
        false
    }
}

private fun shareCsv(ctx: Context, name: String, text: String) {
    try {
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, name)
        f.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not share: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun download(ctx: Context, name: String, text: String) {
    if (saveToDownloads(ctx, name, text)) {
        Toast.makeText(ctx, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
    } else {
        shareCsv(ctx, name, text) // older phones: choose where to save
    }
}

@Composable
private fun SumCard(store: PosStore, s: Sum) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            RowKV("Bills", s.bills.toString())
            RowKV("Total sales", store.money(s.total), bold = true)
            RowKV("Cash received", store.money(s.cash))
            RowKV("UPI received", store.money(s.upi))
            RowKV("Room charged", store.money(s.room))
            RowKV("Pending / balance due", store.money(s.due))
            if (s.tax > 0) RowKV("(Tax included: ${store.money(s.tax)})", "")
        }
    }
}

@Composable
fun ReportsScreen(store: PosStore) {
    val ctx = LocalContext.current
    val withBt = rememberWithBtPermission()
    var month by remember {
        mutableStateOf(Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() - 2L * 3600L * 1000L // business-day month
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        })
    }
    var day by remember { mutableStateOf<String?>(null) }
    var deleteSale by remember { mutableStateOf<Sale?>(null) }

    val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(month.time)
    val today = bizDayKey(System.currentTimeMillis())

    // Pull this month's sales from the cloud (other devices' bills) while the screen is open.
    DisposableEffect(monthKey, store.cloudUrl) {
        var alive = true
        val t = thread(isDaemon = true) {
            try {
                while (alive) {
                    store.fetchMonth(monthKey)
                    Thread.sleep(15000)
                }
            } catch (e: InterruptedException) {
            }
        }
        onDispose {
            alive = false
            t.interrupt()
        }
    }

    val monthSales = store.sales.filter { it.dayKey.startsWith(monthKey) }
    val byDay = monthSales.groupBy { it.dayKey }

    val selected = day
    BackHandler(enabled = selected != null) { day = null }

    Column(Modifier.fillMaxSize()) {
        AppBar(if (selected == null) "Sales report" else selected, if (selected == null) null else ({ day = null }))

        if (selected == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { month = (month.clone() as Calendar).apply { add(Calendar.MONTH, -1) } }) {
                    Text("<  Prev", fontSize = 16.sp)
                }
                Text(monthLabel(month), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = { month = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) } }) {
                    Text("Next  >", fontSize = 16.sp)
                }
            }
            Text(
                "Business day: 2:00 AM to 2:00 AM",
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            SumCard(store, sum(monthSales))
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (monthSales.isEmpty()) Toast.makeText(ctx, "No sales this month", Toast.LENGTH_SHORT).show()
                        else download(ctx, "sales-$monthKey.csv", buildCsv(store, "Monthly report ${monthLabel(month)}", monthSales, true))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Download month") }
                OutlinedButton(
                    onClick = {
                        if (monthSales.isEmpty()) Toast.makeText(ctx, "No sales this month", Toast.LENGTH_SHORT).show()
                        else shareCsv(ctx, "sales-$monthKey.csv", buildCsv(store, "Monthly report ${monthLabel(month)}", monthSales, true))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
            }
            Text(
                "Daily sales (tap a day for its bills and report)",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            if (byDay.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No sales in this month.\nSales appear after 'Settle & clear'.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(byDay.keys.sortedDescending(), key = { it }) { d ->
                        val list = byDay[d].orEmpty()
                        val s = sum(list)
                        ListItem(
                            headlineContent = {
                                Text(if (d == today) "$d  (today)" else d, fontWeight = FontWeight.Medium)
                            },
                            supportingContent = {
                                Text("${s.bills} bills | Cash ${store.fmt(s.cash)} | UPI ${store.fmt(s.upi)} | Room ${store.fmt(s.room)} | Due ${store.fmt(s.due)}")
                            },
                            trailingContent = { Text(store.money(s.total), fontWeight = FontWeight.Bold) },
                            modifier = Modifier.clickable { day = d }
                        )
                        HorizontalDivider()
                    }
                }
            }
        } else {
            val list = byDay[selected].orEmpty().sortedByDescending { it.time }
            Text(
                "Business day $selected: 2:00 AM to next day 2:00 AM",
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
            SumCard(store, sum(list))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { download(ctx, "sales-$selected.csv", buildCsv(store, "Daily report $selected", list, false)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Download") }
                OutlinedButton(
                    onClick = { shareCsv(ctx, "sales-$selected.csv", buildCsv(store, "Daily report $selected", list, false)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
                OutlinedButton(
                    onClick = {
                        if (store.printerAddress.isBlank()) {
                            Toast.makeText(ctx, "Choose a printer in Settings first", Toast.LENGTH_LONG).show()
                        } else {
                            val bytes = EscPos.summary(store, selected, list)
                            val address = store.printerAddress
                            withBt { BtPrinter.printAsync(ctx, address, bytes) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Print") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text("Bill ${s.billNo} | ${s.table} | ${timeStr(s.time)}") },
                        supportingContent = {
                            Text(buildString {
                                append(linesText(s)).append("\n")
                                append(s.mode).append(" paid ").append(store.fmt2(s.paid))
                                if (s.balance > 0) append("  |  DUE ").append(store.fmt2(s.balance))
                                if (s.customer.isNotBlank()) append("  |  ").append(s.customer)
                            })
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(store.money(s.total), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { deleteSale = s }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete bill")
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleteSale?.let { sale ->
        AlertDialog(
            onDismissRequest = { deleteSale = null },
            title = { Text("Delete bill ${sale.billNo}?") },
            text = { Text("It will no longer count in the daily and monthly totals (on all devices).") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteSale(sale)
                    deleteSale = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteSale = null }) { Text("Cancel") } }
        )
    }
}

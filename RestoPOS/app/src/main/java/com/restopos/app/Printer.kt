package com.restopos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/** Sends raw bytes to a paired Bluetooth (classic SPP) thermal printer. */
@SuppressLint("MissingPermission")
object BtPrinter {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val main = Handler(Looper.getMainLooper())

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isEnabled(ctx: Context): Boolean = adapter(ctx)?.isEnabled == true

    fun pairedDevices(ctx: Context): List<BluetoothDevice> =
        adapter(ctx)?.bondedDevices?.toList() ?: emptyList()

    /** Prints on a background thread and shows a toast with the result. */
    fun printAsync(ctx: Context, address: String, data: ByteArray) {
        val app = ctx.applicationContext
        Toast.makeText(app, "Printing...", Toast.LENGTH_SHORT).show()
        thread {
            val msg = try {
                send(app, address, data)
                "Bill sent to printer"
            } catch (e: Exception) {
                "Print failed: ${e.message ?: "make sure the printer is on and in range"}"
            }
            main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun connect(device: BluetoothDevice): BluetoothSocket {
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP)
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                try {
                    s.close()
                } catch (e2: IOException) {
                }
            }
        } catch (e: IOException) {
        }
        // Fallback that works on many cheap printers
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        val s2 = m.invoke(device, 1) as BluetoothSocket
        s2.connect()
        return s2
    }

    private fun send(ctx: Context, address: String, data: ByteArray) {
        val ad = adapter(ctx) ?: throw IOException("Bluetooth not available")
        if (!ad.isEnabled) throw IOException("Bluetooth is turned off")
        val device = ad.getRemoteDevice(address)
        ad.cancelDiscovery()
        val socket = connect(device)
        try {
            val os = socket.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(512, data.size - i)
                os.write(data, i, n)
                os.flush()
                i += n
                Thread.sleep(40)
            }
            Thread.sleep(800) // let the printer finish before closing
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }
}

/** Builds ESC/POS byte streams for 58mm (2 inch) and 80mm printers. */
object EscPos {
    private fun clean(t: String): String =
        t.replace("₹", "Rs").map { if (it.code in 32..126) it else '?' }.joinToString("")

    private fun lr(left: String, right: String, w: Int): String {
        val maxL = maxOf(w - right.length - 1, 0)
        val l = if (left.length > maxL) left.take(maxL) else left
        return l + " ".repeat(maxOf(w - l.length - right.length, 1)) + right
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun raw(vararg b: Int) {
            for (x in b) out.write(x)
        }

        fun line(t: String = "") {
            val bytes = clean(t).toByteArray(Charsets.ISO_8859_1)
            out.write(bytes, 0, bytes.size)
            out.write(0x0A)
        }

        fun center(on: Boolean) = raw(0x1B, 0x61, if (on) 1 else 0)
        fun bold(on: Boolean) = raw(0x1B, 0x45, if (on) 1 else 0)
        fun size(n: Int) = raw(0x1D, 0x21, n)
        fun feed(n: Int) = raw(0x1B, 0x64, n)
    }

    fun bill(s: PosStore, table: String): ByteArray {
        val w = s.charsPerLine
        val sep = "-".repeat(w)
        val p = Writer()

        p.raw(0x1B, 0x40) // initialise printer
        p.center(true)
        p.bold(true)
        p.size(0x01) // double height
        p.line(s.restaurantName.uppercase())
        p.size(0x00)
        p.bold(false)
        for (a in s.address.lines()) if (a.isNotBlank()) p.line(a.trim())
        if (s.phone.isNotBlank()) p.line("Ph: ${s.phone}")
        p.center(false)

        p.line(sep)
        p.line(lr("Bill No: ${s.billNo}", "Table: $table", w))
        p.line(SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date()))
        p.line(sep)
        p.line(lr("Item", "Amount(${s.currency})", w))
        p.line(sep)

        for (l in s.orders[table].orEmpty()) {
            l.name.chunked(w).forEach { p.line(it) }
            p.line(lr("  ${l.qty} x ${s.fmt2(l.price)}", s.fmt2(l.price * l.qty), w))
        }

        p.line(sep)
        val sub = s.subtotal(table)
        val tax = s.tax(table)
        p.line(lr("Subtotal", s.fmt2(sub), w))
        if (s.taxPercent > 0) p.line(lr("Tax ${s.fmt(s.taxPercent)}%", s.fmt2(tax), w))
        p.line(sep)
        p.bold(true)
        p.size(0x11) // double width + height (half the characters per line)
        p.line(lr("TOTAL", s.fmt2(sub + tax), w / 2))
        p.size(0x00)
        p.bold(false)
        p.line(sep)

        p.center(true)
        if (s.footer.isNotBlank()) p.line(s.footer)
        p.center(false)
        p.feed(4)
        return p.out.toByteArray()
    }

    /** Short end-of-day summary slip. */
    fun summary(s: PosStore, title: String, sales: List<Sale>): ByteArray {
        val w = s.charsPerLine
        val sep = "-".repeat(w)
        val p = Writer()
        p.raw(0x1B, 0x40)
        p.center(true)
        p.bold(true)
        p.line(s.restaurantName.uppercase())
        p.line("SALES SUMMARY")
        p.bold(false)
        p.line(title)
        p.center(false)
        p.line(sep)
        val items = LinkedHashMap<String, Pair<Int, Double>>()
        for (sale in sales) for (l in sale.lines) {
            val old = items[l.name] ?: (0 to 0.0)
            items[l.name] = (old.first + l.qty) to (old.second + l.qty * l.price)
        }
        p.line(lr("Item", "Qty   Amount", w))
        p.line(sep)
        for ((name, v) in items) {
            p.line(lr(name, "${v.first}  ${s.fmt2(v.second)}", w))
        }
        p.line(sep)
        p.line(lr("Bills", sales.size.toString(), w))
        p.line(lr("Subtotal", s.fmt2(sales.sumOf { it.subtotal }), w))
        p.line(lr("Tax", s.fmt2(sales.sumOf { it.tax }), w))
        p.bold(true)
        p.line(lr("TOTAL (${s.currency})", s.fmt2(sales.sumOf { it.total }), w))
        p.bold(false)
        p.line(sep)
        for (m in listOf("Cash", "UPI", "Room")) {
            p.line(lr(m, s.fmt2(sales.filter { it.mode == m }.sumOf { it.paid }), w))
        }
        p.line(lr("Pending", s.fmt2(sales.sumOf { it.balance }), w))
        p.feed(4)
        return p.out.toByteArray()
    }

    fun test(s: PosStore): ByteArray {
        val w = s.charsPerLine
        val p = Writer()
        p.raw(0x1B, 0x40)
        p.center(true)
        p.bold(true)
        p.line("PRINTER TEST")
        p.bold(false)
        p.line(s.restaurantName)
        p.center(false)
        p.line("-".repeat(w))
        p.line((1..w).joinToString("") { (it % 10).toString() })
        p.line("Width: $w characters per line")
        p.line("If this line fits exactly, your")
        p.line("paper width setting is correct.")
        p.feed(4)
        return p.out.toByteArray()
    }
}

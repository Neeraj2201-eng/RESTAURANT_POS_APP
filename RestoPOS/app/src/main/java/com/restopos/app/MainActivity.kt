@file:OptIn(ExperimentalMaterial3Api::class)

package com.restopos.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

typealias Act = () -> Unit

class MainActivity : ComponentActivity() {
    private lateinit var posStore: PosStore

    override fun onStart() {
        super.onStart()
        if (::posStore.isInitialized) posStore.active = true
    }

    override fun onStop() {
        super.onStop()
        if (::posStore.isInitialized) posStore.active = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PosStore(applicationContext)
        posStore = store
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFB23A1C),
                    onPrimary = Color.White,
                    secondary = Color(0xFF2E7D32)
                )
            ) {
                Surface(Modifier.fillMaxSize()) { PosApp(store) }
            }
        }
    }
}

sealed class Screen {
    data object Tables : Screen()
    data object MenuMgr : Screen()
    data object Reports : Screen()
    data object Settings : Screen()
    data class Order(val table: String) : Screen()
}

@Composable
fun PosApp(store: PosStore) {
    var screen by remember { mutableStateOf<Screen>(Screen.Tables) }
    val current = screen

    if (current is Screen.Order) {
        BackHandler { screen = Screen.Tables }
        OrderScreen(store, current.table) { screen = Screen.Tables }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = current is Screen.Tables,
                        onClick = { screen = Screen.Tables },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Tables") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.MenuMgr,
                        onClick = { screen = Screen.MenuMgr },
                        icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                        label = { Text("Menu") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.Reports,
                        onClick = { screen = Screen.Reports },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text("Reports") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.Settings,
                        onClick = { screen = Screen.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    is Screen.Tables -> TablesScreen(store) { screen = Screen.Order(it) }
                    is Screen.MenuMgr -> MenuScreen(store)
                    is Screen.Reports -> ReportsScreen(store)
                    is Screen.Settings -> SettingsScreen(store)
                    else -> {}
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Shared helpers
// ------------------------------------------------------------------

@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White
        )
    )
}

/** Runs [action] after making sure the Bluetooth permission is granted (Android 12+). */
@Composable
fun rememberWithBtPermission(): (Act) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Act?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted) {
            p?.invoke()
        } else {
            Toast.makeText(ctx, "Bluetooth permission is needed to print", Toast.LENGTH_LONG).show()
        }
    }
    return { action: Act ->
        val needs = Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needs) {
            pending = action
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            action()
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun RowKV(k: String, v: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = if (bold) 18.sp else 15.sp)
        Text(v, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = if (bold) 18.sp else 15.sp)
    }
}

// ------------------------------------------------------------------
// Tables
// ------------------------------------------------------------------

@Composable
fun TablesScreen(store: PosStore, onOpen: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val tables = Areas.all[store.area].orEmpty()
    Column(Modifier.fillMaxSize()) {
        AppBar(store.restaurantName.ifBlank { "Tables" })
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("${store.area}   \u25BC", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Areas.all.forEach { (name, list) ->
                    val busy = list.count { store.orders[it].orEmpty().isNotEmpty() }
                    DropdownMenuItem(
                        text = { Text("$name  ($busy occupied)") },
                        onClick = {
                            store.area = name
                            menuOpen = false
                        }
                    )
                }
            }
        }
        Text(
            "\u25CF ${store.syncStatus}",
            fontSize = 12.sp,
            color = if (store.syncStatus == "Synced") Color(0xFF2E7D32) else Color(0xFF757575),
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tables) { t ->
                val busy = store.orders[t].orEmpty().isNotEmpty()
                Card(
                    onClick = { onOpen(t) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (busy) Color(0xFFFFE0B2) else Color(0xFFE8F5E9)
                    )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(t, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(if (busy) store.money(store.total(t)) else "Free", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Order screen (add items + bill)
// ------------------------------------------------------------------

@Composable
fun OrderScreen(store: PosStore, table: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val withBt = rememberWithBtPermission()
    var tab by remember { mutableIntStateOf(0) }
    var showSettle by remember { mutableStateOf(false) }
    val lines = store.orders[table].orEmpty()

    fun printBill() {
        when {
            lines.isEmpty() ->
                Toast.makeText(ctx, "Add items first", Toast.LENGTH_SHORT).show()
            store.printerAddress.isBlank() ->
                Toast.makeText(ctx, "Choose a printer in Settings first", Toast.LENGTH_LONG).show()
            else -> {
                val bytes = EscPos.bill(store, table)
                val address = store.printerAddress
                withBt { BtPrinter.printAsync(ctx, address, bytes) }
            }
        }
    }

    Scaffold(
        topBar = { AppBar(table, onBack) },
        bottomBar = {
            Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("${store.itemCount(table)} items", fontSize = 13.sp)
                        Text(
                            store.money(store.total(table)),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (tab == 0) {
                        Button(onClick = { tab = 1 }) { Text("View bill") }
                    } else {
                        Button(onClick = { printBill() }) { Text("Print bill") }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Add items") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Bill (${store.itemCount(table)})") }
                )
            }
            if (tab == 0) {
                MenuPicker(store, table)
            } else {
                BillPanel(store, table) { showSettle = true }
            }
        }
    }

    if (showSettle) {
        SettleDialog(store, table, onDismiss = { showSettle = false }) {
            showSettle = false
            onBack()
        }
    }
}

@Composable
fun SettleDialog(store: PosStore, table: String, onDismiss: Act, onDone: Act) {
    val ctx = LocalContext.current
    val total = store.total(table)
    var mode by remember { mutableStateOf("Cash") }
    var paid by remember { mutableStateOf(store.fmt2(total)) }
    var customer by remember { mutableStateOf("") }
    val paidVal = paid.toDoubleOrNull() ?: 0.0
    val balance = maxOf(0.0, total - paidVal)
    val needName = mode == "Pending" || balance > 0.009

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle $table: ${store.money(total)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Payment mode", fontWeight = FontWeight.Bold)
                PAY_MODES.chunked(2).forEach { rowModes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowModes.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = {
                                    mode = m
                                    paid = if (m == "Pending") "0" else store.fmt2(total)
                                },
                                label = { Text(m) }
                            )
                        }
                    }
                }
                Field("Amount paid", paid, KeyboardType.Decimal) { paid = it }
                if (balance > 0.009) Text("Balance due: ${store.money(balance)}", color = MaterialTheme.colorScheme.error)
                if (paidVal > total + 0.009) Text("Return change: ${store.money(paidVal - total)}")
                if (needName || mode == "Room") {
                    Field(
                        if (needName) "Customer name (required)" else "Room no. / guest name (optional)",
                        customer
                    ) { customer = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (needName && customer.isBlank()) {
                    Toast.makeText(ctx, "Customer name is required for a pending amount", Toast.LENGTH_LONG).show()
                } else {
                    store.settle(table, mode, paidVal, customer)
                    onDone()
                }
            }) { Text("Settle & clear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun MenuPicker(store: PosStore, table: String) {
    var query by remember { mutableStateOf("") }
    val lines = store.orders[table].orEmpty()
    val shown = store.menu
        .filter { it.name.contains(query, true) || it.category.contains(query, true) }
        .sortedWith(compareBy<MenuItem>({ it.category.lowercase() }, { it.name.lowercase() }))

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search item or category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { item ->
                val qty = lines.firstOrNull { it.itemId == item.id }?.qty ?: 0
                ListItem(
                    headlineContent = { Text(item.name, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(item.category) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (qty > 0) {
                                Text(
                                    "x$qty   ",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(store.money(item.price))
                        }
                    },
                    modifier = Modifier.clickable { store.addItem(table, item) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun BillPanel(store: PosStore, table: String, onSettle: () -> Unit) {
    val lines = store.orders[table].orEmpty()
    Column(Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No items yet. Open the 'Add items' tab and tap an item.")
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(lines, key = { it.itemId }) { l ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                l.name,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(store.money(l.price * l.qty), fontWeight = FontWeight.Bold)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${store.money(l.price)} each", fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { store.changeQty(table, l.itemId, -1) }) {
                                    Text("-", fontSize = 26.sp)
                                }
                                Text("${l.qty}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                IconButton(onClick = { store.changeQty(table, l.itemId, 1) }) {
                                    Text("+", fontSize = 24.sp)
                                }
                                IconButton(onClick = { store.removeLine(table, l.itemId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            RowKV("Subtotal", store.money(store.subtotal(table)))
            if (store.taxPercent > 0) {
                RowKV("Tax (${store.fmt(store.taxPercent)}%)", store.money(store.tax(table)))
            }
            RowKV("Total", store.money(store.total(table)), bold = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSettle,
                enabled = lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Settle & clear table") }
        }
    }
}

// ------------------------------------------------------------------
// Menu manager (add / edit / delete items and prices)
// ------------------------------------------------------------------

@Composable
fun MenuScreen(store: PosStore) {
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = store.menu
        .filter { it.name.contains(query, true) || it.category.contains(query, true) }
        .sortedWith(compareBy<MenuItem>({ it.category.lowercase() }, { it.name.lowercase() }))

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar("Menu & prices")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search item or category") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(item.category) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(store.money(item.price), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { editing = item }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                            }
                        },
                        modifier = Modifier.clickable { editing = item }
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add item") }
    }

    if (adding) {
        ItemDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { n, p, c ->
                store.addMenuItem(n, p, c)
                adding = false
            },
            onDelete = null
        )
    }
    editing?.let { item ->
        ItemDialog(
            initial = item,
            onDismiss = { editing = null },
            onSave = { n, p, c ->
                store.updateMenuItem(item.id, n, p, c)
                editing = null
            },
            onDelete = {
                store.deleteMenuItem(item.id)
                editing = null
            }
        )
    }
}

@Composable
fun ItemDialog(
    initial: MenuItem?,
    onDismiss: () -> Unit,
    onSave: (String, Double, String) -> Unit,
    onDelete: (() -> Unit)?
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var price by remember {
        mutableStateOf(
            initial?.let { if (it.price % 1.0 == 0.0) it.price.toLong().toString() else it.price.toString() } ?: ""
        )
    }
    var category by remember { mutableStateOf(initial?.category ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add item" else "Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Item name", name) { name = it }
                Field("Price", price, KeyboardType.Decimal) { price = it }
                Field("Category (e.g. Starters)", category) { category = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull()
                if (name.isBlank() || p == null || p < 0) {
                    Toast.makeText(ctx, "Enter a name and a valid price", Toast.LENGTH_SHORT).show()
                } else {
                    onSave(name.trim(), p, category.trim().ifBlank { "General" })
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(store: PosStore) {
    val ctx = LocalContext.current
    val withBt = rememberWithBtPermission()
    var showPicker by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var taxText by remember { mutableStateOf(store.fmt(store.taxPercent)) }
    var urlText by remember { mutableStateOf(store.cloudUrl) }
    var authText by remember { mutableStateOf(store.cloudAuth) }

    Column(Modifier.fillMaxSize()) {
        AppBar("Settings")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Bluetooth printer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                if (store.printerAddress.isBlank()) "No printer selected"
                else "${store.printerName} (${store.printerAddress})"
            )
            Button(onClick = {
                withBt {
                    if (!BtPrinter.isEnabled(ctx)) {
                        Toast.makeText(ctx, "Turn on Bluetooth first", Toast.LENGTH_LONG).show()
                    } else {
                        val found = BtPrinter.pairedDevices(ctx)
                        devices = found
                        if (found.isEmpty()) {
                            Toast.makeText(
                                ctx,
                                "No paired devices. Pair the printer in Android Bluetooth settings first.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            showPicker = true
                        }
                    }
                }
            }) { Text("Choose Bluetooth printer") }
            OutlinedButton(onClick = {
                val address = store.printerAddress
                if (address.isBlank()) {
                    Toast.makeText(ctx, "Choose a printer first", Toast.LENGTH_SHORT).show()
                } else {
                    val bytes = EscPos.test(store)
                    withBt { BtPrinter.printAsync(ctx, address, bytes) }
                }
            }) { Text("Print test page") }

            Text("Paper width")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(32 to "2 inch", 42 to "3 inch (42)", 48 to "3 inch (48)").forEach { (n, label) ->
                    FilterChip(
                        selected = store.charsPerLine == n,
                        onClick = {
                            store.charsPerLine = n
                            store.save()
                        },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider()
            Text("Restaurant details (printed on bill)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Field("Restaurant name", store.restaurantName) {
                store.restaurantName = it
                store.save()
            }
            Field("Address", store.address) {
                store.address = it
                store.save()
            }
            Field("Phone", store.phone, KeyboardType.Phone) {
                store.phone = it
                store.save()
            }
            Field("Bill footer message", store.footer) {
                store.footer = it
                store.save()
            }
            Field("Currency label (printed as text, e.g. Rs)", store.currency) {
                store.currency = it
                store.save()
            }
            Field("Tax / GST %", taxText, KeyboardType.Decimal) {
                taxText = it
                store.taxPercent = it.toDoubleOrNull() ?: 0.0
                store.save()
            }
            Button(onClick = {
                store.pushSettings()
                Toast.makeText(ctx, "Saved and shared with other devices", Toast.LENGTH_SHORT).show()
            }) { Text("Save & share these details") }

            HorizontalDivider()
            Text("Multi-device sync", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Paste the same Firebase Realtime Database URL on every device. Leave empty to use this device only.", fontSize = 13.sp)
            Field("Database URL (https://...firebasedatabase.app)", urlText) { urlText = it }
            Field("Database secret (optional)", authText) { authText = it }
            Button(onClick = {
                val u = urlText.trim()
                if (u.isNotBlank() && !u.startsWith("https://")) {
                    Toast.makeText(ctx, "URL must start with https://", Toast.LENGTH_LONG).show()
                } else {
                    store.setCloud(u, authText)
                    Toast.makeText(ctx, if (u.isBlank()) "Sync turned off" else "Connecting...", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Save & connect") }
            Text("Status: ${store.syncStatus}")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Select printer") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    devices.forEach { d ->
                        ListItem(
                            headlineContent = { Text(d.name ?: "Unknown device") },
                            supportingContent = { Text(d.address) },
                            modifier = Modifier.clickable {
                                store.printerAddress = d.address
                                store.printerName = d.name ?: d.address
                                store.save()
                                showPicker = false
                            }
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        )
    }
}

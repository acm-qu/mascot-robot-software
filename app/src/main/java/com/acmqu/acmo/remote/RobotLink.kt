package com.acmqu.acmo.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.acmqu.acmo.face.Expression
import java.util.concurrent.Executors

/**
 * Sends ACMO's current face to the wheel-control board over the USB cable. The
 * board is a USB CDC-ACM device -- its `Serial` -- and this app is the USB host.
 * On every expression change it writes one line, `face:<name>\n`, to the board's
 * bulk-OUT endpoint; the board reads it in serviceVoice() and turns it into a
 * small movement or servo gesture (faces.ino / voice-activated.ino).
 *
 * Write-only and best-effort. If the cable is out, permission is not granted, or
 * the write fails, [face] quietly does nothing and the next change retries the
 * connection. A single background thread carries the writes, so a burst of face
 * changes while ACMO talks never blocks the UI thread that calls [face].
 *
 * A background reader drains the board's incoming bytes (its own log output) and
 * discards them, so the board's `Serial` TX buffer cannot fill and stall the
 * sketch while the host is not otherwise reading.
 *
 * This is unverified on hardware from here: the board's USB vendor id and the
 * CDC interface layout, and Android's USB-permission dialog, are the things to
 * check on the tablet. See the README.
 */
class RobotLink(private val context: Context) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var conn: UsbDeviceConnection? = null
    @Volatile private var iface: UsbInterface? = null
    @Volatile private var out: UsbEndpoint? = null
    @Volatile private var input: UsbEndpoint? = null
    @Volatile private var drain: Thread? = null
    @Volatile private var reachable = false   // so a state change logs once, not per face
    @Volatile private var asking = false      // a permission request is outstanding; do not stack another

    private val permission = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERMISSION) return
            asking = false
            @Suppress("DEPRECATION")
            val device = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                open(device)
            }
        }
    }

    /** Register for the permission result, then try to open an attached board. */
    fun start() {
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permission, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permission, filter)
        }
        connect()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(permission) }
        io.execute { close() }
        io.shutdown()
    }

    /** Any thread. Sends the new face; a no-op (and a reconnect attempt) if the board is not open. */
    fun face(e: Expression) {
        val c = conn
        val o = out
        if (c == null || o == null) {
            connect()
            return
        }
        val line = "face:${e.name.lowercase()}\n".toByteArray()
        io.execute {
            val n = c.bulkTransfer(o, line, line.size, WRITE_TIMEOUT_MS)
            if (n < 0) {
                Log.w(TAG, "write failed; dropping the link")
                close()
            }
        }
    }

    /** Find the board and open it, asking for USB permission if we do not have it yet. */
    fun connect() {
        if (out != null || asking) return
        val device = findBoard() ?: return
        if (usb.hasPermission(device)) {
            open(device)
        } else {
            // One dialog at a time: without this, a face change per sentence while
            // the board is plugged-but-unauthorised would raise a dialog each time.
            asking = true
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    // The Arduino first (its own vendor id), else any device exposing a CDC data
    // interface -- that is the class that carries the bulk endpoints we write to.
    private fun findBoard(): UsbDevice? {
        val devices = usb.deviceList.values
        return devices.firstOrNull { it.vendorId == ARDUINO_VID }
            ?: devices.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } }
    }

    private fun open(device: UsbDevice) {
        io.execute {
            close()
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_CDC_DATA) continue

                var bulkOut: UsbEndpoint? = null
                var bulkIn: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep else bulkIn = ep
                }
                if (bulkOut == null) continue

                val c = usb.openDevice(device) ?: continue
                if (!c.claimInterface(intf, true)) {
                    c.close()
                    continue
                }
                // Assert DTR/RTS so the board treats the link as live. Best effort:
                // native-USB CDC ignores it, and a failure does not stop the writes.
                c.controlTransfer(0x21, SET_CONTROL_LINE_STATE, 0x03, intf.id, null, 0, 200)

                conn = c
                iface = intf
                out = bulkOut
                input = bulkIn
                startDrain()
                if (!reachable) {
                    reachable = true
                    Log.i(TAG, "robot USB open: ${device.productName ?: device.deviceName}")
                }
                return@execute
            }
            Log.w(TAG, "no CDC data interface on ${device.deviceName}")
        }
    }

    // Read and throw away whatever the board sends (its log lines), so its USB TX
    // buffer never fills and blocks the sketch. Ends when the endpoint is cleared.
    private fun startDrain() {
        val c = conn ?: return
        val inEp = input ?: return
        val t = Thread {
            val buf = ByteArray(64)
            while (input === inEp) {
                val n = c.bulkTransfer(inEp, buf, buf.size, DRAIN_TIMEOUT_MS)
                if (n < 0 && input !== inEp) break
            }
        }
        t.isDaemon = true
        drain = t
        t.start()
    }

    private fun close() {
        input = null   // stops the drain loop
        out = null
        iface?.let { conn?.releaseInterface(it) }
        conn?.close()
        conn = null
        iface = null
        drain = null
        reachable = false
        asking = false
    }

    companion object {
        private const val TAG = "RobotLink"
        private const val ACTION_PERMISSION = "com.acmqu.acmo.USB_PERMISSION"
        private const val ARDUINO_VID = 0x2341   // Arduino SA -- the UNO R4
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val WRITE_TIMEOUT_MS = 200
        private const val DRAIN_TIMEOUT_MS = 200
    }
}

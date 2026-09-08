package com.example.Chennai_Coop.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.Chennai_Coop.data.models.Member
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PrintableReportRow(
    val number: String,
    val issuedCount: Int,
    val scannedCount: Int
)

class ThermalPrinterManager(
    private val context: Context,
    private val bluetoothManager: BluetoothPrinterManager = BluetoothPrinterManager(context)
) {

    private val TAG = "ThermalPrinter"
    private val eventConfig by lazy { EventConfig.load(context) }
    // --- SECURITY CONFIGURATION ---
    private val SECRET_KEY = "s0c1ety_Sup3r_S3cr3t_K3y_@2024"

    // --- HELPER: Text Formatting ---
    private fun centerText(text: String, width: Int = 48): String {
        if (text.length >= width) return text + "\n"
        val padding = (width - text.length) / 2
        val sb = StringBuilder()
        repeat(padding) { sb.append(" ") }
        sb.append(text)
        sb.append("\n")
        return sb.toString()
    }

    // --- NEW HELPER: 3 Column Layout (Left - Center - Right) ---
    private fun formatThreeColumns(col1: String, col2: String, col3: String): String {
        val colWidth = 16 // 48 chars total / 3 columns

        // 1. Left Column: Left Aligned
        // %-16s pads with spaces to the right
        val s1 = String.format("%-16s", col1)

        // 3. Right Column: Right Aligned
        // %16s pads with spaces to the left
        val s3 = String.format("%16s", col3)

        // 2. Center Column: Center Aligned (Manual calculation)
        val sbMiddle = StringBuilder()
        val textLength = col2.length
        if (textLength >= colWidth) {
            sbMiddle.append(col2) // If too long, just print it (might break alignment)
        } else {
            val totalPadding = colWidth - textLength
            val padLeft = totalPadding / 2
            val padRight = totalPadding - padLeft
            repeat(padLeft) { sbMiddle.append(" ") }
            sbMiddle.append(col2)
            repeat(padRight) { sbMiddle.append(" ") }
        }

        return s1 + sbMiddle.toString() + s3 + "\n"
    }

    private fun formatKeyValue(key: String, value: String): String {
        val maxWidth = 48
        val padding = maxWidth - key.length - value.length
        val sb = StringBuilder()
        sb.append(key)
        if (padding > 0) {
            repeat(padding) { sb.append(" ") }
        } else {
            sb.append(" ")
        }
        sb.append(value)
        sb.append("\n")
        return sb.toString()
    }

    private fun StringBuilder.appendSocietyHeader() {
        append("\u001D\u0021\u0001") // Double size
        append("\u001B\u0045\u0001") // Bold on
        eventConfig.societyNameLines.forEach { line -> append("$line\n") }
        append("\u001D\u0021\u0000") // Normal size
        append("\u001B\u0045\u0000") // Bold off
    }

    // --- HELPER: Date Formatting ---
    private fun formatShortDate(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return ""
        return try {
            val parsers = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )
            val outputFormat = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())

            for (parser in parsers) {
                try {
                    val date = parser.parse(dateString)
                    if (date != null) return outputFormat.format(date)
                } catch (e: Exception) { continue }
            }
            dateString
        } catch (e: Exception) { dateString }
    }

    private fun formatDateOnly(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return ""
        return try {
            val parsers = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )
            val outputFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            for (parser in parsers) {
                try {
                    val date = parser.parse(dateString)
                    if (date != null) return outputFormat.format(date)
                } catch (e: Exception) { continue }
            }
            dateString
        } catch (e: Exception) { dateString }
    }

    // --- HELPER: Image Processing ---
    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bitmapWidth = (width + 7) / 8 * 8
        val bytesPerLine = bitmapWidth / 8
        val imageBytes = ByteArray(bytesPerLine * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                if ((red + green + blue) / 3 < 128) {
                    val byteIndex = y * bytesPerLine + (x / 8)
                    val bitIndex = 7 - (x % 8)
                    imageBytes[byteIndex] = (imageBytes[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }
        val command = ByteArray(8 + imageBytes.size)
        command[0] = 0x1D; command[1] = 0x76; command[2] = 0x30; command[3] = 0x00
        command[4] = (bytesPerLine % 256).toByte()
        command[5] = (bytesPerLine / 256).toByte()
        command[6] = (height % 256).toByte()
        command[7] = (height / 256).toByte()
        System.arraycopy(imageBytes, 0, command, 8, imageBytes.size)
        return command
    }

    fun generateQRCode(data: String, size: Int = 350): Bitmap? {
        try {
            val qrCodeWriter = QRCodeWriter()
            val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) { return null }
    }

    // --- SECURITY: HMAC SIGNING LOGIC ---

    // Generates "MemberID|Signature"
    private fun generateSignedPayload(data: String): String {
        val signature = computeHmacSha256(data, SECRET_KEY)
        return "$data|$signature"
    }

    private fun computeHmacSha256(data: String, key: String): String {
        try {
            val algorithm = "HmacSHA256"
            val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), algorithm)
            val mac = Mac.getInstance(algorithm)
            mac.init(secretKeySpec)
            val bytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return bytesToHex(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[j * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }

    // --- LOGIC ---

    fun formatPrintData(member: Member): String {
        val builder = StringBuilder()

        // 1. Initialize & Header
        builder.append("\u001B@") // Reset
        builder.append("\u001Ba\u0001") // Align Center

        builder.appendSocietyHeader()

        // G.B MEETING NOTICE
        builder.append("------------------------------------------------\n")
        builder.append("\u001B\u0045\u0001") // Bold On
        builder.append("${eventConfig.meetingTitle}\n")
        builder.append("\u001B\u0045\u0000") // Bold Off
        builder.append("Date: ${eventConfig.meetingDate}\n")
        builder.append("Venue   ${eventConfig.venue}\n")
        builder.append("------------------------------------------------\n")

        builder.append("\u001Ba\u0000") // Align Left

        // 2. Member Info
        builder.append(" Member No   : ${member.memberNumber}\n")
        builder.append(" Employee No : ${member.employeeNumber}\n")
        builder.append(" Name        : ")
        builder.append("\u001B\u0045\u0001") // Bold
        builder.append("${member.name}\n")
        builder.append("\u001B\u0045\u0000") // Bold Off
        builder.append(" Station     : ${member.station ?: ""}\n")

        // 3. Account Closed Warning
        if (!member.isEligibleForQr) {
            builder.append("\n")
            builder.append("\u001Ba\u0001") // Center
            builder.append("\u001D\u0021\u0001") // Double Size
            builder.append("\u001B\u0045\u0001") // Bold
            builder.append("*** ${member.qrIneligibilityLabel} ***\n")
            builder.append("\u001B\u0045\u0000")
            builder.append("\u001D\u0021\u0000")
            builder.append("\u001Ba\u0000") // Left
            builder.append("\n")
        }

        builder.append("------------------------------------------------\n")

        // Check if account is closed
        val isClosed = member.isAccountClosed

        // --- 1. DEPOSIT DETAILS (Only show if account is not closed) ---
        if (!isClosed) {
            builder.append("\u001Ba\u0001") // Center
            builder.append("\u001B\u0045\u0001") // Bold
            builder.append("DEPOSIT DETAILS\n")
            builder.append("\u001B\u0045\u0000")
            builder.append("\u001Ba\u0000") // Left
            builder.append("------------------------------------------------\n")

            // Headers
            builder.append(formatThreeColumns("Share Capital", "Thrift Dep", "F.D"))

            builder.append("------------------------------------------------\n")

            // Values
            builder.append(formatThreeColumns(
                "Rs.${(member.shareCapital ?: 0.0).toInt()}",
                "Rs.${(member.thriftDeposit ?: 0.0).toInt()}",
                "Rs.${(member.familyDeposit ?: 0.0).toInt()}"
            ))
            builder.append("------------------------------------------------\n")
        }

        // --- 2. LOAN DETAILS (Only show if loan balance > 0) ---
        val loanBalance = member.loanBalance ?: 0.0
        if (loanBalance > 0) {
            builder.append("\u001Ba\u0001") // Center
            builder.append("\u001B\u0045\u0001") // Bold
            builder.append("LOAN DETAILS\n")
            builder.append("\u001B\u0045\u0000")
            builder.append("\u001Ba\u0000") // Left
            builder.append("------------------------------------------------\n")

            // Headers
            builder.append(formatThreeColumns("Loan Date", "Amount", "Balance"))

            builder.append("------------------------------------------------\n")

            // Values
            builder.append(formatThreeColumns(
                formatDateOnly(member.loanDate),
                "Rs.${(member.loanAmount ?: 0.0).toInt()}",
                "Rs.${(member.loanBalance ?: 0.0).toInt()}"
            ))

            builder.append("------------------------------------------------\n")
        }

        // --- 3. DIVIDEND CALCULATION DETAILS ---
        builder.append("\u001Ba\u0001") // Center
        builder.append("\u001B\u0045\u0001") // Bold
        builder.append("DIVIDEND CALCULATION DETAILS\n")
        builder.append("\u001B\u0045\u0000")
        builder.append("\u001Ba\u0000") // Left
        builder.append("------------------------------------------------\n")

        // Table
        val rowFormat = "%-10s%-10s%8s%10s%10s\n"
        builder.append(String.format(rowFormat, "From", "To", "Days", "Bal", "Amt"))
        builder.append("------------------------------------------------\n")

        var totalAmount = 0.0
        member.dividend.forEach { entry ->
            val fromDate = formatDateOnly(entry.from)
            val toDate = formatDateOnly(entry.to)
            val days = (entry.days ?: 0).toString()
            val balance = (entry.balance ?: 0.0).toInt().toString()
            val amount = String.format("%.2f", entry.amount ?: 0.0)

            builder.append(String.format(rowFormat, fromDate, toDate, days, balance, amount))
            totalAmount += entry.amount ?: 0.0
        }

        builder.append("------------------------------------------------\n")
        val totalLabel = "Dividend @ 14% :"
        val totalVal = String.format("%.2f", totalAmount)
        builder.append("\u001B\u0045\u0001")
        builder.append(String.format("%38s%10s\n", totalLabel, totalVal))
        builder.append("\u001B\u0045\u0000")
        builder.append("------------------------------------------------\n")

        // --- 4. FOOTER: NEFT & AC NO ---
        builder.append("\u001B\u0045\u0001")
        builder.append(formatKeyValue("NEFT Amount", "Rs. ${member.neft ?: 0}"))
        builder.append("\u001B\u0045\u0000")

        val accNo = member.accountNumber?.trim() ?: ""
        builder.append(formatKeyValue("A/C No", accNo))

        builder.append("\n\n")
        return builder.toString()
    }

    fun getPairedPrinters() = bluetoothManager.getPairedDevices()

    fun printViaBluetooth(
        member: Member,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (!bluetoothManager.isConnected()) {
                    val printers = bluetoothManager.getPairedDevices()
                    if (printers.isEmpty()) {
                        onError("No paired Bluetooth printers found.")
                        return@launch
                    }
                    val printer = printers.find { device ->
                        val name = device.name ?: ""
                        name.contains("MPT", ignoreCase = true) ||
                                name.contains("printer", ignoreCase = true) ||
                                name.contains("POS", ignoreCase = true)
                    } ?: printers.firstOrNull()

                    if (printer == null) {
                        onError("No suitable printer found")
                        return@launch
                    }
                    if (!bluetoothManager.connect(printer)) {
                        onError("Failed to connect to printer")
                        return@launch
                    }
                }

                val textData = formatPrintData(member)
                if (!bluetoothManager.print(textData)) {
                    onError("Failed to print text")
                    return@launch
                }
                delay(500)

                // --- GENERATE SECURE QR ---
                if (member.isEligibleForQr) {
                    val rawMemberId = member.memberNumber?.trim() ?: "0000"
                    val secureQrContent = generateSignedPayload(rawMemberId)
                    val qrBitmap = generateQRCode(secureQrContent, 350)

                    if (qrBitmap != null) {
                        bluetoothManager.printBytes(byteArrayOf(0x1B, 0x61, 0x01)) // Center
                        bluetoothManager.printBytes(bitmapToBytes(qrBitmap))
                        bluetoothManager.printBytes(byteArrayOf(0x1B, 0x61, 0x00)) // Left
                    }
                }
                // ---------------------------------------------

                delay(200)
                bluetoothManager.print("\n\n\n\u001DVA\u0003")
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Error in printViaBluetooth: ${e.message}", e)
                onError("Error: ${e.message}")
            }
        }
    }

    /** Prints the members recorded by one bulk scan. */
    fun printBulkScan(
        groupId: String,
        members: List<Member>,
        scannedAt: String,
        issuerNumber: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (members.isEmpty()) {
                    onError("No scanned members to print")
                    return@launch
                }

                if (!bluetoothManager.isConnected()) {
                    val printers = bluetoothManager.getPairedDevices()
                    if (printers.isEmpty()) {
                        onError("No paired Bluetooth printers found.")
                        return@launch
                    }
                    val printer = printers.find { device ->
                        val name = device.name.orEmpty()
                        name.contains("MPT", ignoreCase = true) ||
                                name.contains("printer", ignoreCase = true) ||
                                name.contains("POS", ignoreCase = true)
                    } ?: printers.first()

                    if (!bluetoothManager.connect(printer)) {
                        onError("Failed to connect to printer")
                        return@launch
                    }
                }

                val orderedMembers = members.sortedWith(
                    compareBy<Member> { it.memberNumber?.toIntOrNull() ?: Int.MAX_VALUE }
                        .thenBy { it.memberNumber.orEmpty() }
                )
                fun boxedCenteredEmphasizedLine(text: String): String {
                    val innerWidth = 46
                    val value = text.take(innerWidth)
                    val leftPadding = (innerWidth - value.length) / 2
                    val rightPadding = innerWidth - value.length - leftPadding
                    return buildString {
                        append("\u001D\u0021\u0010") // Double height for the complete boxed row
                        append("\u001B\u0045\u0001") // Bold on
                        append('|')
                        append(" ".repeat(leftPadding))
                        append(value)
                        append(" ".repeat(rightPadding))
                        append("|\n")
                        append("\u001B\u0045\u0000") // Bold off
                        append("\u001D\u0021\u0000") // Normal size
                    }
                }
                fun buildReceiptCopy(copyLabel: String, includeTotalScanned: Boolean): String = buildString {
                    append("\u001B@")
                    append("\u001Ba\u0001")
                    appendSocietyHeader()
                    append("------------------------------------------------\n")
                    append("\u001B\u0045\u0001")
                    append("BULK SWEET SCAN - $copyLabel\n")
                    append("\u001B\u0045\u0000")
                    append("Group: $groupId\n")
                    append("Issue Date: ${formatShortDate(scannedAt)}\n")
                    append("Issuer Number: $issuerNumber\n")
                    append("------------------------------------------------\n")
                    append("\u001Ba\u0000")
                    append(String.format("%-5s%-9s%-34s\n", "SNO", "MNO", "NAME"))
                    append("------------------------------------------------\n")
                    orderedMembers.forEachIndexed { index, member ->
                        val sno = (index + 1).toString()
                        val mno = member.memberNumber.orEmpty().take(8)
                        val name = member.name.orEmpty().replace("\n", " ").take(33)
                        append(String.format("%-5s%-9s%-34s\n", sno, mno, name))
                    }
                    append("------------------------------------------------\n")
                    if (includeTotalScanned) {
                        append("+----------------------------------------------+\n")
                        append(boxedCenteredEmphasizedLine("TOTAL SCANNED"))
                        append(boxedCenteredEmphasizedLine(orderedMembers.size.toString()))
                        append("+----------------------------------------------+\n")
                    }
                }

                val receipt = buildString {
                    append(buildReceiptCopy("OFFICE COPY", includeTotalScanned = true))
                    append("\n\n\n")
                    append("------------------ TEAR HERE ------------------\n")
                    append("\n\n\n\n\n")
                    append(buildReceiptCopy("MEMBER COPY", includeTotalScanned = false))
                    append("\n\n\n\u001DVA\u0003")
                }

                if (bluetoothManager.print(receipt)) onSuccess()
                else onError("Failed to print scanned member list")
            } catch (e: Exception) {
                Log.e(TAG, "Error printing bulk scan: ${e.message}", e)
                onError("Error: ${e.message}")
            }
        }
    }

    fun printReport(
        totalIssued: Int,
        totalScanned: Int,
        rows: List<PrintableReportRow>,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (!bluetoothManager.isConnected()) {
                    val printers = bluetoothManager.getPairedDevices()
                    if (printers.isEmpty()) {
                        onError("No paired Bluetooth printers found.")
                        return@launch
                    }
                    val printer = printers.find { device ->
                        val name = device.name.orEmpty()
                        name.contains("MPT", ignoreCase = true) ||
                                name.contains("printer", ignoreCase = true) ||
                                name.contains("POS", ignoreCase = true)
                    } ?: printers.first()

                    if (!bluetoothManager.connect(printer)) {
                        onError("Failed to connect to printer")
                        return@launch
                    }
                }

                val printedAt = SimpleDateFormat(
                    "dd/MM/yy hh:mm a",
                    Locale.getDefault()
                ).format(Date())
                val receipt = buildString {
                    append("\u001B@")
                    append("\u001Ba\u0001")
                    appendSocietyHeader()
                    append("------------------------------------------------\n")
                    append("\u001B\u0045\u0001")
                    append("ACTIVITY REPORT\n")
                    append("\u001B\u0045\u0000")
                    append("Printed: $printedAt\n")
                    append("------------------------------------------------\n")
                    append("\u001Ba\u0000")
                    append(formatKeyValue("Total Issued", totalIssued.toString()))
                    append(formatKeyValue("Total Scanned", totalScanned.toString()))
                    append("------------------------------------------------\n")
                    append(formatThreeColumns("ISSUER ID", "ISSUED", "SCANNED"))
                    append("------------------------------------------------\n")
                    rows.forEach { row ->
                        append(formatThreeColumns(
                            row.number.take(15),
                            row.issuedCount.toString(),
                            row.scannedCount.toString()
                        ))
                    }
                    append("------------------------------------------------\n")
                    append(formatKeyValue("Total Operators", rows.size.toString()))
                    append("\n\n\n\u001DVA\u0003")
                }

                if (bluetoothManager.print(receipt)) onSuccess()
                else onError("Failed to print report")
            } catch (e: Exception) {
                Log.e(TAG, "Error printing report: ${e.message}", e)
                onError("Error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        bluetoothManager.close()
    }
    fun isConnected() = bluetoothManager.isConnected()
}

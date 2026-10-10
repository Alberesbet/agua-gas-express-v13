package com.example.aguagasexpress

import android.content.Context
import android.location.Geocoder
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.DialogInterface
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.net.Uri
import android.provider.MediaStore
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aguagasexpress.ui.theme.AguaGasExpressTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0B1730)
private val Blue = Color(0xFF1688E8)
private val LightBlue = Color(0xFF54B8FF)
private val Orange = Color(0xFFFF8A32)
private val Ink = Color(0xFF18253A)
private val Green = Color(0xFF16834A)

// Links compartilhados: HTTPS para o WhatsApp reconhecer como clicável.
// A página /invite redireciona para o aplicativo quando instalado.
private const val WEB_INVITE_BASE_URL = "https://alberesbet.github.io/agua-gas-express-v13/invite/"
// Um único link por convite. O Android App Link abre o aplicativo quando ele já está instalado;
// caso contrário, o mesmo HTTPS cai na página Web de entrada/download.
private fun makeInviteLink(profile: String, companyCode: String, platform: String = ""): String {
    return "$WEB_INVITE_BASE_URL?perfil=${Uri.encode(profile)}&empresa=${Uri.encode(companyCode)}"
}

// Código inicial de autorização. É local/offline; não substitui autenticação online.
private const val DEFAULT_ADMIN_AUTH_CODE = "160829"

private data class Product(val name: String, val price: Double, val isGas: Boolean = name.equals("Gás", ignoreCase = true))

private data class Order(
    val id: Long,
    val customer: String,
    val phone: String,
    val customerDocType: String = "",
    val customerDocNumber: String = "",
    val address: String,
    val items: String,
    val total: Double,
    val payment: String,
    val cashGiven: Double,
    val status: String,
    val paid: Boolean,
    val waterQty: Int = 0,
    val gasQty: Int = 0,
    val demo: Boolean = false,
    val pixReported: Boolean = false,
    val orderNumber: Int = 0,
    val customerUid: String = "",
    val customerFcmToken: String = "",
    val trackingLat: Double? = null,
    val trackingLng: Double? = null,
    val trackingActive: Boolean = false,
    val trackingPath: List<Map<String, Double>> = emptyList(),
    val assignedDriver: Int = 0,
    val companyId: String = "",
    val receiptGeneratedAt: Long = 0L,
    val paymentConfirmedAt: Long = 0L,
    val approvalSentAt: Long = 0L,
    val deliveryStartedAt: Long = 0L,
    val arrivalAt: Long = 0L,
    val originAddress: String = ""
)


private fun companyConfigRef(firestore: FirebaseFirestore, companyId: String) =
    firestore.collection("companies").document(companyId).collection("config").document("main")

private fun normalizeCompanyCode(value: String): String =
    value.trim().uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(12)

private fun generateCompanyCode(): String =
    UUID.randomUUID().toString().replace("-", "").take(6).uppercase(Locale.ROOT)

private fun generateCommercialLicenseKey(): String {
    val raw = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)
    return "COM-${raw.take(4)}-${raw.drop(4).take(4)}-${raw.drop(8).take(4)}"
}

private fun localTrialActive(prefs: android.content.SharedPreferences): Boolean {
    if (prefs.getBoolean("license_permanent", false)) return true
    val expiry = prefs.getLong("license_expires_at", 0L)
    return expiry == 0L || System.currentTimeMillis() < expiry
}

private fun trialExpiryLabel(prefs: android.content.SharedPreferences): String {
    if (prefs.getBoolean("license_permanent", false)) return "LICENÇA PERMANENTE"
    val expiry = prefs.getLong("license_expires_at", 0L)
    if (expiry <= 0L) return "Aguardando ativação"
    return java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(java.util.Date(expiry))
}

private fun saveCompanyLocal(
    prefs: android.content.SharedPreferences,
    id: String,
    name: String,
    code: String,
    expiry: Long,
    permanent: Boolean,
    licenseKey: String,
    phone: String,
    address: String
) {
    prefs.edit()
        .putString("company_id", id)
        .putString("company_name", name)
        .putString("company_code", code)
        .putLong("license_expires_at", expiry)
        .putBoolean("license_permanent", permanent)
        .putString("license_key", licenseKey)
        .putString("company_phone", phone)
        .putString("company_origin_address", address)
        .apply()
}

private fun registerAdminPushToken(
    prefs: android.content.SharedPreferences,
    firestore: FirebaseFirestore,
    companyId: String
) {
    if (prefs.getString("admin_password_hash", null).isNullOrBlank()) return
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            prefs.edit().putString("admin_fcm_token", token).apply()
            // Merge preserves prices, Pix settings and other fields in appConfig/main.
            companyConfigRef(firestore, companyId)
                .set(mapOf("adminFcmToken" to token), SetOptions.merge())
        }
}

private enum class Page { HOME, LOGIN, DELIVERY_LOGIN, CUSTOMER, ADMIN, DELIVERY, TRACKING, PRODUCTS, PIX_SETTINGS, ADMIN_CODE_SETTINGS, DELIVERY_PASSWORD_SETTINGS, NEW_ORDER, ORDERS, RECOVERY }

class MainActivity : ComponentActivity() {
    var pendingTrackingOrderId: String? = null
    var pendingTrackingAddress: String? = null
    var pendingTrackingOriginAddress: String? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val orderId = pendingTrackingOrderId
        val address = pendingTrackingAddress
        val originAddress = pendingTrackingOriginAddress
        pendingTrackingOrderId = null
        pendingTrackingAddress = null
        pendingTrackingOriginAddress = null

        if (!granted || orderId.isNullOrBlank()) return@registerForActivityResult

        val orderRef = FirebaseFirestore.getInstance().collection("orders").document(orderId)
        orderRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.getString("status") == "Autorizado") {
                orderRef.set(
                    mapOf(
                        "status" to "Em entrega",
                        "deliveryStartedAt" to System.currentTimeMillis(),
                        "trackingActive" to true,
                        "trackingPath" to emptyList<Map<String, Double>>()
                    ),
                    SetOptions.merge()
                ).addOnSuccessListener {
                    startDeliveryLocationService(this, orderId)
                    openDeliveryMap(address, originAddress)
                }
            } else {
                startDeliveryLocationService(this, orderId)
                openDeliveryMap(address, originAddress)
            }
        }
    }

    fun requestLocationPermission(orderId: String, address: String, originAddress: String = "") {
        pendingTrackingOrderId = orderId
        pendingTrackingAddress = address
        pendingTrackingOriginAddress = originAddress
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun openDeliveryMap(address: String?, originAddress: String? = null) {
        if (address.isNullOrBlank()) return
        val originPart = if (!originAddress.isNullOrBlank()) "&origin=${Uri.encode(originAddress)}" else ""
        val url = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(address)}$originPart&travelmode=driving"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No compatible Maps/browser app installed.
        }
    }
    override fun onResume() {
        super.onResume()
        isAppVisible = true
    }

    override fun onPause() {
        isAppVisible = false
        super.onPause()
    }

    companion object {
        @Volatile var isAppVisible: Boolean = false
            private set
        @Volatile var isOnDeliveryScreen: Boolean = false
    }

    private fun applyInviteIntent(incomingIntent: Intent?, prefs: android.content.SharedPreferences): Boolean {
        val data = incomingIntent?.data ?: return false
        val inviteProfile = data.getQueryParameter("perfil")?.lowercase(Locale.ROOT) ?: return false
        if (inviteProfile !in setOf("cliente", "entregador", "empresa", "proprietario")) return false

        val inviteCompanyCode = data.getQueryParameter("empresa")?.let(::normalizeCompanyCode).orEmpty()
        val invitePlatform = data.getQueryParameter("plataforma")?.lowercase(Locale.ROOT).orEmpty()
        val editor = prefs.edit().putString("invite_profile", inviteProfile)

        // Convites de cliente/entregador sempre substituem o papel anterior do aparelho.
        // Isso impede que a tela antiga de três perfis seja reaproveitada.
        if (inviteProfile == "cliente" || inviteProfile == "entregador") {
            editor.remove("company_id").remove("company_role").remove("company_authenticated")
        }
        if (inviteCompanyCode.isNotBlank()) editor.putString("invite_company_code", inviteCompanyCode)
        if (invitePlatform == "android" || invitePlatform == "notebook") editor.putString("invite_platform", invitePlatform)
        editor.apply()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prefs = getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
        if (applyInviteIntent(intent, prefs)) {
            // O app pode já estar aberto. Recriar a Activity faz o Gate reler o perfil
            // recém-recebido e abrir o fluxo correto do convite.
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("pedidos_novos", "Novos pedidos", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos de pedidos e chegada do entregador"
                enableVibration(true)
            })
        }
        val prefs = getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2601)
        }
        val defaultProfile = BuildConfig.DEFAULT_PROFILE
        val hasInvite = applyInviteIntent(intent, prefs)

        // Os APKs públicos de cliente e entregador não dependem de convite.
        // Cada variante abre diretamente no fluxo de cadastro/acesso do seu perfil.
        if (defaultProfile == "cliente" || defaultProfile == "entregador") {
            prefs.edit()
                .putString("company_role", if (defaultProfile == "cliente") "customer" else "delivery")
                .remove("invite_profile")
                .remove("invite_company_code")
                .apply()
        } else if (!hasInvite && prefs.getString("company_id", "").isNullOrBlank() &&
            prefs.getString("developer_password_hash", "").isNullOrBlank() &&
            prefs.getString("invite_profile", "").isNullOrBlank()) {
            prefs.edit().putString("invite_profile", defaultProfile).apply()
        }

        setContent {
            AguaGasExpressTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Navy) {
                    if (defaultProfile == "cliente" || defaultProfile == "entregador") {
                        AguaGasApp(prefs)
                    } else {
                        CommercialCompanyGate(prefs)
                    }
                }
            }
        }
    }
}

private fun announceCustomerArrival(context: Context) {
    try {
        lateinit var speech: android.speech.tts.TextToSpeech
        speech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val languageStatus = speech.setLanguage(java.util.Locale("pt", "BR"))
                if (languageStatus != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                    languageStatus != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    speech.speak("Seu pedido chegou! O entregador está no endereço.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "aguagas_cliente_chegada")
                } else playOrderAlertSound(context)
            } else playOrderAlertSound(context)
        }
    } catch (_: Exception) { playOrderAlertSound(context) }
}

private fun announcePaymentConfirmed(context: Context) {
    announceCustomerVoice(
        context,
        "Pagamento confirmado. Seu pedido foi liberado para entrega. A nota do pedido já está disponível.",
        "aguagas_pagamento_confirmado"
    )
}

private fun announceOrderSentToDelivery(context: Context) {
    announceCustomerVoice(
        context,
        "Seu pedido foi enviado para entrega. Em breve o entregador iniciará a entrega.",
        "aguagas_pedido_enviado_entrega"
    )
}

private fun announceCustomerApproved(context: Context) {
    announceOrderSentToDelivery(context)
}

private fun announceDeliveryStarted(context: Context) {
    announceCustomerVoice(
        context,
        "O entregador iniciou a entrega. Seu pedido está a caminho.",
        "aguagas_entrega_iniciada"
    )
}

private fun announceCustomerVoice(context: Context, text: String, eventId: String) {
    try {
        lateinit var speech: android.speech.tts.TextToSpeech
        speech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try {
                    val languageStatus = speech.setLanguage(Locale("pt", "BR"))
                    if (languageStatus != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                        languageStatus != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        speech.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, eventId)
                    } else {
                        playOrderAlertSound(context)
                    }
                } catch (_: Exception) { playOrderAlertSound(context) }
            } else {
                playOrderAlertSound(context)
            }
        }
    } catch (_: Exception) {
        playOrderAlertSound(context)
    }
}

private fun showCustomerVoiceNotification(context: Context, title: String, text: String) {
    try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                "cliente_status",
                "Status do pedido",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) })
        }
        val notification = NotificationCompat.Builder(context, "cliente_status")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    } catch (_: Exception) { }
}


private fun createOrderReceiptPdf(context: Context, order: Order, companyName: String): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val cp = context.getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
        val black = android.graphics.Color.BLACK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = black; textSize = 11f }
        var y = 42f
        val left = 36f
        val right = 559f

        // Logo do aplicativo no comprovante.
        try {
            context.getDrawable(R.drawable.ic_app_logo)?.let { logo ->
                logo.setBounds(36, 24, 96, 84)
                logo.draw(canvas)
            }
        } catch (_: Exception) { }

        fun line(text: String, size: Float = 11f, bold: Boolean = false, indent: Float = left) {
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(text.take(92), indent, y, paint)
            y += size + 5f
        }
        fun wrapped(text: String, size: Float = 10.5f, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val words = text.split(" ")
            var current = ""
            for (word in words) {
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) > (right - left) && current.isNotBlank()) {
                    canvas.drawText(current, left, y, paint)
                    y += size + 4f
                    current = word
                } else current = candidate
            }
            if (current.isNotBlank()) {
                canvas.drawText(current, left, y, paint)
                y += size + 4f
            }
        }
        fun section(title: String) {
            y += 4f
            line(title, 12f, true)
            y += 1f
        }

        line("COMPROVANTE DE PEDIDO", 18f, true, 108f)
        line("Água & Gás Express", 12f, true, 108f)
        y = maxOf(y, 91f)
        canvas.drawLine(left, y, right, y, paint)
        y += 15f

        section("DADOS DA EMPRESA")
        wrapped("Razão/Nome: ${companyName.ifBlank { "Empresa" }}")
        val responsible = cp.getString("company_responsible", "") ?: ""
        val phone = cp.getString("company_phone", "") ?: ""
        val email = cp.getString("company_email", "") ?: ""
        val cep = cp.getString("company_cep", "") ?: ""
        val street = cp.getString("company_street", "") ?: ""
        val number = cp.getString("company_number", "") ?: ""
        val neighborhood = cp.getString("company_neighborhood", "") ?: ""
        val city = cp.getString("company_city", "") ?: ""
        val state = cp.getString("company_state", "") ?: ""
        val code = cp.getString("company_code", "") ?: ""
        if (responsible.isNotBlank()) wrapped("Responsável: $responsible")
        if (phone.isNotBlank()) wrapped("Telefone: $phone")
        if (email.isNotBlank()) wrapped("E-mail: $email")
        val companyAddressParts = listOf(
            if (cep.isNotBlank()) "CEP $cep" else "",
            listOf(street, number).filter { it.isNotBlank() }.joinToString(", "),
            neighborhood,
            listOf(city, state).filter { it.isNotBlank() }.joinToString(" - ")
        ).filter { it.isNotBlank() }
        if (companyAddressParts.isNotEmpty()) wrapped("Endereço: ${companyAddressParts.joinToString(" — ")}")
        if (code.isNotBlank()) wrapped("Código da empresa: $code")

        section("DADOS DO CLIENTE")
        wrapped("Nome: ${order.customer.ifBlank { "Não informado" }}")
        wrapped("Telefone: ${order.phone.ifBlank { "Não informado" }}")
        if (order.customerDocNumber.isNotBlank()) {
            wrapped("${order.customerDocType.ifBlank { "CPF/CNPJ" }}: ${formatDocument(order.customerDocType, order.customerDocNumber)}")
        }
        wrapped("Endereço de entrega: ${order.address.ifBlank { "Não informado" }}")

        section("DADOS DO PEDIDO")
        line("Pedido: #${order.orderNumber.toString().padStart(2, '0')}", 12f, true)
        val orderDate = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
            .format(java.util.Date(if (order.receiptGeneratedAt > 0) order.receiptGeneratedAt else System.currentTimeMillis()))
        wrapped("Data do pedido: $orderDate")
        if (order.deliveryStartedAt > 0L) {
            val departure = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                .format(java.util.Date(order.deliveryStartedAt))
            wrapped("Data de saída para entrega: $departure")
        } else {
            wrapped("Data de saída para entrega: ainda não registrada")
        }
        wrapped("Itens: ${order.items}")
        wrapped("Forma de pagamento: ${order.payment}")
        if (order.payment == "Dinheiro" && order.cashGiven > 0) wrapped("Valor recebido: ${money(order.cashGiven)}")
        line("VALOR TOTAL: ${money(order.total)}", 16f, true)
        line(if (order.paid) "Status: PAGAMENTO CONFIRMADO" else "Status: AGUARDANDO PAGAMENTO", 11f, true)

        y += 8f
        canvas.drawLine(left, y, right, y, paint)
        y += 16f
        line("Documento interno do aplicativo", 9f)
        line("Não substitui documento fiscal oficial.", 9f)
        line("A identificação CPF/CNPJ é opcional e só aparece se o cliente a informar.", 8.5f)

        pdf.finishPage(page)
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "comprovante_pedido_${order.orderNumber.toString().padStart(2, '0')}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AguaGasExpress")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: return null
        pdf.close()
        uri
    } catch (_: Exception) { null }
}


private fun shareOrderReceipt(context: Context, order: Order, companyName: String) {
    val uri = createOrderReceiptPdf(context, order, companyName)
    if (uri == null) {
        android.widget.Toast.makeText(context, "O PDF exige Android 10 ou superior nesta versão.", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar nota do pedido"))
    } catch (_: Exception) { }
}

private fun customerDocumentId(phone: String): String {
    return hashPassword(phone.filter(Char::isDigit)).take(32)
}

private fun hashPassword(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun money(value: Double): String =
    "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value)

private fun normalizeDocument(value: String): String = value.filter(Char::isDigit).take(14)

private fun isValidOptionalDocument(type: String, value: String): Boolean {
    if (value.isBlank()) return true
    val digits = normalizeDocument(value)
    return when (type.uppercase(Locale.ROOT)) {
        "CPF" -> digits.length == 11
        "CNPJ" -> digits.length == 14
        else -> false
    }
}

private fun formatDocument(type: String, value: String): String {
    val d = normalizeDocument(value)
    return when (type.uppercase(Locale.ROOT)) {
        "CPF" -> if (d.length == 11) d.replace(Regex("(\\d{3})(\\d{3})(\\d{3})(\\d{2})"), "$1.$2.$3-$4") else d
        "CNPJ" -> if (d.length == 14) d.replace(Regex("(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})"), "$1.$2.$3/$4-$5") else d
        else -> d
    }
}


private fun playOrderAlertSound(context: Context) {
    try {
        val ringtone = android.media.RingtoneManager.getRingtone(
            context,
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        )
        ringtone?.play()
    } catch (_: Exception) { }
}

private fun announceNewOrder(context: Context) {
    try {
        lateinit var speech: android.speech.tts.TextToSpeech
        speech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try {
                    val languageStatus = speech.setLanguage(java.util.Locale("pt", "BR"))
                    if (languageStatus == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        playOrderAlertSound(context)
                    } else {
                        val result = speech.speak(
                            "Chegou mais um pedido!",
                            android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                            null,
                            "aguagas_novo_pedido"
                        )
                        if (result == android.speech.tts.TextToSpeech.ERROR) playOrderAlertSound(context)
                    }
                } catch (_: Exception) { playOrderAlertSound(context) }
            } else {
                playOrderAlertSound(context)
            }
        }
    } catch (_: Exception) {
        playOrderAlertSound(context)
    }
}

private const val COMBO_WATER_NAME = "Estrela • Safira • Diamantina"
private const val LEGACY_COMBO_WATER_NAME = "Água (Estrela, Safira e Diamantina)"

private fun defaultProducts(gasPrice: Double = 95.0, comboWaterPrice: Double = 5.50): List<Product> = listOf(
    Product("Estrela", comboWaterPrice),
    Product("Safira", comboWaterPrice),
    Product("Diamantina", comboWaterPrice),
    Product("Cristalina", 8.00),
    Product("Santa Maria", 6.50),
    Product("Santa Joana", 12.00),
    Product("Gás", gasPrice, true)
)

private fun loadProducts(prefs: android.content.SharedPreferences): List<Product> {
    val saved = prefs.getString("products_json", null) ?: return defaultProducts()
    return try {
        val array = JSONArray(saved)
        val loaded = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name", "").trim()
            val price = item.optDouble("price", -1.0)
            if (name.isBlank() || price < 0.0) null
            else Product(name, price, item.optBoolean("isGas", name.equals("Gás", ignoreCase = true)))
        }.distinctBy { it.name.lowercase(Locale.ROOT) }
        // Preserve the exact saved catalog, including an intentionally empty list or deleted brands.
        if (loaded.isNotEmpty() || array.length() == 0) loaded else defaultProducts()
    } catch (_: Exception) {
        defaultProducts()
    }
}

private fun saveProducts(prefs: android.content.SharedPreferences, products: List<Product>) {
    val array = JSONArray()
    products.forEach { array.put(JSONObject().put("name", it.name).put("price", it.price).put("isGas", it.isGas)) }
    prefs.edit().putString("products_json", array.toString()).apply()
}

private fun loadOrders(prefs: android.content.SharedPreferences): List<Order> {
    val saved = prefs.getString("orders_json", null) ?: return emptyList()
    return try {
        val array = JSONArray(saved)
        val loaded = (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Order(
                id = item.getLong("id"),
                customer = item.getString("customer"),
                phone = item.getString("phone"),
                customerDocType = item.optString("customerDocType", ""),
                customerDocNumber = item.optString("customerDocNumber", ""),
                address = item.getString("address"),
                items = item.getString("items"),
                total = item.getDouble("total"),
                payment = item.getString("payment"),
                cashGiven = item.optDouble("cashGiven", 0.0),
                status = item.getString("status"),
                paid = item.optBoolean("paid", false),
                waterQty = item.optInt("waterQty", 0),
                gasQty = item.optInt("gasQty", 0),
                demo = item.optBoolean("demo", false),
                pixReported = item.optBoolean("pixReported", false),
                orderNumber = item.optInt("orderNumber", 0),
                customerUid = item.optString("customerUid", ""),
                customerFcmToken = item.optString("customerFcmToken", ""),
                trackingLat = if (item.has("trackingLat") && !item.isNull("trackingLat")) item.optDouble("trackingLat") else null,
                trackingLng = if (item.has("trackingLng") && !item.isNull("trackingLng")) item.optDouble("trackingLng") else null,
                trackingActive = item.optBoolean("trackingActive", false),
                trackingPath = try { val path = item.optJSONArray("trackingPath") ?: JSONArray(); (0 until path.length()).mapNotNull { i -> path.optJSONObject(i)?.let { mapOf("lat" to it.optDouble("lat"), "lng" to it.optDouble("lng")) } } } catch (_: Exception) { emptyList() },
                originAddress = item.optString("originAddress", "")
            )
        }.sortedBy { it.id }
        var nextLegacyNumber = 0
        loaded.map { order ->
            if (order.demo) order else {
                nextLegacyNumber = maxOf(nextLegacyNumber + 1, order.orderNumber)
                if (order.orderNumber <= 0) order.copy(orderNumber = nextLegacyNumber) else order
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveOrders(prefs: android.content.SharedPreferences, orders: List<Order>) {
    val array = JSONArray()
    orders.forEach {
        array.put(
            JSONObject()
                .put("id", it.id).put("customer", it.customer).put("phone", it.phone)
                .put("customerDocType", it.customerDocType).put("customerDocNumber", it.customerDocNumber)
                .put("address", it.address).put("items", it.items).put("total", it.total)
                .put("payment", it.payment).put("cashGiven", it.cashGiven)
                .put("status", it.status).put("paid", it.paid)
                .put("waterQty", it.waterQty).put("gasQty", it.gasQty).put("demo", it.demo)
                .put("pixReported", it.pixReported).put("orderNumber", it.orderNumber)
                .put("customerUid", it.customerUid)
                .put("customerFcmToken", it.customerFcmToken)
                .put("trackingLat", it.trackingLat).put("trackingLng", it.trackingLng)
                .put("trackingActive", it.trackingActive)
                .put("trackingPath", JSONArray().apply { it.trackingPath.forEach { point -> put(JSONObject().put("lat", point["lat"] ?: 0.0).put("lng", point["lng"] ?: 0.0)) } })
                .put("receiptGeneratedAt", it.receiptGeneratedAt)
                .put("paymentConfirmedAt", it.paymentConfirmedAt)
                .put("approvalSentAt", it.approvalSentAt)
                .put("deliveryStartedAt", it.deliveryStartedAt)
                .put("arrivalAt", it.arrivalAt)
                .put("originAddress", it.originAddress)
        )
    }
    prefs.edit().putString("orders_json", array.toString()).apply()
}

private fun orderToFirestoreMap(order: Order): Map<String, Any> = mapOf(
    "id" to order.id,
    "customer" to order.customer,
    "phone" to order.phone,
    "customerDocType" to order.customerDocType,
    "customerDocNumber" to order.customerDocNumber,
    "address" to order.address,
    "items" to order.items,
    "total" to order.total,
    "payment" to order.payment,
    "cashGiven" to order.cashGiven,
    "status" to order.status,
    "paid" to order.paid,
    "waterQty" to order.waterQty,
    "gasQty" to order.gasQty,
    "demo" to order.demo,
    "pixReported" to order.pixReported,
    "orderNumber" to order.orderNumber,
    "customerUid" to order.customerUid,
    "customerFcmToken" to order.customerFcmToken,
    "trackingActive" to order.trackingActive,
    "trackingPath" to order.trackingPath,
    "trackingLat" to (order.trackingLat ?: 0.0),
    "trackingLng" to (order.trackingLng ?: 0.0),
    "assignedDriver" to order.assignedDriver,
    "companyId" to order.companyId,
    "receiptGeneratedAt" to order.receiptGeneratedAt,
    "paymentConfirmedAt" to order.paymentConfirmedAt,
    "approvalSentAt" to order.approvalSentAt,
    "deliveryStartedAt" to order.deliveryStartedAt,
    "arrivalAt" to order.arrivalAt,
    "originAddress" to order.originAddress
)

private fun firestoreDocumentToOrder(documentId: String, data: Map<String, Any>): Order? = try {
    Order(
        id = (data["id"] as? Number)?.toLong() ?: documentId.toLong(),
        customer = data["customer"] as? String ?: "",
        phone = data["phone"] as? String ?: "",
        customerDocType = data["customerDocType"] as? String ?: "",
        customerDocNumber = data["customerDocNumber"] as? String ?: "",
        address = data["address"] as? String ?: "",
        items = data["items"] as? String ?: "",
        total = (data["total"] as? Number)?.toDouble() ?: 0.0,
        payment = data["payment"] as? String ?: "Dinheiro",
        cashGiven = (data["cashGiven"] as? Number)?.toDouble() ?: 0.0,
        status = data["status"] as? String ?: "Pendente",
        paid = data["paid"] as? Boolean ?: false,
        waterQty = (data["waterQty"] as? Number)?.toInt() ?: 0,
        gasQty = (data["gasQty"] as? Number)?.toInt() ?: 0,
        demo = data["demo"] as? Boolean ?: false,
        pixReported = data["pixReported"] as? Boolean ?: false,
        orderNumber = (data["orderNumber"] as? Number)?.toInt() ?: 0,
        customerUid = data["customerUid"] as? String ?: "",
        customerFcmToken = data["customerFcmToken"] as? String ?: "",
        trackingLat = (data["trackingLat"] as? Number)?.toDouble()?.takeIf { it != 0.0 },
        trackingLng = (data["trackingLng"] as? Number)?.toDouble()?.takeIf { it != 0.0 },
        trackingActive = data["trackingActive"] as? Boolean ?: false,
        assignedDriver = (data["assignedDriver"] as? Number)?.toInt() ?: 0,
        companyId = data["companyId"] as? String ?: "",
        receiptGeneratedAt = (data["receiptGeneratedAt"] as? Number)?.toLong() ?: 0L,
        paymentConfirmedAt = (data["paymentConfirmedAt"] as? Number)?.toLong() ?: 0L,
        approvalSentAt = (data["approvalSentAt"] as? Number)?.toLong() ?: 0L,
        deliveryStartedAt = (data["deliveryStartedAt"] as? Number)?.toLong() ?: 0L,
        arrivalAt = (data["arrivalAt"] as? Number)?.toLong() ?: 0L,
        originAddress = data["originAddress"] as? String ?: "",
        trackingPath = (data["trackingPath"] as? List<*>)?.mapNotNull { point ->
            val map = point as? Map<*, *> ?: return@mapNotNull null
            val lat = (map["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (map["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            mapOf("lat" to lat, "lng" to lng)
        } ?: emptyList()
    )
} catch (_: Exception) { null }

private fun productsJson(products: List<Product>): String {
    val array = JSONArray()
    products.forEach { array.put(JSONObject().put("name", it.name).put("price", it.price).put("isGas", it.isGas)) }
    return array.toString()
}

private fun authenticateBiometric(
    context: Context,
    title: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        onError("A entrada por digital exige Android 9 ou superior neste aplicativo. Use a senha.")
        return
    }
    val activity = context as? ComponentActivity
    if (activity == null) {
        onError("Não foi possível abrir a autenticação biométrica. Use a senha.")
        return
    }
    try {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Confirme sua identidade com a biometria cadastrada neste aparelho")
            .setNegativeButton("Cancelar", activity.mainExecutor, DialogInterface.OnClickListener { _, _ -> })
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onError(errString?.toString() ?: "Autenticação cancelada. Use a senha se preferir.")
                }
            }
        )
    } catch (_: Exception) {
        onError("Não foi possível iniciar a digital. Confira se há biometria cadastrada no aparelho.")
    }
}


private suspend fun lookupCep(cep: String): JSONObject? = withContext(Dispatchers.IO) {
    val digits = cep.filter(Char::isDigit)
    if (digits.length != 8) return@withContext null
    try {
        val connection = (URL("https://viacep.com.br/ws/$digits/json/").openConnection() as HttpURLConnection)
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) }
    } catch (_: Exception) { null }
}

@Composable
private fun CommercialCompanyGate(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val firestore = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val inviteProfile = prefs.getString("invite_profile", "").orEmpty()
    val invitedCode = prefs.getString("invite_company_code", "").orEmpty()

    var mode by remember {
        mutableStateOf(
            when {
                inviteProfile == "proprietario" -> "master"
                inviteProfile == "empresa" && prefs.getString("company_id", "").isNullOrBlank() -> "company_email"
                inviteProfile == "cliente" || inviteProfile == "entregador" -> "join"
                !prefs.getString("company_id", "").isNullOrBlank() && prefs.getBoolean("company_authenticated", false) -> "app"
                !prefs.getString("company_id", "").isNullOrBlank() -> "login"
                else -> "master"
            }
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var companyEmailVerified by remember { mutableStateOf(false) }
    var companyName by remember { mutableStateOf("") }
    var responsible by remember { mutableStateOf("") }
    var companyPhone by remember { mutableStateOf("") }
    var companyCep by remember { mutableStateOf("") }
    var companyStreet by remember { mutableStateOf("") }
    var companyNumber by remember { mutableStateOf("") }
    var companyNeighborhood by remember { mutableStateOf("") }
    var companyCity by remember { mutableStateOf("") }
    var companyState by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var loginEmail by remember { mutableStateOf(prefs.getString("company_email", "") ?: "") }
    var loginPassword by remember { mutableStateOf("") }
    var trialDays by remember { mutableStateOf("30") }
    var companyCepSearching by remember { mutableStateOf(false) }
    var masterPassword by remember { mutableStateOf("") }
    var masterPasswordConfirm by remember { mutableStateOf("") }
    var developerPasswordSetup by remember { mutableStateOf(prefs.getString("developer_password_hash", "").isNullOrBlank()) }
    var showMasterPassword by remember { mutableStateOf(false) }
    var showMasterPasswordConfirm by remember { mutableStateOf(false) }
    var showCompanyPassword by remember { mutableStateOf(false) }
    var showCompanyConfirmPassword by remember { mutableStateOf(false) }
    var showCompanyLoginPassword by remember { mutableStateOf(false) }
    var masterExtensionDays by remember { mutableStateOf("7") }
    var masterLoggedIn by remember { mutableStateOf(false) }
    var masterRegistration by remember { mutableStateOf(false) }
    var masterPermanent by remember { mutableStateOf(false) }
    var masterCompanyCode by remember { mutableStateOf("") }
    var masterCompanyName by remember { mutableStateOf("") }
    var masterCompanyEmail by remember { mutableStateOf("") }
    var masterStatus by remember { mutableStateOf("") }
    var masterCompanyPhone by remember { mutableStateOf("") }
    var masterCompanyDetailsOpen by remember { mutableStateOf(false) }
    var masterCompanyId by remember { mutableStateOf("") }
    var masterLicensePermanent by remember { mutableStateOf(false) }
    var masterLicenseExpiresAt by remember { mutableStateOf(0L) }
    var masterLicenseStatus by remember { mutableStateOf("") }
    var masterLicenseDays by remember { mutableStateOf(0) }
    var masterCompanyActivated by remember { mutableStateOf(false) }
    var masterCompanyActive by remember { mutableStateOf(true) }
    var masterCompanyCreatedAt by remember { mutableStateOf(0L) }
    var masterCompanyVersion by remember { mutableStateOf("11.0-test") }
    var masterCompanyEmailDraft by remember { mutableStateOf("") }
    var masterCompanyMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun ensureFirebase(onReady: () -> Unit) {
        // Todo acesso ao Firestore precisa chegar autenticado.
        // Não continuamos silenciosamente quando o login anônimo falha,
        // pois isso transforma o erro real em PERMISSION_DENIED.
        val current = auth.currentUser
        if (current != null) { onReady(); return }
        loading = true
        auth.signInAnonymously()
            .addOnSuccessListener {
                loading = false
                onReady()
            }
            .addOnFailureListener { e ->
                loading = false
                error = "Firebase Auth não autorizou o acesso. Ative Authentication > Sign-in method > Anonymous no projeto Firebase.\n${e.localizedMessage ?: "Falha no login anônimo."}"
            }
    }

    fun loadLatestMasterCompany() {
        ensureFirebase {
            val uid = auth.currentUser?.uid.orEmpty()
            if (uid.isBlank()) return@ensureFirebase
            firestore.collection("companies").whereEqualTo("ownerUid", uid).get()
                .addOnSuccessListener { snap ->
                    val doc = snap.documents.maxByOrNull { it.getLong("createdAt") ?: 0L } ?: return@addOnSuccessListener
                    masterCompanyId = doc.id
                    masterCompanyCode = doc.getString("companyCode") ?: ""
                    masterCompanyName = doc.getString("name") ?: "Empresa"
                    masterCompanyEmail = doc.getString("email") ?: doc.getString("authorizedEmail") ?: ""
                    masterCompanyPhone = doc.getString("companyPhone") ?: ""
                    masterLicensePermanent = doc.getBoolean("licensePermanent") ?: false
                    masterLicenseExpiresAt = doc.getLong("licenseExpiresAt") ?: 0L
                    masterLicenseStatus = doc.getString("licenseStatus") ?: if (masterLicensePermanent) "PERMANENT" else "TRIAL"
                    masterLicenseDays = (doc.getLong("licenseDays") ?: 0L).toInt()
                    masterCompanyActivated = doc.getBoolean("activated") ?: false
                    masterCompanyActive = doc.getBoolean("active") != false
                    masterCompanyCreatedAt = doc.getLong("createdAt") ?: 0L
                    masterCompanyVersion = doc.getString("appVersion") ?: "11.0-test"
                }
        }
    }

    fun changeAuthorizedEmail() {
        val id = masterCompanyId
        val newEmail = masterCompanyEmailDraft.trim()
        if (id.isBlank()) { masterCompanyMessage = "Empresa não encontrada."; return }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) { masterCompanyMessage = "Informe um e-mail válido."; return }
        if (masterCompanyActivated) {
            masterCompanyMessage = "Esta empresa já criou a conta. A troca do e-mail de login precisa ser feita pela conta autenticada da empresa para não perder o acesso."
            return
        }
        loading = true
        firestore.collection("companies").document(id).update(mapOf("email" to newEmail, "authorizedEmail" to newEmail))
            .addOnSuccessListener {
                masterCompanyEmail = newEmail
                masterCompanyEmailDraft = newEmail
                masterCompanyMessage = "E-mail autorizado atualizado. O link continuará apontando para a mesma empresa."
                loading = false
            }
            .addOnFailureListener { e -> loading = false; masterCompanyMessage = "Não foi possível alterar o e-mail: ${e.localizedMessage ?: "erro no Firebase"}" }
    }



    fun makeCompanyPermanent() {
        val id = masterCompanyId
        if (id.isBlank()) { masterCompanyMessage = "Empresa não encontrada."; return }
        loading = true
        firestore.collection("companies").document(id).update(mapOf(
            "licensePermanent" to true,
            "licenseStatus" to "PERMANENT",
            "licenseDays" to 0,
            "licenseExpiresAt" to 0L,
            "active" to true
        )).addOnSuccessListener {
            masterLicensePermanent = true
            masterLicenseStatus = "PERMANENT"
            masterLicenseDays = 0
            masterLicenseExpiresAt = 0L
            masterCompanyActive = true
            masterCompanyMessage = "Teste encerrado. Empresa ativada como licença definitiva."
            loading = false
        }.addOnFailureListener { e -> loading = false; masterCompanyMessage = "Não foi possível finalizar o teste: ${e.localizedMessage ?: "erro no Firebase"}" }
    }

    fun extendCompanyTrial() {
        val id = masterCompanyId
        val extra = masterExtensionDays.toIntOrNull() ?: 0
        if (id.isBlank()) { masterCompanyMessage = "Empresa não encontrada."; return }
        if (extra <= 0) { masterCompanyMessage = "Informe uma quantidade de dias maior que zero."; return }
        if (masterLicensePermanent) { masterCompanyMessage = "Esta empresa já possui licença definitiva."; return }
        val now = System.currentTimeMillis()
        val base = maxOf(now, masterLicenseExpiresAt)
        val newExpiry = base + extra * 24L * 60L * 60L * 1000L
        val currentDays = maxOf(0, ((base - now + 86_399_999L) / 86_400_000L).toInt())
        val newDays = currentDays + extra
        loading = true
        firestore.collection("companies").document(id).update(mapOf(
            "licensePermanent" to false,
            "licenseStatus" to "TRIAL",
            "licenseDays" to newDays,
            "licenseExpiresAt" to newExpiry,
            "active" to true
        )).addOnSuccessListener {
            masterLicensePermanent = false
            masterLicenseStatus = "TRIAL"
            masterLicenseDays = newDays
            masterLicenseExpiresAt = newExpiry
            masterCompanyActive = true
            masterCompanyMessage = "Teste prorrogado por mais $extra dias."
            loading = false
        }.addOnFailureListener { e -> loading = false; masterCompanyMessage = "Não foi possível acrescentar dias: ${e.localizedMessage ?: "erro no Firebase"}" }
    }

    fun shareCompanyLink(code: String, emailForMessage: String = "", platform: String = "") {
        val normalized = normalizeCompanyCode(code)
        if (normalized.length < 6) { masterStatus = "Nenhum link válido foi gerado ainda."; return }
        val direct = makeInviteLink("empresa", normalized)
        val text = "🏪 Água & Gás Express — LINK ÚNICO DA EMPRESA\n\n$direct\n\nAo abrir, a página identifica o convite e orienta o acesso para Android e Notebook.\nE-mail autorizado: $emailForMessage"
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Água & Gás Express — Acesso da Empresa")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Enviar link da empresa"))
            masterStatus = "Link da empresa preparado para envio."
        } catch (_: Exception) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link da empresa", text))
            masterStatus = "Link copiado para a área de transferência."
        }
    }

    fun loadCompanyLocally(doc: com.google.firebase.firestore.DocumentSnapshot, role: String = "admin") {
        val expiry = doc.getLong("licenseExpiresAt") ?: 0L
        val permanent = doc.getBoolean("licensePermanent") ?: false
        prefs.edit()
            .putString("company_id", doc.id)
            .putString("company_name", doc.getString("name") ?: "Empresa")
            .putString("company_code", doc.getString("companyCode") ?: "")
            .putString("license_key", doc.getString("licenseKey") ?: "")
            .putString("company_responsible", doc.getString("responsible") ?: "")
            .putString("company_phone", doc.getString("companyPhone") ?: "")
            .putString("company_email", doc.getString("email") ?: "")
            .putString("company_cep", doc.getString("companyCep") ?: "")
            .putString("company_street", doc.getString("companyStreet") ?: "")
            .putString("company_number", doc.getString("companyNumber") ?: "")
            .putString("company_neighborhood", doc.getString("companyNeighborhood") ?: "")
            .putString("company_city", doc.getString("companyCity") ?: "")
            .putString("company_state", doc.getString("companyState") ?: "")
            .putString("company_origin_address", doc.getString("originAddress") ?: "")
            .putLong("license_expires_at", expiry)
            .putBoolean("license_permanent", permanent)
            .putString("company_role", role)
            .putString("admin_password_hash", doc.getString("adminPasswordHash") ?: prefs.getString("admin_password_hash", ""))
            .putBoolean("company_authenticated", true)
            .remove("invite_profile").remove("invite_company_code").apply()
    }

    LaunchedEffect(mode, inviteProfile, invitedCode) {
        if (mode == "join" && invitedCode.isNotBlank()) {
            loading = true
            ensureFirebase {
                firestore.collection("companies")
                    .whereEqualTo("companyCode", normalizeCompanyCode(invitedCode))
                    .limit(1)
                    .get()
                    .addOnSuccessListener { snap ->
                        val doc = snap.documents.firstOrNull()
                        if (doc == null) {
                            loading = false
                            error = "Convite da empresa inválido."
                        } else {
                            val active = doc.getBoolean("active") != false
                            val permanent = doc.getBoolean("licensePermanent") ?: false
                            val expiresAt = doc.getLong("licenseExpiresAt") ?: 0L
                            val expired = !permanent && expiresAt > 0L && System.currentTimeMillis() > expiresAt
                            if (!active) {
                                loading = false
                                error = "Esta empresa está desativada. Solicite suporte ao proprietário."
                            } else if (expired) {
                                loading = false
                                error = "A licença desta empresa expirou. Solicite suporte ao proprietário."
                            } else {
                                val role = if (inviteProfile == "entregador") "delivery" else "customer"
                                loadCompanyLocally(doc, role)
                                loading = false
                                mode = "app"
                                error = ""
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        loading = false
                        error = e.localizedMessage ?: "Não foi possível consultar a empresa."
                    }
            }
        }
    }

    if (mode == "app") { AguaGasApp(prefs); return }

    Column(Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_app_logo), contentDescription = "Ícone Água & Gás Express", modifier = Modifier.size(82.dp))
        Text("COMÉRCIO EXPRESS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("GESTÃO DE ENTREGAS", color = Orange, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(22.dp))

        LaunchedEffect(masterLoggedIn) {
            if (masterLoggedIn) loadLatestMasterCompany()
        }

        if (mode == "master") {
            if (!masterLoggedIn) {
                Text("DESENVOLVEDOR / PROPRIETÁRIO", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (developerPasswordSetup) "Primeiro acesso: crie sua senha privada. Ela não será enviada para nenhuma empresa." else "Acesso exclusivo do desenvolvedor.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(masterPassword, { masterPassword = it }, label = { Text(if (developerPasswordSetup) "Criar senha do desenvolvedor" else "Senha do desenvolvedor") }, singleLine = true, visualTransformation = if (showMasterPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showMasterPassword = !showMasterPassword }) { Text(if (showMasterPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } }, modifier = Modifier.fillMaxWidth())
                if (developerPasswordSetup) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(masterPasswordConfirm, { masterPasswordConfirm = it }, label = { Text("Confirmar senha do desenvolvedor") }, singleLine = true, visualTransformation = if (showMasterPasswordConfirm) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showMasterPasswordConfirm = !showMasterPasswordConfirm }) { Text(if (showMasterPasswordConfirm) "OCULTAR" else "MOSTRAR", color = LightBlue) } }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Button(enabled = !loading, onClick = {
                    when {
                        masterPassword.length < 6 -> error = "A senha do desenvolvedor deve ter pelo menos 6 caracteres."
                        developerPasswordSetup && masterPassword != masterPasswordConfirm -> error = "As senhas não coincidem."
                        developerPasswordSetup -> { prefs.edit().putString("developer_password_hash", hashPassword(masterPassword)).apply(); developerPasswordSetup = false; masterLoggedIn = true; masterPassword = ""; masterPasswordConfirm = "" }
                        hashPassword(masterPassword) == prefs.getString("developer_password_hash", "") -> { masterLoggedIn = true; masterPassword = ""; error = "" }
                        else -> error = "Senha do desenvolvedor incorreta."
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (developerPasswordSetup) "CRIAR SENHA E ENTRAR" else "ENTRAR COMO DESENVOLVEDOR") }
            } else if (masterCompanyDetailsOpen) {
                Text("🏢 INFORMAÇÕES DA EMPRESA", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF173B62)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(masterCompanyName.ifBlank { "Empresa" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("E-mail autorizado: $masterCompanyEmail", color = LightBlue)
                        Text("Telefone: ${masterCompanyPhone.ifBlank { "não informado" }}", color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        val now = System.currentTimeMillis()
                        val remaining = if (masterLicensePermanent) -1 else maxOf(0L, (masterLicenseExpiresAt - now + 86_399_999L) / 86_400_000L)
                        val situation = when { !masterCompanyActive -> "INATIVA"; !masterCompanyActivated -> "AGUARDANDO CADASTRO"; masterLicensePermanent -> "ATIVA — DEFINITIVA"; remaining <= 0 -> "EXPIRADA"; else -> "ATIVA — EM TESTE" }
                        Text("Situação: $situation", color = if (situation.startsWith("ATIVA")) Color(0xFF86EFAC) else Color(0xFFFFD0A8), fontWeight = FontWeight.Bold)
                        Text("Licença: ${if (masterLicensePermanent) "Definitiva" else "${masterLicenseDays} dias"}", color = Color.White)
                        Text(if (masterLicensePermanent) "Validade: sem vencimento" else "Dias restantes: $remaining", color = Color.White)
                        if (masterLicenseExpiresAt > 0L) Text("Vencimento: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(masterLicenseExpiresAt))}", color = Color.White)
                        Text("Versão autorizada: $masterCompanyVersion", color = Color.White)
                        if (masterCompanyCreatedAt > 0L) Text("Cadastrada em: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(masterCompanyCreatedAt))}", color = Color.White)
                        Text("Código interno: $masterCompanyCode", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Trocar e-mail autorizado", color = LightBlue, fontWeight = FontWeight.Bold)
                OutlinedTextField(masterCompanyEmailDraft, { masterCompanyEmailDraft = it; masterCompanyMessage = "" }, label = { Text("Novo e-mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(enabled = !loading, onClick = { changeAuthorizedEmail() }, modifier = Modifier.fillMaxWidth()) { Text("ALTERAR E-MAIL") }
                if (masterCompanyMessage.isNotBlank()) Text(masterCompanyMessage, color = LightBlue, textAlign = TextAlign.Center, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("CONTROLE DA LICENÇA", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                if (!masterLicensePermanent) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(masterExtensionDays, { masterExtensionDays = it.filter(Char::isDigit).take(3) }, label = { Text("Dias a acrescentar") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        Button(enabled = !loading, onClick = { extendCompanyTrial() }, modifier = Modifier.weight(1f)) { Text("+ DIAS") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(enabled = !loading, onClick = { makeCompanyPermanent() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("✓ ENCERRAR TESTE E TORNAR DEFINITIVA") }
                } else {
                    Text("Esta empresa já está em licença definitiva.", color = Color(0xFF86EFAC), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { shareCompanyLink(masterCompanyCode, masterCompanyEmail) }, modifier = Modifier.fillMaxWidth()) { Text("🔗 ENVIAR LINK ÚNICO DA EMPRESA") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { masterCompanyDetailsOpen = false; masterCompanyMessage = "" }) { Text("VOLTAR", color = Color.White) }
            } else {
                Text("👑 PAINEL DO PROPRIETÁRIO", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Cadastre a empresa com nome, e-mail autorizado e telefone. O e-mail continua sendo a autorização para ela abrir a conta.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { masterRegistration = true; mode = "register"; error = ""; masterStatus = ""; masterCompanyEmail = ""; masterCompanyName = ""; masterCompanyPhone = ""; trialDays = "30"; masterPermanent = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("🏪 CADASTRAR EMPRESA", fontWeight = FontWeight.ExtraBold) }
                if (masterCompanyCode.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { masterCompanyDetailsOpen = true; masterCompanyEmailDraft = masterCompanyEmail; masterCompanyMessage = "" }) {
                        Text(masterCompanyName.ifBlank { "Empresa" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("E-mail autorizado: $masterCompanyEmail", color = LightBlue, fontSize = 12.sp)
                    Text("Telefone: ${masterCompanyPhone.ifBlank { "não informado" }}", color = Color.White, fontSize = 12.sp)
                    Button(onClick = { shareCompanyLink(masterCompanyCode, masterCompanyEmail) }, modifier = Modifier.fillMaxWidth()) { Text("🔗 ENVIAR LINK ÚNICO DA EMPRESA") }
                }
                if (masterStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(masterStatus, color = LightBlue, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { masterLoggedIn = false; masterPassword = "" }) { Text("SAIR DO PROPRIETÁRIO", color = Color.White) }
            }
        } else if (mode == "company_email") {
            Text("🏢 ACESSO DA EMPRESA", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Digite o e-mail que o proprietário autorizou. O link sozinho não dá acesso à conta.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(email, { email = it }, label = { Text("E-mail autorizado pela empresa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(enabled = !loading, onClick = {
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { error = "Informe um e-mail válido."; return@Button }
                ensureFirebase {
                    firestore.collection("companies").whereEqualTo("email", email.trim()).limit(20).get().addOnSuccessListener { snap ->
                        val doc = snap.documents.filter { !(it.getBoolean("activated") ?: false) }.maxByOrNull { it.getLong("createdAt") ?: 0L }
                        if (doc == null) error = "Este e-mail ainda não foi autorizado pelo proprietário."
                        else { prefs.edit().putString("invite_company_code", doc.getString("companyCode") ?: "").putString("pending_company_id", doc.id).apply(); companyEmailVerified = true; mode = "register"; error = "" }
                    }.addOnFailureListener { e -> error = e.localizedMessage ?: "Não foi possível consultar a autorização." }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "CONSULTANDO..." else "CONTINUAR COM ESTE E-MAIL") }
        } else if (mode == "register") {
            if (masterRegistration) {
                Text("CADASTRAR EMPRESA", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Informe o nome, e-mail autorizado e telefone. O e-mail continua sendo o que libera a empresa para abrir a conta.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(masterCompanyName, { masterCompanyName = it }, label = { Text("Nome da empresa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(masterCompanyEmail, { masterCompanyEmail = it }, label = { Text("E-mail autorizado") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(masterCompanyPhone, { masterCompanyPhone = it }, label = { Text("Telefone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("LICENÇA", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("10", "15", "30").forEach { days -> Button(onClick = { trialDays = days; masterPermanent = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (!masterPermanent && trialDays == days) Green else Color(0xFF315A85))) { Text("$days dias") } }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { masterPermanent = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (masterPermanent) Green else Color(0xFF315A85))) { Text("♾️ LICENÇA DEFINITIVA") }
                Spacer(Modifier.height(12.dp))
                Button(enabled = !loading, onClick = {
                    when {
                        masterCompanyName.trim().length < 2 -> { error = "Informe o nome da empresa."; return@Button }
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(masterCompanyEmail.trim()).matches() -> { error = "Informe um e-mail válido."; return@Button }
                        masterCompanyPhone.filter(Char::isDigit).length < 8 -> { error = "Informe um telefone válido."; return@Button }
                    }
                    ensureFirebase {
                        val code = generateCompanyCode(); val now = System.currentTimeMillis(); val permanent = masterPermanent; val days = trialDays.toIntOrNull() ?: 30; val expiry = if (permanent) 0L else now + days * 24L * 60L * 60L * 1000L; val id = UUID.randomUUID().toString(); val license = generateCommercialLicenseKey()
                        val uid = auth.currentUser?.uid
                        if (uid.isNullOrBlank()) { loading = false; error = "Firebase Auth ainda não está autenticado. Tente novamente após ativar o login anônimo no Firebase."; return@ensureFirebase }
                        val data = mapOf("name" to masterCompanyName.trim(), "email" to masterCompanyEmail.trim(), "authorizedEmail" to masterCompanyEmail.trim(), "companyPhone" to masterCompanyPhone.trim(), "companyCode" to code, "licensePermanent" to permanent, "licenseKey" to license, "licenseStatus" to if (permanent) "PERMANENT" else "TRIAL", "licenseDays" to if (permanent) 0 else days, "licenseStartedAt" to now, "licenseExpiresAt" to expiry, "active" to true, "activated" to false, "createdAt" to now, "createdByMaster" to true, "ownerUid" to uid,
                                "appVersion" to "12.0-test")
                        firestore.collection("companies").document(id).set(data).addOnSuccessListener {
                            masterCompanyCode = code; masterCompanyEmail = masterCompanyEmail.trim(); masterCompanyName = masterCompanyName.trim(); masterCompanyPhone = masterCompanyPhone.trim(); masterCompanyId = id; masterLicensePermanent = permanent; masterLicenseExpiresAt = expiry; masterLicenseStatus = if (permanent) "PERMANENT" else "TRIAL"; masterLicenseDays = if (permanent) 0 else days; masterCompanyActivated = false; masterCompanyActive = true; masterCompanyCreatedAt = now; masterCompanyVersion = "12.0-test"; masterStatus = "Empresa cadastrada com sucesso. Agora os links da empresa foram liberados para envio."; masterRegistration = false; masterPermanent = false; mode = "master"; loading = false
                        }.addOnFailureListener { e -> loading = false; error = "Não foi possível autorizar a empresa: ${e.localizedMessage ?: "erro no Firebase"}" }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "CADASTRANDO..." else "CADASTRAR EMPRESA") }
            } else {
                Text("🏢 CADASTRO DA EMPRESA", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(email, { email = it }, label = { Text("E-mail autorizado") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(companyName, { companyName = it }, label = { Text("Nome da empresa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(responsible, { responsible = it }, label = { Text("Responsável") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(companyPhone, { companyPhone = it }, label = { Text("Telefone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(companyCep, { value -> companyCep = value.filter(Char::isDigit).take(8); if (companyCep.length == 8) { companyCepSearching = true; scope.launch { val r = lookupCep(companyCep); companyCepSearching = false; if (r != null && !r.optBoolean("erro", false)) { companyStreet = r.optString("logradouro"); companyNeighborhood = r.optString("bairro"); companyCity = r.optString("localidade"); companyState = r.optString("uf").uppercase(Locale.ROOT) } else error = "CEP não encontrado." } } }, label = { Text(if (companyCepSearching) "CEP (pesquisando...)" else "CEP") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (companyStreet.isNotBlank()) Text("$companyStreet — $companyNeighborhood — $companyCity/$companyState", color = LightBlue, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(companyNumber, { companyNumber = it }, label = { Text("Número") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Criar senha") }, singleLine = true, visualTransformation = if (showCompanyPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showCompanyPassword = !showCompanyPassword }) { Text(if (showCompanyPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Repetir senha") }, singleLine = true, visualTransformation = if (showCompanyConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showCompanyConfirmPassword = !showCompanyConfirmPassword }) { Text(if (showCompanyConfirmPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(enabled = !loading, onClick = {
                    when {
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> error = "Informe o e-mail autorizado."
                        companyName.trim().length < 2 -> error = "Informe o nome da empresa."
                        companyPhone.isBlank() -> error = "Informe o telefone."
                        companyCep.filter(Char::isDigit).length != 8 -> error = "Informe um CEP válido."
                        companyStreet.isBlank() || companyCity.isBlank() || companyState.length != 2 -> error = "Pesquise um CEP válido antes de continuar."
                        companyNumber.isBlank() -> error = "Informe o número."
                        password.length < 6 -> error = "A senha deve ter pelo menos 6 caracteres."
                        password != confirmPassword -> error = "As senhas não coincidem."
                        else -> ensureFirebase {
                            loading = true
                            val pendingId = prefs.getString("pending_company_id", "").orEmpty()
                            firestore.collection("companies").document(pendingId).get().addOnSuccessListener { doc ->
                                if (!doc.exists() || doc.getString("email")?.equals(email.trim(), true) != true) { loading = false; error = "Autorização da empresa não encontrada."; return@addOnSuccessListener }
                                val permanent = doc.getBoolean("licensePermanent") ?: false; val expiry = doc.getLong("licenseExpiresAt") ?: 0L; val code = doc.getString("companyCode") ?: generateCompanyCode(); val origin = listOf(companyCep, "$companyStreet, $companyNumber", companyNeighborhood, "$companyCity - $companyState").filter { it.isNotBlank() }.joinToString(" — ")
                                val updated = mapOf("name" to companyName.trim(), "responsible" to responsible.trim(), "companyPhone" to companyPhone.trim(), "companyCep" to companyCep.trim(), "companyStreet" to companyStreet.trim(), "companyNumber" to companyNumber.trim(), "companyNeighborhood" to companyNeighborhood.trim(), "companyCity" to companyCity.trim(), "companyState" to companyState.trim().uppercase(Locale.ROOT), "email" to email.trim(), "adminPasswordHash" to hashPassword(password), "originAddress" to origin, "activated" to true, "active" to true, "companyActivatedAt" to System.currentTimeMillis())
                                firestore.collection("companies").document(doc.id).set(updated, SetOptions.merge()).addOnSuccessListener {
                                    saveCompanyLocal(prefs, doc.id, companyName.trim(), code, expiry, permanent, doc.getString("licenseKey") ?: "", companyPhone.trim(), origin)
                                    prefs.edit().putString("company_responsible", responsible.trim()).putString("company_email", email.trim()).putString("admin_password_hash", hashPassword(password)).putString("company_role", "admin").putBoolean("company_authenticated", true).putBoolean("company_just_registered", true).remove("pending_company_id").remove("invite_profile").remove("invite_company_code").apply()
                                    loading = false; mode = "app"; error = ""
                                }.addOnFailureListener { e -> loading = false; error = "Não foi possível salvar a empresa: ${e.localizedMessage ?: "erro no Firebase"}" }
                            }.addOnFailureListener { e -> loading = false; error = e.localizedMessage ?: "Não foi possível validar a autorização." }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "SALVANDO..." else "CADASTRAR EMPRESA") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { masterRegistration = false; mode = "master"; error = "" }) { Text("VOLTAR", color = LightBlue) }
        } else if (mode == "login") {
            Text("🔐 ENTRAR NA EMPRESA", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Em outro celular ou notebook, use o mesmo e-mail e a senha da empresa para recuperar a conta e todos os dados.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(loginEmail, { loginEmail = it }, label = { Text("E-mail da empresa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(loginPassword, { loginPassword = it }, label = { Text("Senha") }, singleLine = true, visualTransformation = if (showCompanyLoginPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showCompanyLoginPassword = !showCompanyLoginPassword }) { Text(if (showCompanyLoginPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(enabled = !loading, onClick = {
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(loginEmail.trim()).matches() || loginPassword.isBlank()) { error = "Informe e-mail e senha."; return@Button }
                ensureFirebase {
                    loading = true
                    firestore.collection("companies").whereEqualTo("email", loginEmail.trim()).limit(20).get().addOnSuccessListener { snap ->
                        val doc = snap.documents.filter { it.getBoolean("activated") == true }.maxByOrNull { it.getLong("createdAt") ?: 0L }
                        if (doc == null || doc.getString("adminPasswordHash") != hashPassword(loginPassword)) { loading = false; error = "E-mail ou senha incorretos." }
                        else { loadCompanyLocally(doc); loading = false; mode = "app"; error = "" }
                    }.addOnFailureListener { e -> loading = false; error = e.localizedMessage ?: "Não foi possível recuperar a conta." }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "ENTRANDO..." else "ENTRAR") }
        } else {
            Text(
                if (inviteProfile == "entregador") "🚚 Abrindo o acesso do Entregador…" else "👤 Abrindo o cadastro do Cliente…",
                color = LightBlue, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = invitedCode, onValueChange = {}, readOnly = true, label = { Text("Empresa do convite") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("O convite está sendo vinculado automaticamente à empresa. Não é necessário tocar em outro botão.", color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
        if (error.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(error, color = Color(0xFFFF7777), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
        Text("Durante os testes, o mesmo e-mail pode ser reutilizado. Em produção, o controle de licença poderá exigir e-mails exclusivos.", color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp)
    }
}

@Composable
private fun AguaGasApp(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val companyId = prefs.getString("company_id", "") ?: ""
    val companyName = prefs.getString("company_name", "Empresa") ?: "Empresa"
    val justRegistered = prefs.getBoolean("company_just_registered", false)
    var page by remember { mutableStateOf(
        if (justRegistered && prefs.getString("company_role", "member").orEmpty() == "admin") {
            Page.ADMIN
        } else {
            when (prefs.getString("company_role", "member").orEmpty()) {
                "delivery" -> Page.DELIVERY_LOGIN
                "customer" -> Page.CUSTOMER
                else -> Page.HOME
            }
        }
    ) }
    LaunchedEffect(Unit) {
        if (justRegistered) prefs.edit().remove("company_just_registered").apply()
    }
    var trackingOrderId by remember { mutableStateOf<Long?>(null) }
    var trackingReturnPage by remember { mutableStateOf(Page.CUSTOMER) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var showDeliveryPassword by remember { mutableStateOf(false) }
    var showDeliveryDraft by remember { mutableStateOf(false) }
    var showDeliveryConfirmDraft by remember { mutableStateOf(false) }
    var selectedDeliveryDriver by remember { mutableStateOf(prefs.getInt("selected_delivery_driver", 1)) }
    var driverSelectionOrderId by remember { mutableStateOf<Long?>(null) }
    var adminAuthorizationInput by remember { mutableStateOf("") }
    var adminAuthorizationDraft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var firstSetup by remember { mutableStateOf(false) }
    var adminBiometricOptIn by remember { mutableStateOf(prefs.getBoolean("admin_biometric_enabled", false)) }
    var deliveryBiometricOptIn by remember { mutableStateOf(prefs.getBoolean("delivery_biometric_enabled", false)) }
    var products by remember { mutableStateOf(loadProducts(prefs)) }
    var newProductName by remember { mutableStateOf("") }
    var newProductPrice by remember { mutableStateOf("") }
    var newProductIsGas by remember { mutableStateOf(false) }
    var productToDelete by remember { mutableStateOf<Product?>(null) }
    var orders by remember { mutableStateOf(loadOrders(prefs).filter { it.companyId.isBlank() || it.companyId == companyId }) }
    var firebaseStatus by remember { mutableStateOf("Conectando ao Firebase…") }
    val selectedOrders = remember { mutableStateListOf<Long>() }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var newOrderDocType by remember { mutableStateOf("CPF") }
    var newOrderDocNumber by remember { mutableStateOf("") }
    var newOrderDocTypeMenuExpanded by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var waterQty by remember { mutableStateOf("0") }
    var gasQty by remember { mutableStateOf("0") }
    var payment by remember { mutableStateOf("Dinheiro") }
    var cashGiven by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var pixKey by remember { mutableStateOf(prefs.getString("pix_key", "") ?: "") }
    var pixKeyDraft by remember { mutableStateOf(pixKey) }
    var pixKeyType by remember { mutableStateOf(prefs.getString("pix_key_type", "CPF") ?: "CPF") }
    var pixKeyTypeDraft by remember { mutableStateOf(pixKeyType) }
    var pixKeyTypeMenuExpanded by remember { mutableStateOf(false) }
    var pixRecipientName by remember { mutableStateOf(prefs.getString("pix_recipient_name", "") ?: "") }
    var pixRecipientNameDraft by remember { mutableStateOf(pixRecipientName) }
    var deliveryPassword by remember { mutableStateOf("") }
    var deliveryConfirmPassword by remember { mutableStateOf("") }
    var deliveryFirstSetup by remember { mutableStateOf(false) }
    var deliveryPasswordDraft by remember { mutableStateOf("") }
    var deliveryPasswordConfirmDraft by remember { mutableStateOf("") }
    var clientHouseNumber by remember { mutableStateOf(prefs.getString("client_house_number", "") ?: "") }
    var cepSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var salesDailyWater by remember { mutableStateOf(0) }
    var salesDailyGas by remember { mutableStateOf(0) }
    var salesMonthlyWater by remember { mutableStateOf(0) }
    var salesMonthlyGas by remember { mutableStateOf(0) }
    var registeredCustomerCount by remember { mutableStateOf(0) }
    var showResetSalesConfirm by remember { mutableStateOf(false) }
    var showShareLinksDialog by remember { mutableStateOf(false) }
    var apkOperationInProgress by remember { mutableStateOf(false) }

    fun importApk(role: String, uri: Uri?) {
        if (uri == null || apkOperationInProgress) return
        apkOperationInProgress = true
        message = "Guardando o APK do $role no aplicativo..."
        coroutineScope.launch {
            try {
                val savedFile = withContext(Dispatchers.IO) {
                    val isClient = role.equals("Cliente", ignoreCase = true)
                    val fileName = if (isClient) "AguaGasExpress-Cliente.apk" else "AguaGasExpress-Entregador.apk"
                    val targetDir = java.io.File(context.filesDir, "downloaded-apks").apply { mkdirs() }
                    val targetFile = java.io.File(targetDir, fileName)
                    val tempFile = java.io.File(targetDir, "$fileName.part")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Não foi possível ler o arquivo escolhido.")
                    if (tempFile.length() < 100_000L) {
                        tempFile.delete()
                        throw IllegalStateException("O arquivo parece incompleto ou não é um APK válido.")
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    targetFile
                }
                message = "APK do $role guardado no aplicativo: " + savedFile.name + ". Agora você pode enviá-lo quando quiser."
            } catch (e: Exception) {
                message = "Não foi possível guardar o APK do $role: " + (e.localizedMessage ?: "verifique se escolheu o arquivo correto")
            } finally {
                apkOperationInProgress = false
            }
        }
    }

    val chooseClientApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> importApk("Cliente", uri) }
    val chooseDelivererApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> importApk("Entregador", uri) }

    fun shareStoredApk(role: String) {
        try {
            val isClient = role.equals("Cliente", ignoreCase = true)
            val fileName = if (isClient) "AguaGasExpress-Cliente.apk" else "AguaGasExpress-Entregador.apk"
            val apkFile = java.io.File(java.io.File(context.filesDir, "downloaded-apks"), fileName)
            if (!apkFile.isFile || apkFile.length() < 100_000L) {
                message = "O APK do $role ainda não foi importado. Primeiro toque em ESCOLHER APK DO $role."
                return
            }
            val apkUri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                apkFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "APK $role", apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Enviar APK do $role"))
            showShareLinksDialog = false
            message = ""
        } catch (e: Exception) {
            message = "Não foi possível compartilhar o APK do $role: " + (e.localizedMessage ?: "tente novamente")
        }
    }
    var clientName by remember { mutableStateOf(prefs.getString("client_name", "") ?: "") }
    var clientPhone by remember { mutableStateOf(prefs.getString("client_phone", "") ?: "") }
    var clientDocType by remember { mutableStateOf(prefs.getString("client_doc_type", "CPF") ?: "CPF") }
    var clientDocNumber by remember { mutableStateOf(prefs.getString("client_doc_number", "") ?: "") }
    var clientDocTypeMenuExpanded by remember { mutableStateOf(false) }
    var clientCep by remember { mutableStateOf(prefs.getString("client_cep", "") ?: "") }
    var clientAddress by remember { mutableStateOf(prefs.getString("client_street_address", prefs.getString("client_address", "")) ?: "") }
    var clientProfileSaved by remember { mutableStateOf(prefs.getBoolean("client_profile_saved", false)) }
    var clientLastOrderId by remember { mutableStateOf<Long?>(null) }
    var orderSubmitting by remember { mutableStateOf(false) }

    val currentOrders by rememberUpdatedState(orders)
    val currentPage by rememberUpdatedState(page)
    SideEffect { MainActivity.isOnDeliveryScreen = page == Page.DELIVERY }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val currentDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val currentMonth = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
            if (prefs.getString("salesDailyDate", "") != currentDay) {
                salesDailyWater = 0
                salesDailyGas = 0
            }
            if (prefs.getString("salesMonthlyKey", "") != currentMonth) {
                salesMonthlyWater = 0
                salesMonthlyGas = 0
            }
        }
    }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val canSeeAllOrders = !prefs.getString("admin_password_hash", null).isNullOrBlank() || page == Page.DELIVERY || (page == Page.TRACKING && trackingReturnPage == Page.DELIVERY)

    DisposableEffect(firestore, canSeeAllOrders) {
        var ordersRegistration: ListenerRegistration? = null
        var configRegistration: ListenerRegistration? = null
        var customerRegistration: ListenerRegistration? = null
        var disposed = false
        val auth = FirebaseAuth.getInstance()
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) prefs.edit().putString("customer_fcm_token", token).apply()
        }

        fun attachListeners(uid: String) {
            if (disposed) return
            if (canSeeAllOrders) registerAdminPushToken(prefs, firestore, companyId)
            val orderQuery = if (canSeeAllOrders) {
                firestore.collection("orders").whereEqualTo("companyId", companyId)
            } else {
                firestore.collection("orders")
                    .whereEqualTo("companyId", companyId)
                    .whereEqualTo("customerUid", uid)
            }
            ordersRegistration?.remove()
            ordersRegistration = orderQuery.addSnapshotListener { snapshot, error ->
                if (disposed) return@addSnapshotListener
                if (error != null) {
                    firebaseStatus = "Firebase: erro ao sincronizar pedidos (${error.localizedMessage ?: "verifique as regras"})"
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val remoteOrders = snapshot.documents.mapNotNull { doc ->
                    doc.data?.let { firestoreDocumentToOrder(doc.id, it) }
                }.filterNot { it.demo }

                // No aparelho do cliente, os avisos usam marcas de tempo do próprio pedido.
                // Isso evita depender apenas da mudança de status e não repete avisos antigos na primeira sincronização.
                if (!canSeeAllOrders) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (disposed) return@post
                        remoteOrders.forEach { remoteOrder ->
                            if (remoteOrder.paymentConfirmedAt > 0L) {
                                val key = "customer_seen_payment_event_${remoteOrder.id}"
                                if (!prefs.getBoolean(key, false)) {
                                    prefs.edit().putBoolean(key, true).apply()
                                    announcePaymentConfirmed(context)
                                    showCustomerVoiceNotification(context, "Pagamento confirmado", "Pagamento confirmado. Seu pedido foi liberado para entrega e a nota já está disponível.")
                                }
                            }
                            if (remoteOrder.approvalSentAt > 0L) {
                                val key = "customer_seen_approval_event_${remoteOrder.id}"
                                if (!prefs.getBoolean(key, false)) {
                                    prefs.edit().putBoolean(key, true).apply()
                                    announceOrderSentToDelivery(context)
                                    showCustomerVoiceNotification(context, "Pedido enviado para entrega", "Seu pedido foi enviado para entrega.")
                                }
                            }
                            if (remoteOrder.deliveryStartedAt > 0L) {
                                val key = "customer_seen_delivery_event_${remoteOrder.id}"
                                if (!prefs.getBoolean(key, false)) {
                                    prefs.edit().putBoolean(key, true).apply()
                                    announceDeliveryStarted(context)
                                    showCustomerVoiceNotification(context, "Entrega iniciada", "O entregador iniciou a entrega. Seu pedido está a caminho.")
                                }
                            }
                            if (remoteOrder.status == "Chegou ao endereço") {
                                val key = "customer_seen_arrival_event_${remoteOrder.id}"
                                if (!prefs.getBoolean(key, false)) {
                                    prefs.edit().putBoolean(key, true).apply()
                                    announceCustomerArrival(context)
                                    showCustomerVoiceNotification(context, "Entregador chegou", "O entregador está no endereço.")
                                }
                            }
                        }
                    }
                }

                // Alerta somente para pedido em dinheiro recém-criado ou quando o cliente
                // informa o Pix no botão verde. Criar pedido Pix, por si só, não toca aviso.
                val alertKeys = remoteOrders.filter { it.status == "Pendente" }.mapNotNull { order ->
                    when {
                        order.payment.equals("PIX", ignoreCase = true) && order.pixReported -> "${order.id}:pix"
                        !order.payment.equals("PIX", ignoreCase = true) -> "${order.id}:cash"
                        else -> null
                    }
                }.toSet()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!disposed && currentPage != Page.DELIVERY &&
                        !prefs.getString("admin_password_hash", null).isNullOrBlank()) {
                        val previousRaw = prefs.getString("admin_seen_order_alert_keys", null)
                        if (previousRaw == null) {
                            // Inicialização silenciosa: não anunciar pedidos antigos ao abrir o app.
                            prefs.edit().putString("admin_seen_order_alert_keys", alertKeys.joinToString(",")).apply()
                        } else {
                            val previousSet = previousRaw.split(",").filter { it.isNotBlank() }.toSet()
                            val newKeys = alertKeys - previousSet
                            if (MainActivity.isAppVisible && newKeys.isNotEmpty()) announceNewOrder(context)
                            prefs.edit().putString("admin_seen_order_alert_keys", alertKeys.joinToString(",")).apply()
                        }
                    }
                }

                // A nuvem é a fonte da verdade. Não restaurar do armazenamento local pedidos
                // removidos após cancelamento ou conclusão.
                val merged = remoteOrders.filter { it.status != "Concluído" && it.status != "Cancelado" }
                    .sortedBy { it.id }
                orders = merged
                saveOrders(prefs, merged)
                // Remover documentos de teste antigos e registros já encerrados, sem tocar em pedidos ativos.
                snapshot.documents.filter { doc ->
                    doc.getBoolean("demo") == true || doc.getString("status") == "Concluído" || doc.getString("status") == "Cancelado"
                }.forEach { doc -> doc.reference.delete() }
                firebaseStatus = "Sincronização ativa · ${remoteOrders.size} pedido(s)"
            }

            if (canSeeAllOrders) {
                firestore.collection("customers").addSnapshotListener { snapshot, error ->
                    if (disposed) return@addSnapshotListener
                    if (error != null) {
                        firebaseStatus = "Clientes: não foi possível atualizar a quantidade (${error.localizedMessage ?: "erro"})"
                        return@addSnapshotListener
                    }
                    registeredCustomerCount = snapshot?.documents?.count { it.getBoolean("active") != false } ?: 0
                }.also { registration ->
                    // A referência fica vinculada ao ciclo de tela abaixo por meio da lista auxiliar.
                    customerRegistration = registration
                }
            }

            configRegistration?.remove()
            configRegistration = companyConfigRef(firestore, companyId)
                .addSnapshotListener { snapshot, error ->
                    if (disposed) return@addSnapshotListener
                    if (error != null) {
                        firebaseStatus = "Firebase conectado parcialmente; confira as regras de configuração."
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val remoteProductsJson = snapshot.getString("productsJson")
                        if (!remoteProductsJson.isNullOrBlank() && remoteProductsJson != "[]") {
                            prefs.edit().putString("products_json", remoteProductsJson).apply()
                            products = loadProducts(prefs)
                        } else if (canSeeAllOrders && products.isNotEmpty()) {
                            // Se a configuração remota antiga estiver sem catálogo, republicar os produtos locais da Administração.
                            companyConfigRef(firestore, companyId)
                                .set(mapOf("productsJson" to productsJson(products)), SetOptions.merge())
                        }
                        snapshot.getString("pixKey")?.let {
                            pixKey = it
                            pixKeyDraft = it
                            prefs.edit().putString("pix_key", it).apply()
                        }
                        snapshot.getString("pixKeyType")?.let {
                            pixKeyType = it
                            pixKeyTypeDraft = it
                            prefs.edit().putString("pix_key_type", it).apply()
                        }
                        snapshot.getString("pixRecipientName")?.let {
                            pixRecipientName = it
                            pixRecipientNameDraft = it
                            prefs.edit().putString("pix_recipient_name", it).apply()
                        }
                        snapshot.getString("companyPhone")?.let { prefs.edit().putString("company_phone", it).apply() }
                        snapshot.getString("companyName")?.let { prefs.edit().putString("company_name", it).apply() }
                        snapshot.getString("companyResponsible")?.let { prefs.edit().putString("company_responsible", it).apply() }
                        snapshot.getString("companyEmail")?.let { prefs.edit().putString("company_email", it).apply() }
                        snapshot.getString("companyCep")?.let { prefs.edit().putString("company_cep", it).apply() }
                        snapshot.getString("companyStreet")?.let { prefs.edit().putString("company_street", it).apply() }
                        snapshot.getString("companyNumber")?.let { prefs.edit().putString("company_number", it).apply() }
                        snapshot.getString("companyNeighborhood")?.let { prefs.edit().putString("company_neighborhood", it).apply() }
                        snapshot.getString("companyCity")?.let { prefs.edit().putString("company_city", it).apply() }
                        snapshot.getString("companyState")?.let { prefs.edit().putString("company_state", it).apply() }
                        val remoteOrigin = listOf(
                            snapshot.getString("companyCep") ?: "",
                            listOf(snapshot.getString("companyStreet") ?: "", snapshot.getString("companyNumber") ?: "").filter { it.isNotBlank() }.joinToString(", "),
                            snapshot.getString("companyNeighborhood") ?: "",
                            listOf(snapshot.getString("companyCity") ?: "", snapshot.getString("companyState") ?: "").filter { it.isNotBlank() }.joinToString(" - ")
                        ).filter { it.isNotBlank() }.joinToString(" — ")
                        if (remoteOrigin.isNotBlank()) prefs.edit().putString("company_origin_address", remoteOrigin).apply()
                        snapshot.getString("deliveryPasswordHash")?.takeIf { it.isNotBlank() }?.let { sharedHash ->
                            val safeHash = if (sharedHash == hashPassword("ENT-583941")) "" else sharedHash
                            prefs.edit().putString("delivery_password_hash", safeHash).apply()
                            if (safeHash != sharedHash && canSeeAllOrders) {
                                companyConfigRef(firestore, companyId).set(mapOf("deliveryPasswordHash" to safeHash), SetOptions.merge())
                            }
                        }
                        val currentDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
                        val currentMonth = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
                        prefs.edit().putString("salesDailyDate", snapshot.getString("salesDailyDate") ?: "")
                            .putString("salesMonthlyKey", snapshot.getString("salesMonthlyKey") ?: "").apply()
                        salesDailyWater = if (snapshot.getString("salesDailyDate") == currentDay) (snapshot.getLong("salesDailyWater") ?: 0L).toInt() else 0
                        salesDailyGas = if (snapshot.getString("salesDailyDate") == currentDay) (snapshot.getLong("salesDailyGas") ?: 0L).toInt() else 0
                        salesMonthlyWater = if (snapshot.getString("salesMonthlyKey") == currentMonth) (snapshot.getLong("salesMonthlyWater") ?: 0L).toInt() else 0
                        salesMonthlyGas = if (snapshot.getString("salesMonthlyKey") == currentMonth) (snapshot.getLong("salesMonthlyGas") ?: 0L).toInt() else 0
                    } else if (canSeeAllOrders) {
                        val initialConfig = mapOf(
                            "productsJson" to productsJson(products),
                            "pixKey" to pixKey,
                            "pixKeyType" to pixKeyType,
                            "pixRecipientName" to pixRecipientName,
                            "deliveryPasswordHash" to (prefs.getString("delivery_password_hash", "") ?: ""),
                            "adminFcmToken" to (prefs.getString("admin_fcm_token", "") ?: ""),
                            "companyPhone" to (prefs.getString("company_phone", "") ?: ""),
                            "companyOriginAddress" to (prefs.getString("company_origin_address", "") ?: ""),
                            "companyName" to (prefs.getString("company_name", "") ?: ""),
                            "companyResponsible" to (prefs.getString("company_responsible", "") ?: ""),
                            "companyEmail" to (prefs.getString("company_email", "") ?: ""),
                            "companyCep" to (prefs.getString("company_cep", "") ?: ""),
                            "companyStreet" to (prefs.getString("company_street", "") ?: ""),
                            "companyNumber" to (prefs.getString("company_number", "") ?: ""),
                            "companyNeighborhood" to (prefs.getString("company_neighborhood", "") ?: ""),
                            "companyCity" to (prefs.getString("company_city", "") ?: ""),
                            "companyState" to (prefs.getString("company_state", "") ?: "")
                        )
                        companyConfigRef(firestore, companyId).set(initialConfig, SetOptions.merge())
                    }
                }
        }

        if (auth.currentUser != null) {
            attachListeners(auth.currentUser!!.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    result.user?.uid?.let { attachListeners(it) }
                }
                .addOnFailureListener { e ->
                    if (!disposed) firebaseStatus = "Firebase: não conectou (${e.localizedMessage ?: "verifique a internet"})"
                }
        }
        onDispose {
            disposed = true
            ordersRegistration?.remove()
            configRegistration?.remove()
            customerRegistration?.remove()
        }
    }

    fun persistOrders(updated: List<Order>) {
        // Salva localmente de imediato e garante autenticação anônima antes de enviar à nuvem.
        val submittedFromCustomer = page == Page.CUSTOMER
        orders = updated
        saveOrders(prefs, updated)

        fun uploadForUid(uid: String) {
            val prepared = updated.map { order ->
                if (!order.demo && order.companyId.isBlank()) order.copy(
                    customerUid = if (order.customerUid.isBlank() && submittedFromCustomer) uid else order.customerUid,
                    companyId = companyId
                ) else if (!order.demo && order.customerUid.isBlank() && submittedFromCustomer) order.copy(customerUid = uid) else order
            }
            orders = prepared
            saveOrders(prefs, prepared)
            val toUpload = prepared.filter { order -> !order.demo && (canSeeAllOrders || order.customerUid == uid) }
            if (toUpload.isEmpty()) return
            toUpload.forEach { order ->
                firestore.collection("orders").document(order.id.toString()).set(orderToFirestoreMap(order))
                    .addOnSuccessListener { firebaseStatus = "Pedido sincronizado com o Firebase." }
                    .addOnFailureListener { e -> firebaseStatus = "Falha ao enviar pedido: ${e.localizedMessage ?: "verifique as regras do Firestore"}" }
            }
        }

        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid
        if (!currentUid.isNullOrBlank()) {
            uploadForUid(currentUid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (!uid.isNullOrBlank()) uploadForUid(uid)
                    else firebaseStatus = "Firebase: autenticação anônima não retornou usuário."
                }
                .addOnFailureListener { e -> firebaseStatus = "Pedido salvo neste aparelho, mas não sincronizado: ${e.localizedMessage ?: "falha de autenticação"}" }
        }
    }
    fun publishSharedConfig() {
        val payload = mapOf(
            "productsJson" to productsJson(products),
            "pixKey" to pixKey,
            "pixKeyType" to pixKeyType,
            "pixRecipientName" to pixRecipientName,
            "companyPhone" to (prefs.getString("company_phone", "") ?: ""),
            "companyOriginAddress" to (prefs.getString("company_origin_address", "") ?: ""),
            "companyName" to (prefs.getString("company_name", "") ?: ""),
            "companyResponsible" to (prefs.getString("company_responsible", "") ?: ""),
            "companyEmail" to (prefs.getString("company_email", "") ?: ""),
            "companyCep" to (prefs.getString("company_cep", "") ?: ""),
            "companyStreet" to (prefs.getString("company_street", "") ?: ""),
            "companyNumber" to (prefs.getString("company_number", "") ?: ""),
            "companyNeighborhood" to (prefs.getString("company_neighborhood", "") ?: ""),
            "companyCity" to (prefs.getString("company_city", "") ?: ""),
            "companyState" to (prefs.getString("company_state", "") ?: "")
        )
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            companyConfigRef(firestore, companyId).set(payload, SetOptions.merge())
                .addOnSuccessListener { firebaseStatus = "Configuração compartilhada atualizada." }
                .addOnFailureListener { e -> firebaseStatus = "Não foi possível sincronizar a configuração: ${e.localizedMessage ?: "erro"}" }
        } else {
            auth.signInAnonymously().addOnSuccessListener {
                companyConfigRef(firestore, companyId).set(payload, SetOptions.merge())
                    .addOnFailureListener { e -> firebaseStatus = "Não foi possível sincronizar a configuração: ${e.localizedMessage ?: "erro"}" }
            }.addOnFailureListener { e -> firebaseStatus = "Firebase: não conectou (${e.localizedMessage ?: "erro"})" }
        }
    }
    fun updateOrder(updated: Order) {
        persistOrders(orders.map { if (it.id == updated.id) updated else it })
    }
    fun removeOrder(order: Order, countSales: Boolean, onDone: (() -> Unit)? = null) {
        val orderRef = firestore.collection("orders").document(order.id.toString())
        orderRef.delete().addOnSuccessListener {
            firestore.collection("orders").document(order.id.toString()).collection("messages").get()
                .addOnSuccessListener { snap ->
                    val batch = firestore.batch()
                    snap.documents.forEach { batch.delete(it.reference) }
                    batch.commit()
                }
            val remaining = orders.filterNot { it.id == order.id }
            orders = remaining
            saveOrders(prefs, remaining)
            message = if (countSales) "Entrega concluída. Totais atualizados." else "Pedido cancelado e removido."
            onDone?.invoke()
        }.addOnFailureListener { e -> message = "Não foi possível remover o pedido da nuvem: ${e.localizedMessage ?: "verifique a conexão e as regras do Firebase"}" }
    }
    fun completeOrderAndCount(order: Order, onDone: () -> Unit) {
        val orderRef = firestore.collection("orders").document(order.id.toString())
        val configRef = companyConfigRef(firestore, companyId)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
        firestore.runTransaction { transaction ->
            val remoteOrder = transaction.get(orderRef)
            val config = transaction.get(configRef)
            if (!remoteOrder.exists()) return@runTransaction false
            val oldDay = config.getString("salesDailyDate").orEmpty()
            val oldMonth = config.getString("salesMonthlyKey").orEmpty()
            val dayWater = if (oldDay == today) (config.getLong("salesDailyWater") ?: 0L).toInt() else 0
            val dayGas = if (oldDay == today) (config.getLong("salesDailyGas") ?: 0L).toInt() else 0
            val monthWater = if (oldMonth == month) (config.getLong("salesMonthlyWater") ?: 0L).toInt() else 0
            val monthGas = if (oldMonth == month) (config.getLong("salesMonthlyGas") ?: 0L).toInt() else 0
            transaction.set(configRef, mapOf(
                "salesDailyDate" to today,
                "salesDailyWater" to dayWater + order.waterQty,
                "salesDailyGas" to dayGas + order.gasQty,
                "salesMonthlyKey" to month,
                "salesMonthlyWater" to monthWater + order.waterQty,
                "salesMonthlyGas" to monthGas + order.gasQty
            ), SetOptions.merge())
            transaction.delete(orderRef)
            true
        }.addOnSuccessListener { counted ->
            if (counted) {
                val savedDay = prefs.getString("salesDailyDate", "").orEmpty()
                val savedMonth = prefs.getString("salesMonthlyKey", "").orEmpty()
                salesDailyWater = if (savedDay == today) salesDailyWater + order.waterQty else order.waterQty
                salesDailyGas = if (savedDay == today) salesDailyGas + order.gasQty else order.gasQty
                salesMonthlyWater = if (savedMonth == month) salesMonthlyWater + order.waterQty else order.waterQty
                salesMonthlyGas = if (savedMonth == month) salesMonthlyGas + order.gasQty else order.gasQty
                prefs.edit().putString("salesDailyDate", today).putString("salesMonthlyKey", month).apply()
                firestore.collection("orders").document(order.id.toString()).collection("messages").get().addOnSuccessListener { snap ->
                    val batch = firestore.batch(); snap.documents.forEach { batch.delete(it.reference) }; batch.commit()
                }
                val remaining = orders.filterNot { it.id == order.id }
                orders = remaining; saveOrders(prefs, remaining)
                onDone()
            } else {
                message = "Este pedido não está mais na nuvem. Atualize a lista antes de concluir."
            }
        }.addOnFailureListener { e -> message = "Não foi possível registrar a venda: ${e.localizedMessage ?: "verifique a conexão e as regras do Firebase"}" }
    }
    fun nextOrderNumber(): Int = (orders.filterNot { it.demo }.maxOfOrNull { it.orderNumber } ?: 0) + 1
    fun parseQty(value: String): Int = value.toIntOrNull()?.coerceIn(0, 999) ?: 0
    fun openMap(order: Order) {
        // Abre uma rota de carro até o endereço usando a localização atual como origem.
        val routeUrl = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(order.address)}&travelmode=driving"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(routeUrl))
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            message = "Não foi possível abrir o Google Maps. Confira se há navegador ou Maps instalado."
        }
    }

    if (productToDelete != null) {
        val deleting = productToDelete!!
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = { Text("Apagar produto?") },
            text = { Text("${deleting.name} será removido do catálogo para novos pedidos. Pedidos ativos não serão alterados.") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = products.filterNot { it.name == deleting.name }
                    products = updated
                    saveProducts(prefs, updated)
                    publishSharedConfig()
                    productToDelete = null
                    message = "Produto ${deleting.name} removido e enviado para sincronização."
                }) { Text("APAGAR", color = Color(0xFFB71C1C)) }
            },
            dismissButton = { TextButton(onClick = { productToDelete = null }) { Text("CANCELAR") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF10264A), Navy, Color(0xFF071020))))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (page) {
            Page.HOME -> {
                Spacer(Modifier.height(25.dp))
                Text("💧 🔥", fontSize = 48.sp)
                Text("Comércio Express", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                Text("EXPRESS", color = Orange, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Spacer(Modifier.height(32.dp))
                val fixedRole = prefs.getString("company_role", "member").orEmpty()
                if (fixedRole != "customer" && fixedRole != "delivery") {
                    Text("Escolha como deseja entrar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                }
                if (fixedRole == "customer" || fixedRole == "member" || fixedRole == "admin") {
                    ModeCard("CLIENTE", "Fazer pedido de água e gás", "🛍️", Green) {
                        message = ""; error = ""
                        if (clientProfileSaved) page = Page.CUSTOMER else page = Page.CUSTOMER
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (fixedRole == "admin") {
                    ModeCard("ADMINISTRAÇÃO", "Pedidos, pagamentos e produtos", "⚙️", Orange) {
                        firstSetup = prefs.getString("admin_password_hash", null).isNullOrBlank()
                        password = ""; confirmPassword = ""; adminAuthorizationInput = ""; error = ""; page = Page.LOGIN
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (fixedRole == "delivery" || fixedRole == "member" || fixedRole == "admin") {
                    ModeCard("ENTREGADOR", "Consultar e atualizar entregas", "🚚", Blue) {
                        deliveryFirstSetup = false
                        deliveryPassword = ""; deliveryConfirmPassword = ""; error = ""; page = Page.DELIVERY_LOGIN
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Pediu, chegou!", color = LightBlue, fontWeight = FontWeight.Bold)
            }

            Page.LOGIN -> {
                Header("Acesso administrativo", "🔐")
                Text(if (firstSetup) "Informe o código de autorização e crie a senha inicial (mínimo 6 caracteres)." else "Digite sua senha.",
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                if (firstSetup) {
                    OutlinedTextField(
                        value = adminAuthorizationInput,
                        onValueChange = { adminAuthorizationInput = it.filter(Char::isDigit).take(6); error = "" },
                        label = { Text("Código de autorização (6 números)", color = Color.White) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = "" },
                    label = { Text("Senha administrativa", color = Color.White) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (firstSetup) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassword, onValueChange = { confirmPassword = it; error = "" },
                        label = { Text("Confirmar senha", color = Color.White) }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                        visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showConfirmPassword = !showConfirmPassword }) { Text(if (showConfirmPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = adminBiometricOptIn, onCheckedChange = { adminBiometricOptIn = it })
                    Text("Ativar acesso por digital neste aparelho", color = Color.White, fontSize = 13.sp)
                }
                ErrorText(error)
                Spacer(Modifier.height(16.dp))
                MainButton(if (firstSetup) "CRIAR SENHA" else "ENTRAR") {
                    if (firstSetup) {
                        val requiredCode = prefs.getString("admin_authorization_code", DEFAULT_ADMIN_AUTH_CODE) ?: DEFAULT_ADMIN_AUTH_CODE
                        when {
                            adminAuthorizationInput != requiredCode -> error = "Código de autorização incorreto."
                            password.length < 6 -> error = "Use pelo menos 6 caracteres."
                            password != confirmPassword -> error = "As senhas não coincidem."
                            else -> {
                                prefs.edit().putString("admin_password_hash", hashPassword(password))
                                    .putBoolean("admin_biometric_enabled", adminBiometricOptIn).apply()
                                password = ""; confirmPassword = ""; adminAuthorizationInput = ""; error = ""; page = Page.ADMIN
                            }
                        }
                    } else {
                        val saved = prefs.getString("admin_password_hash", null)
                        if (saved != null && saved == hashPassword(password)) {
                            prefs.edit().putBoolean("admin_biometric_enabled", adminBiometricOptIn).apply()
                            password = ""; error = ""; page = Page.ADMIN
                        } else error = "Senha incorreta."
                    }
                }
                if (!firstSetup && prefs.getBoolean("admin_biometric_enabled", false)) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            authenticateBiometric(context, "Acesso administrativo",
                                onSuccess = { error = ""; page = Page.ADMIN },
                                onError = { error = it })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ENTRAR COM DIGITAL", color = Color.White) }
                }
                if (!firstSetup) TextButton(onClick = { page = Page.RECOVERY }) {
                    Text("Esqueci minha senha", color = LightBlue)
                }
                BackButton { page = Page.HOME }
            }

            Page.DELIVERY_LOGIN -> {
                Header("Acesso do entregador", "🚚")
                Text("Selecione o entregador deste aparelho e digite a senha.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("ENTREGADOR", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..6).forEach { driver ->
                        Button(onClick = { selectedDeliveryDriver = driver }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (selectedDeliveryDriver == driver) Green else Color(0xFF315A85)), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("$driver", fontWeight = FontWeight.ExtraBold) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deliveryPassword,
                    onValueChange = { deliveryPassword = it; error = "" },
                    label = { Text("Senha do entregador", color = Color.White) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryPassword = !showDeliveryPassword }) { Text(if (showDeliveryPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = deliveryBiometricOptIn, onCheckedChange = { deliveryBiometricOptIn = it })
                    Text("Ativar acesso por digital neste aparelho", color = Color.White, fontSize = 13.sp)
                }
                ErrorText(error)
                Spacer(Modifier.height(12.dp))
                MainButton("ENTRAR") {
                    val savedDeliveryHash = prefs.getString("delivery_password_hash", "").orEmpty()
                    if (savedDeliveryHash.isBlank()) {
                        error = "A senha dos entregadores ainda não foi cadastrada pelo proprietário. Peça para ele abrir o painel administrativo e cadastrar a senha."
                    } else if (savedDeliveryHash == hashPassword(deliveryPassword) || savedDeliveryHash == hashPassword(deliveryPassword.replace(" ", ""))) {
                        prefs.edit().putBoolean("delivery_biometric_enabled", deliveryBiometricOptIn).putInt("selected_delivery_driver", selectedDeliveryDriver).apply()
                        deliveryPassword = ""; error = ""; selectedOrders.clear(); page = Page.DELIVERY
                    } else error = "Senha incorreta. Peça a senha ao proprietário."
                }
                if (!deliveryFirstSetup && prefs.getBoolean("delivery_biometric_enabled", false)) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            authenticateBiometric(context, "Acesso do entregador",
                                onSuccess = { error = ""; selectedDeliveryDriver = prefs.getInt("selected_delivery_driver", 1); selectedOrders.clear(); page = Page.DELIVERY },
                                onError = { error = it })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ENTRAR COM DIGITAL", color = Color.White) }
                }
                BackButton { error = ""; page = Page.HOME }
            }

            Page.CUSTOMER -> {
                Header("Faça seu pedido", "🛍️")
                if (!clientProfileSaved) {
                    Text("Primeiro, preencha seus dados para entrega.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    InputField("Nome completo", clientName) { clientName = it }
                    InputField("Telefone", clientPhone) { clientPhone = it }
                    Text("Documento para nota (opcional)", color = LightBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { clientDocType = "CPF"; clientDocTypeMenuExpanded = false }, colors = ButtonDefaults.buttonColors(containerColor = if (clientDocType == "CPF") Blue else Color(0xFF28415F)), modifier = Modifier.weight(1f)) { Text("CPF") }
                        Button(onClick = { clientDocType = "CNPJ"; clientDocTypeMenuExpanded = false }, colors = ButtonDefaults.buttonColors(containerColor = if (clientDocType == "CNPJ") Blue else Color(0xFF28415F)), modifier = Modifier.weight(1f)) { Text("CNPJ") }
                    }
                    InputField("Número do CPF/CNPJ (opcional)", clientDocNumber) { clientDocNumber = normalizeDocument(it) }
                    Text("Se não quiser informar, deixe em branco.", color = Color(0xFFB8C8D8), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                    InputField("CEP", clientCep) { clientCep = it.filter { ch -> ch.isDigit() }.take(8) }
                    LaunchedEffect(clientCep) {
                        if (clientCep.length == 8) {
                            cepSearching = true
                            message = "Consultando CEP…"
                            val result = lookupCep(clientCep)
                            cepSearching = false
                            if (result == null || result.optBoolean("erro", false)) {
                                message = "Não foi possível localizar o CEP. Você pode preencher o endereço manualmente."
                            } else {
                                val parts = listOf(result.optString("logradouro"), result.optString("bairro"), listOf(result.optString("localidade"), result.optString("uf")).filter { it.isNotBlank() }.joinToString(" - "))
                                    .filter { it.isNotBlank() && it != "null" }
                                clientAddress = parts.joinToString(", ")
                                message = if (clientAddress.isBlank()) "CEP localizado. Preencha o endereço manualmente." else "Endereço localizado. Informe o número da casa abaixo."
                            }
                        }
                    }
                    if (cepSearching) Text("Pesquisando CEP…", color = LightBlue, fontSize = 12.sp)
                    if (clientAddress.isNotBlank()) {
                        Text("Endereço encontrado pelo CEP", color = LightBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(clientAddress, color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    }
                    InputField("Número da casa", clientHouseNumber) { clientHouseNumber = it }
                    ErrorText(message)
                    Spacer(Modifier.height(10.dp))
                    MainButton("SALVAR CADASTRO E CONTINUAR") {
                        when {
                            clientName.isBlank() -> message = "Informe seu nome."
                            clientPhone.isBlank() -> message = "Informe seu telefone."
                            !isValidOptionalDocument(clientDocType, clientDocNumber) -> message = "Informe um ${clientDocType} válido ou deixe o campo em branco."
                            clientAddress.isBlank() -> message = "Informe um CEP válido para localizar o endereço."
                            clientHouseNumber.isBlank() -> message = "Informe o número da casa."
                            else -> {
                                clientProfileSaved = true
                                val savedAddress = listOf(clientAddress.trim(), clientHouseNumber.trim()).filter { it.isNotBlank() }.joinToString(", ")
                                prefs.edit().putString("client_name", clientName.trim())
                                    .putString("client_phone", clientPhone.trim())
                                    .putString("client_doc_type", clientDocType)
                                    .putString("client_doc_number", normalizeDocument(clientDocNumber))
                                    .putString("client_cep", clientCep.trim())
                                    .putString("client_street_address", clientAddress.trim())
                                    .putString("client_house_number", clientHouseNumber.trim())
                                    .putString("client_address", savedAddress)
                                    .putBoolean("client_profile_saved", true).apply()

                                fun saveCustomerRegistration(uid: String) {
                                    firestore.collection("customers").document(customerDocumentId(clientPhone)).set(mapOf(
                                        "uid" to uid,
                                        "name" to clientName.trim(),
                                        "phone" to clientPhone.trim(),
                                        "documentType" to clientDocType,
                                        "documentNumber" to normalizeDocument(clientDocNumber),
                                        "cep" to clientCep.trim(),
                                        "address" to savedAddress,
                                        "active" to true,
                                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                    ), SetOptions.merge())
                                }
                                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                                    saveCustomerRegistration(uid)
                                } ?: FirebaseAuth.getInstance().signInAnonymously().addOnSuccessListener { result ->
                                    result.user?.uid?.let { saveCustomerRegistration(it) }
                                }
                                message = ""
                            }
                        }
                    }
                } else {
                    Text("Olá, $clientName!", color = LightBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Telefone: $clientPhone", color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 14.sp)
                    Text("Endereço: ${listOf(clientAddress, clientHouseNumber).filter { it.isNotBlank() }.joinToString(", ")}", color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 14.sp)
                    TextButton(onClick = {
                        clientProfileSaved = false
                        message = ""
                    }) { Text("Alterar endereço / dados", color = LightBlue) }
                    Spacer(Modifier.height(2.dp))
                    var clientQuantities by remember { mutableStateOf(products.associate { it.name to 0 }) }
                    LaunchedEffect(products.map { it.name to it.isGas }) {
                        clientQuantities = products.associate { it.name to (clientQuantities[it.name] ?: 0) }
                    }
                    var pixCopied by remember { mutableStateOf(false) }
                    val lastClientOrder = orders.firstOrNull { it.id == clientLastOrderId && it.status !in listOf("Concluído", "Cancelado") } ?: orders
                        .filter { !it.demo && it.phone.filter(Char::isDigit) == clientPhone.filter(Char::isDigit) && it.status in listOf("Pendente", "Autorizado", "Em entrega", "Chegou ao endereço") }
                        .maxByOrNull { it.id }
                    LaunchedEffect(lastClientOrder?.id, lastClientOrder?.status) {
                        val arrivalOrder = lastClientOrder
                        if (arrivalOrder != null && arrivalOrder.status == "Chegou ao endereço" &&
                            prefs.getLong("arrival_announced_order_id", -1L) != arrivalOrder.id) {
                            prefs.edit().putLong("arrival_announced_order_id", arrivalOrder.id).apply()
                            announceCustomerArrival(context)
                        }
                    }
                    if (lastClientOrder != null) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("ACOMPANHAMENTO DO PEDIDO #${lastClientOrder.orderNumber.toString().padStart(2, '0')}", color = Ink, fontWeight = FontWeight.ExtraBold)
                                Text("Status atual: ${when (lastClientOrder.status) { "Pendente" -> "Aguardando confirmação"; "Autorizado" -> "Pedido confirmado"; "Em entrega" -> "Saiu para entrega"; "Chegou ao endereço" -> "Pedido chegou ao endereço"; else -> lastClientOrder.status }}", color = Blue, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("ETAPAS DO PEDIDO", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                fun statusTime(value: Long): String = if (value > 0L) java.text.SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(java.util.Date(value)) else "aguardando"
                                val confirmed = lastClientOrder.approvalSentAt > 0L || lastClientOrder.status in listOf("Autorizado", "Em entrega", "Chegou ao endereço")
                                val departed = lastClientOrder.deliveryStartedAt > 0L || lastClientOrder.status in listOf("Em entrega", "Chegou ao endereço")
                                val arrived = lastClientOrder.arrivalAt > 0L || lastClientOrder.status == "Chegou ao endereço"
                                listOf(
                                    Triple(confirmed, "1. PEDIDO CONFIRMADO", statusTime(lastClientOrder.approvalSentAt)),
                                    Triple(departed, "2. SAIU PARA ENTREGA", statusTime(lastClientOrder.deliveryStartedAt)),
                                    Triple(arrived, "3. PEDIDO NO ENDEREÇO", statusTime(lastClientOrder.arrivalAt))
                                ).forEach { (done, label, time) ->
                                    Text(if (done) "✓ $label — $time" else "○ $label — aguardando", color = if (done) Green else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                if (lastClientOrder.paid && lastClientOrder.receiptGeneratedAt > 0L) {
                                    Spacer(Modifier.height(8.dp))
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)), shape = RoundedCornerShape(12.dp)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("🧾 NOTA DO PEDIDO DISPONÍVEL", color = Green, fontWeight = FontWeight.ExtraBold)
                                            Text("Pagamento confirmado. O comprovante foi gerado automaticamente.", color = Ink, fontSize = 13.sp)
                                            Spacer(Modifier.height(7.dp))
                                            Text("Itens: ${lastClientOrder.items}", color = Ink, fontSize = 13.sp)
                                            Text("Total: ${money(lastClientOrder.total)}", color = Ink, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(7.dp))
                                            Button(onClick = { shareOrderReceipt(context, lastClientOrder, prefs.getString("company_name", "Comércio Express") ?: "Comércio Express") }, modifier = Modifier.fillMaxWidth()) {
                                                Text("📄 GERAR PDF E COMPARTILHAR")
                                            }
                                        }
                                    }
                                }
                                if (lastClientOrder.status == "Chegou ao endereço") {
                                    Spacer(Modifier.height(10.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9F8E4)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "📦 PEDIDO CHEGOU!",
                                                color = Color(0xFF126B36),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "O entregador está no endereço do cliente.",
                                                color = Color(0xFF126B36),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                if (lastClientOrder.status == "Em entrega") {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { trackingOrderId = lastClientOrder.id; trackingReturnPage = Page.CUSTOMER; page = Page.TRACKING }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                                        Text("📍 ACOMPANHAR ENTREGA")
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("ESCOLHA SEUS PRODUTOS", color = LightBlue, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    if (products.isEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(12.dp)) {
                            Text("Os produtos ainda estão sincronizando. Volte a esta tela em alguns instantes ou peça à administração para conferir o catálogo.", modifier = Modifier.padding(14.dp), color = Color.White, textAlign = TextAlign.Center)
                        }
                    }
                    products.forEach { product ->
                        val quantity = clientQuantities[product.name] ?: 0
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10284A)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (product.isGas) Text("🔥", fontSize = 24.sp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                    Text(product.name, color = Color.White, fontWeight = FontWeight.Bold,
                                        fontSize = if (product.name == COMBO_WATER_NAME) 12.sp else 14.sp, maxLines = 2)
                                    Text(money(product.price), color = LightBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = { if (quantity > 0) clientQuantities = clientQuantities + (product.name to quantity - 1) },
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) { Text("−", fontSize = 18.sp) }
                                Text("$quantity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = { if (quantity < 99) clientQuantities = clientQuantities + (product.name to quantity + 1) },
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) { Text("+", fontSize = 18.sp) }
                            }
                        }
                    }
                    val clientWaterQty = products.filterNot { it.isGas }.sumOf { clientQuantities[it.name] ?: 0 }
                    val clientGasQty = products.filter { it.isGas }.sumOf { clientQuantities[it.name] ?: 0 }
                    val clientTotal = products.sumOf { product -> (clientQuantities[product.name] ?: 0) * product.price }
                    Text("TOTAL: ${money(clientTotal)}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (payment == "PIX" && pixCopied && lastClientOrder != null && lastClientOrder.payment == "PIX" && lastClientOrder.status == "Pendente" && !lastClientOrder.pixReported) {
                        Button(
                            onClick = {
                                updateOrder(lastClientOrder.copy(pixReported = true))
                                message = "Você informou que fez o Pix. A administração ainda precisa conferir o recebimento."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("JÁ FIZ O PAGAMENTO PIX", fontWeight = FontWeight.Bold) }
                    } else if (lastClientOrder != null && lastClientOrder.payment == "PIX" && lastClientOrder.pixReported && lastClientOrder.status == "Pendente") {
                        Text("Pix informado — aguardando conferência da administração", color = Color(0xFF8BE9A5), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = payment == "Dinheiro", onClick = { payment = "Dinheiro"; message = "" })
                        Text("Dinheiro", color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        RadioButton(selected = payment == "PIX", onClick = { payment = "PIX"; message = "" })
                        Text("Pix", color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        RadioButton(selected = payment == "Fiado", onClick = { payment = "Fiado"; message = "No momento não estamos com essa opção. Aguarde mais alguns instantes." })
                        Text("Fiado", color = Color.White)
                    }
                    if (payment == "Dinheiro") {
                        InputField("Quanto você vai entregar? (ex.: 50)", cashGiven, true) { cashGiven = it; message = "" }
                        val cashValue = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                        if (cashGiven.isNotBlank() && cashValue >= clientTotal) {
                            Text("Troco: ${money(cashValue - clientTotal)}", color = Color(0xFF8BE9A5), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        } else if (cashGiven.isNotBlank()) {
                            Text("Faltam: ${money(clientTotal - cashValue)}", color = Color(0xFFFFD0A8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Informe o valor que vai entregar para calcular o troco.", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (payment == "Fiado") {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Text(
                                "No momento não estamos com essa opção. Aguarde mais alguns instantes.",
                                modifier = Modifier.padding(14.dp), color = Ink, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (payment == "PIX") {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("CONFIRME O DESTINATÁRIO DO PIX", color = Ink, fontWeight = FontWeight.Bold)
                                Text(if (pixRecipientName.isBlank()) "Nome do recebedor não cadastrado" else "Pix para $pixRecipientName", color = Ink, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("CHAVE PIX $pixKeyType", color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                Text(
                                    if (pixKey.isBlank()) "A chave Pix ainda não foi cadastrada pela administração." else pixKey,
                                    modifier = Modifier.padding(start = 10.dp, top = 5.dp, bottom = 8.dp),
                                    color = Ink,
                                    fontSize = 17.sp
                                )
                                if (pixKey.isNotBlank()) {
                                    MainButton("COPIAR CHAVE PIX") {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chave Pix", pixKey))
                                        pixCopied = true
                                        message = "Chave Pix copiada."
                                    }
                                }
                            }
                        }
                    }
                    ErrorText(message)
                    Spacer(Modifier.height(10.dp))
                    MainButton(if (orderSubmitting) "ENVIANDO PEDIDO…" else "FAZER PEDIDO") {
                        if (orderSubmitting) return@MainButton
                        when {
                            clientWaterQty + clientGasQty == 0 -> message = "Escolha ao menos um produto."
                            products.any { (clientQuantities[it.name] ?: 0) > 0 && it.price <= 0.0 } -> message = "Os preços ainda não foram configurados pela administração."
                            payment == "PIX" && pixKey.isBlank() -> message = "A administração precisa cadastrar a chave Pix."
                            payment == "Fiado" -> message = "No momento não estamos com essa opção. Aguarde mais alguns instantes."
                            payment == "Dinheiro" && (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) < clientTotal -> message = "Informe um valor igual ou maior que o total para calcular o troco."
                            else -> {
                                val items = products.mapNotNull { product ->
                                    val quantity = clientQuantities[product.name] ?: 0
                                    if (quantity > 0) "$quantity ${product.name}" else null
                                }.joinToString(", ")
                                val order = Order(
                                    id = System.currentTimeMillis(),
                                    customer = clientName.trim(),
                                    phone = clientPhone.trim(),
                                    customerDocType = if (clientDocNumber.isBlank()) "" else clientDocType,
                                    customerDocNumber = normalizeDocument(clientDocNumber),
                                    address = listOf(clientCep.trim(), listOf(clientAddress.trim(), clientHouseNumber.trim()).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" — "),
                                    items = items,
                                    total = clientTotal,
                                    payment = payment,
                                    cashGiven = if (payment == "Dinheiro") (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) else 0.0,
                                    status = "Pendente",
                                    paid = false,
                                    waterQty = clientWaterQty,
                                    gasQty = clientGasQty,
                                    demo = false,
                                    orderNumber = nextOrderNumber(),
                                    customerUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
                                    customerFcmToken = prefs.getString("customer_fcm_token", "").orEmpty(),
                                    companyId = companyId,
                                    originAddress = prefs.getString("company_origin_address", "") ?: ""
                                )
                                if (orderSubmitting) return@MainButton
                                orderSubmitting = true
                                val auth = FirebaseAuth.getInstance()
                                fun uploadOrderForCustomer(uid: String) {
                                    val orderForCloud = order.copy(customerUid = uid)
                                    firestore.collection("orders").document(orderForCloud.id.toString())
                                        .set(orderToFirestoreMap(orderForCloud))
                                        .addOnSuccessListener {
                                            // Atualiza o token do cliente depois de gravar o pedido, para que o aviso de chegada
                                            // também funcione se o token ainda estivesse sendo gerado quando o cliente tocou em pedir.
                                            FirebaseMessaging.getInstance().token.addOnSuccessListener { freshToken ->
                                                if (freshToken.isNotBlank()) {
                                                    prefs.edit().putString("customer_fcm_token", freshToken).apply()
                                                    firestore.collection("orders").document(orderForCloud.id.toString()).update("customerFcmToken", freshToken)
                                                }
                                            }
                                            val updatedOrders = (orders.filterNot { it.id == orderForCloud.id } + orderForCloud).sortedBy { it.id }
                                            orders = updatedOrders
                                            saveOrders(prefs, updatedOrders)
                                            clientLastOrderId = orderForCloud.id
                                            clientQuantities = products.associate { it.name to 0 }
                                            cashGiven = ""
                                            orderSubmitting = false
                                            message = if (orderForCloud.payment.equals("PIX", ignoreCase = true)) {
                                                "Pedido #${orderForCloud.orderNumber.toString().padStart(2, '0')} enviado para a administração. O Pix ainda está pendente."
                                            } else {
                                                "Pedido #${orderForCloud.orderNumber.toString().padStart(2, '0')} enviado para a administração."
                                            }
                                        }
                                        .addOnFailureListener { e ->
                                            orderSubmitting = false
                                            message = "Não foi possível enviar o pedido. Verifique a conexão e as regras do Firebase. ${e.localizedMessage ?: "Tente novamente."}"
                                        }
                                }
                                if (auth.currentUser != null) {
                                    uploadOrderForCustomer(auth.currentUser!!.uid)
                                } else {
                                    auth.signInAnonymously()
                                        .addOnSuccessListener { result ->
                                            val uid = result.user?.uid
                                            if (uid.isNullOrBlank()) {
                                                orderSubmitting = false
                                                message = "Não foi possível autenticar este aparelho no Firebase."
                                            } else uploadOrderForCustomer(uid)
                                        }
                                        .addOnFailureListener { e ->
                                            orderSubmitting = false
                                            message = "Não foi possível conectar ao Firebase. ${e.localizedMessage ?: "Confira a internet e tente novamente."}"
                                        }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                MainButton("VOLTAR AO INÍCIO") { message = ""; page = Page.HOME }
            }

            Page.ADMIN -> {
                Header("Administração", "⚙️")
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🏢 $companyName", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text("Código da empresa: ${prefs.getString("company_code", "—") ?: "—"}", color = LightBlue, fontWeight = FontWeight.Bold)
                        Text("🔒 Licença de uso da empresa — não vender, ceder ou revender o aplicativo.", color = Color(0xFFFFD0A8), fontSize = 11.sp, textAlign = TextAlign.Center)
                        Text("Licença: ${trialExpiryLabel(prefs)}", color = if (localTrialActive(prefs)) Green else Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                        Text("Chave da licença: ${prefs.getString("license_key", "—") ?: "—"}", color = LightBlue, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("VENDAS DE HOJE", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                        Text("Água: $salesDailyWater  •  Gás: $salesDailyGas", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("ACUMULADO DO MÊS", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                        Text("Água: $salesMonthlyWater  •  Gás: $salesMonthlyGas", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Text("CLIENTES INSCRITOS", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                        Text("$registeredCustomerCount cliente(s)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showShareLinksDialog = true }) {
                        Text("📦 Enviar APKs", color = LightBlue)
                    }
                    TextButton(onClick = { showResetSalesConfirm = true }) { Text("Zerar testes", color = Orange) }
                }

                if (showShareLinksDialog) {
                    AlertDialog(
                        onDismissRequest = { showShareLinksDialog = false },
                        title = { Text("📦 Preparar e enviar APKs") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "1. Baixe ou gere os APKs no Android. 2. Escolha cada arquivo nesta tela para guardá-lo dentro do aplicativo. 3. Envie cada APK quando precisar.",
                                    color = Color.DarkGray
                                )
                                Text(
                                    "Os arquivos importados ficam guardados nos dados do aplicativo e não precisam ser escolhidos novamente a cada envio. Se limpar os dados ou desinstalar o app, será necessário importá-los de novo.",
                                    color = Color.DarkGray,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        confirmButton = {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(
                                    onClick = { chooseClientApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                                    enabled = !apkOperationInProgress
                                ) { Text("📥 ESCOLHER APK DO CLIENTE") }
                                TextButton(
                                    onClick = { chooseDelivererApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                                    enabled = !apkOperationInProgress
                                ) { Text("📥 ESCOLHER APK DO ENTREGADOR") }
                                Divider()
                                TextButton(
                                    onClick = { shareStoredApk("Cliente") },
                                    enabled = !apkOperationInProgress
                                ) { Text("👤 ENVIAR APK DO CLIENTE") }
                                TextButton(
                                    onClick = { shareStoredApk("Entregador") },
                                    enabled = !apkOperationInProgress
                                ) { Text("🚚 ENVIAR APK DO ENTREGADOR") }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showShareLinksDialog = false }) { Text("FECHAR") }
                        }
                    )
                }
                if (showResetSalesConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetSalesConfirm = false },
                        title = { Text("Zerar contadores de vendas?") },
                        text = { Text("Isso vai zerar as vendas de hoje e o acumulado do mês para começar a contar as vendas reais. Os pedidos não serão apagados.") },
                        confirmButton = {
                            TextButton(onClick = {
                                val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
                                val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
                                companyConfigRef(firestore, companyId).set(mapOf(
                                    "salesDailyDate" to today,
                                    "salesDailyWater" to 0,
                                    "salesDailyGas" to 0,
                                    "salesMonthlyKey" to month,
                                    "salesMonthlyWater" to 0,
                                    "salesMonthlyGas" to 0
                                ), SetOptions.merge()).addOnSuccessListener {
                                    salesDailyWater = 0; salesDailyGas = 0
                                    salesMonthlyWater = 0; salesMonthlyGas = 0
                                    prefs.edit().putString("salesDailyDate", today).putString("salesMonthlyKey", month).apply()
                                    message = "Contadores de teste zerados. A partir de agora, as entregas concluídas serão contadas."
                                }.addOnFailureListener { e ->
                                    message = "Não foi possível zerar os contadores: ${e.localizedMessage ?: "confira a internet e as regras do Firebase"}"
                                }
                                showResetSalesConfirm = false
                            }) { Text("ZERAR CONTADORES") }
                        },
                        dismissButton = { TextButton(onClick = { showResetSalesConfirm = false }) { Text("Cancelar") } }
                    )
                }
                Spacer(Modifier.height(8.dp))
                MenuButton("💧  Produtos e preços") { page = Page.PRODUCTS }
                MenuButton("🔑  Configurar chave Pix") {
                    pixKeyDraft = pixKey
                    pixKeyTypeDraft = pixKeyType
                    pixRecipientNameDraft = pixRecipientName
                    message = ""
                    page = Page.PIX_SETTINGS
                }
                MenuButton("🛡️  Código de autorização") {
                    adminAuthorizationDraft = prefs.getString("admin_authorization_code", DEFAULT_ADMIN_AUTH_CODE) ?: DEFAULT_ADMIN_AUTH_CODE
                    message = ""
                    page = Page.ADMIN_CODE_SETTINGS
                }
                MenuButton("🔐  Cadastrar senha dos entregadores") {
                    deliveryPasswordDraft = ""
                    deliveryPasswordConfirmDraft = ""
                    message = ""
                    page = Page.DELIVERY_PASSWORD_SETTINGS
                }
                MenuButton("📥  Pedidos recebidos (${orders.count { it.status == "Pendente" }})") { page = Page.ORDERS }
                MenuButton("🚚  Entregas (${orders.count { it.status == "Autorizado" || it.status == "Em entrega" || it.status == "Chegou ao endereço" }})") { page = Page.DELIVERY }
                Spacer(Modifier.height(10.dp))
                MainButton("SAIR DA ADMINISTRAÇÃO") { page = Page.HOME }
                Spacer(Modifier.height(12.dp))

            }

            Page.PRODUCTS -> {
                Header("Produtos e preços", "💧")
                Text("Cadastre, altere ou retire produtos. O catálogo será compartilhado com os clientes pelo Firebase.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("ACRESCENTAR PRODUTO", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        OutlinedTextField(
                            value = newProductName,
                            onValueChange = { newProductName = it; message = "" },
                            label = { Text("Nome do produto / marca", color = Color.White) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newProductPrice,
                            onValueChange = { newProductPrice = it },
                            label = { Text("Preço em reais", color = Color.White) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !newProductIsGas, onClick = { newProductIsGas = false })
                            Text("Água / marca", color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            RadioButton(selected = newProductIsGas, onClick = { newProductIsGas = true })
                            Text("Gás", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val name = newProductName.trim()
                                val price = newProductPrice.replace(",", ".").toDoubleOrNull()
                                when {
                                    name.isBlank() -> message = "Digite o nome do produto."
                                    price == null || price <= 0.0 -> message = "Digite um preço maior que zero."
                                    products.any { it.name.equals(name, ignoreCase = true) } -> message = "Já existe um produto com esse nome."
                                    else -> {
                                        val updated = products + Product(name, price, newProductIsGas)
                                        products = updated
                                        saveProducts(prefs, updated)
                                        publishSharedConfig()
                                        newProductName = ""
                                        newProductPrice = ""
                                        message = "Produto adicionado. A lista do Cliente será atualizada quando o Firebase sincronizar."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("ACRESCENTAR PRODUTO") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("PRODUTOS CADASTRADOS (${products.size})", color = Color.White, fontWeight = FontWeight.ExtraBold)
                if (products.isEmpty()) {
                    Text("Nenhum produto cadastrado. Use o formulário acima para acrescentar um.", color = Color(0xFFFFD0A8), textAlign = TextAlign.Center)
                }
                products.forEachIndexed { index, product ->
                    var priceText by remember(product.name, product.price) {
                        mutableStateOf(if (product.price == 0.0) "" else product.price.toString().replace(".", ","))
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (product.isGas) "🔥 ${product.name}" else product.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = priceText, onValueChange = { priceText = it },
                                label = { Text("Preço em reais") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                                    cursorColor = Blue, focusedLabelColor = Blue, unfocusedLabelColor = Ink,
                                    focusedBorderColor = Blue, unfocusedBorderColor = Ink
                                ), modifier = Modifier.fillMaxWidth())
                            Text("Preço atual: ${money(product.price)}", color = Ink)
                            Button(onClick = {
                                val parsed = priceText.replace(",", ".").toDoubleOrNull()
                                if (parsed == null || parsed <= 0.0) message = "Digite um preço maior que zero."
                                else {
                                    val updated = products.toMutableList().also { it[index] = product.copy(price = parsed) }
                                    products = updated
                                    saveProducts(prefs, updated)
                                    publishSharedConfig()
                                    message = "Preço de ${product.name} salvo e enviado para sincronização."
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("SALVAR PREÇO") }
                            OutlinedButton(
                                onClick = { productToDelete = product },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C))
                            ) { Text("APAGAR PRODUTO") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                ErrorText(message)
                Text(firebaseStatus, color = if (firebaseStatus.startsWith("Conectado") || firebaseStatus.startsWith("Configuração compartilhada")) Color(0xFF86EFAC) else Color(0xFFFFD0A8), fontSize = 11.sp, textAlign = TextAlign.Center)
                MainButton("VOLTAR") { message = ""; page = Page.ADMIN }
            }

            Page.ADMIN_CODE_SETTINGS -> {
                Header("Código de autorização", "🛡️")
                Text(
                    "Defina o código que será exigido antes do cadastro inicial de uma senha administrativa NESTE aparelho. Guarde-o com cuidado.",
                    color = Color.White, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = adminAuthorizationDraft,
                    onValueChange = { adminAuthorizationDraft = it.filter(Char::isDigit).take(6); message = "" },
                    label = { Text("Novo código de autorização (6 números)", color = Color.White) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR CÓDIGO") {
                    if (!adminAuthorizationDraft.matches(Regex("\\d{6}"))) {
                        message = "O código deve ter exatamente 6 números."
                    } else {
                        prefs.edit().putString("admin_authorization_code", adminAuthorizationDraft.trim()).apply()
                        message = "Código salvo neste aparelho."
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Atenção: este código é local neste aparelho. O código inicial desta versão é 160829; alterações feitas aqui não são enviadas para outros celulares.",
                    color = Color(0xFFFFD0A8), fontSize = 12.sp, textAlign = TextAlign.Center
                )
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.DELIVERY_PASSWORD_SETTINGS -> {
                Header("Senha do entregador", "🔐")
                Text("O proprietário cadastra ou altera aqui a senha que será usada pelos entregadores. A mesma senha é sincronizada com os aparelhos autorizados da empresa.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = deliveryPasswordDraft,
                    onValueChange = { deliveryPasswordDraft = it; message = "" },
                    label = { Text("Cadastrar nova senha dos entregadores", color = Color.White) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryDraft) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryDraft = !showDeliveryDraft }) { Text(if (showDeliveryDraft) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deliveryPasswordConfirmDraft,
                    onValueChange = { deliveryPasswordConfirmDraft = it; message = "" },
                    label = { Text("Confirmar nova senha", color = Color.White) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryConfirmDraft) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryConfirmDraft = !showDeliveryConfirmDraft }) { Text(if (showDeliveryConfirmDraft) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR SENHA DO ENTREGADOR") {
                    when {
                        deliveryPasswordDraft.length < 6 -> message = "Use pelo menos 6 caracteres."
                        deliveryPasswordDraft != deliveryPasswordConfirmDraft -> message = "As senhas não coincidem."
                        else -> {
                            val newDeliveryHash = hashPassword(deliveryPasswordDraft)
                            prefs.edit().putString("delivery_password_hash", newDeliveryHash).apply()
                            companyConfigRef(firestore, companyId).set(mapOf("deliveryPasswordHash" to newDeliveryHash), SetOptions.merge())
                                .addOnSuccessListener { message = "Senha do entregador atualizada e sincronizada." }
                                .addOnFailureListener { e -> message = "Senha salva neste aparelho, mas não sincronizou: ${e.localizedMessage ?: "erro do Firebase"}" }
                            deliveryPasswordDraft = ""
                            deliveryPasswordConfirmDraft = ""
                        }
                    }
                }
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.PIX_SETTINGS -> {
                Header("Configurar Pix", "🔑")
                Text(
                    "Cadastre o nome de quem vai receber e a chave Pix. O cliente verá o nome para conferir antes de pagar. Estes dados ficam salvos neste aparelho.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pixRecipientNameDraft,
                    onValueChange = { pixRecipientNameDraft = it; message = "" },
                    label = { Text("Nome completo de quem recebe o Pix", color = Color.White) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("Tipo da chave Pix", color = Color.White, fontWeight = FontWeight.Bold)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { pixKeyTypeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Text("CHAVE PIX $pixKeyTypeDraft  ▾", fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = pixKeyTypeMenuExpanded,
                        onDismissRequest = { pixKeyTypeMenuExpanded = false }
                    ) {
                        listOf("CPF", "CNPJ", "Celular", "E-mail", "Aleatória").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    pixKeyTypeDraft = type
                                    pixKeyTypeMenuExpanded = false
                                    message = ""
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pixKeyDraft,
                    onValueChange = { pixKeyDraft = it; message = "" },
                    label = { Text("Digite o número ou valor da chave Pix", color = Color.White) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2
                )
                Spacer(Modifier.height(12.dp))
                if (pixKey.isNotBlank() || pixRecipientName.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Dados do Pix salvos", color = Green, fontWeight = FontWeight.Bold)
                            Text("Recebedor: ${if (pixRecipientName.isBlank()) "não informado" else pixRecipientName}", color = Ink)
                            Text("Chave Pix $pixKeyType: $pixKey", color = Ink)
                        }
                    }
                } else {
                    Text("Nenhuma chave Pix cadastrada ainda.", color = Color(0xFFFFD0A8))
                }
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR DADOS DO PIX") {
                    if (pixRecipientNameDraft.isBlank()) {
                        message = "Digite o nome de quem vai receber o Pix."
                    } else if (pixKeyDraft.isBlank()) {
                        message = "Digite a chave Pix antes de salvar."
                    } else {
                        pixKey = pixKeyDraft.trim()
                        pixKeyType = pixKeyTypeDraft
                        pixRecipientName = pixRecipientNameDraft.trim()
                        prefs.edit().putString("pix_key", pixKey)
                            .putString("pix_key_type", pixKeyType)
                            .putString("pix_recipient_name", pixRecipientName).apply()
                        publishSharedConfig()
                        message = "Nome do recebedor e chave Pix salvos e enviados para sincronização."
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A chave Pix e os preços serão compartilhados com o aplicativo do cliente quando o Firebase estiver conectado.",
                    color = Color(0xFFFFD0A8), fontSize = 12.sp, textAlign = TextAlign.Center
                )
                Text(firebaseStatus, color = if (firebaseStatus.startsWith("Conectado") || firebaseStatus.startsWith("Configuração compartilhada")) Color(0xFF86EFAC) else Color(0xFFFFD0A8), fontSize = 11.sp, textAlign = TextAlign.Center)
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.NEW_ORDER -> {
                Header("Registrar pedido", "🧾")
                Text("Preencha os dados do cliente e as quantidades.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                InputField("Nome do cliente", customer) { customer = it }
                InputField("Telefone", phone) { phone = it }
                Text("CPF/CNPJ do cliente (opcional)", color = LightBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { newOrderDocType = "CPF"; newOrderDocTypeMenuExpanded = false }, colors = ButtonDefaults.buttonColors(containerColor = if (newOrderDocType == "CPF") Blue else Color(0xFF28415F)), modifier = Modifier.weight(1f)) { Text("CPF") }
                    Button(onClick = { newOrderDocType = "CNPJ"; newOrderDocTypeMenuExpanded = false }, colors = ButtonDefaults.buttonColors(containerColor = if (newOrderDocType == "CNPJ") Blue else Color(0xFF28415F)), modifier = Modifier.weight(1f)) { Text("CNPJ") }
                }
                InputField("Número do CPF/CNPJ (opcional)", newOrderDocNumber) { newOrderDocNumber = normalizeDocument(it) }
                InputField("Endereço completo", address) { address = it }
                Text("Água — ${money(products.firstOrNull { !it.isGas }?.price ?: 0.0)}", color = Color.White, fontWeight = FontWeight.Bold)
                InputField("Quantidade de água", waterQty, true) { waterQty = it }
                Text("Gás — ${money(products.firstOrNull { it.isGas }?.price ?: 0.0)}", color = Color.White, fontWeight = FontWeight.Bold)
                InputField("Quantidade de gás", gasQty, true) { gasQty = it }
                val w = parseQty(waterQty); val g = parseQty(gasQty)
                val waterPrice = products.firstOrNull { !it.isGas }?.price ?: 0.0
                val gasPrice = products.firstOrNull { it.isGas }?.price ?: 0.0
                val total = w * waterPrice + g * gasPrice
                Spacer(Modifier.height(8.dp))
                Text("TOTAL: ${money(total)}", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(12.dp))
                Text("Forma de pagamento", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = payment == "Dinheiro", onClick = { payment = "Dinheiro" })
                    Text("Dinheiro", color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    RadioButton(selected = payment == "PIX", onClick = { payment = "PIX" })
                    Text("PIX", color = Color.White)
                }
                if (payment == "Dinheiro") {
                    InputField("Valor entregue pelo cliente (ex.: 50)", cashGiven, true) { cashGiven = it }
                    val cash = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                    Text("Troco: ${money((cash - total).coerceAtLeast(0.0))}", color = LightBlue, fontWeight = FontWeight.Bold)
                }
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR PEDIDO") {
                    when {
                        customer.isBlank() -> message = "Informe o nome do cliente."
                        phone.isBlank() -> message = "Informe o telefone."
                        !isValidOptionalDocument(newOrderDocType, newOrderDocNumber) -> message = "Informe um ${newOrderDocType} válido ou deixe o campo em branco."
                        address.isBlank() -> message = "Informe o endereço."
                        w + g == 0 -> message = "Informe uma quantidade maior que zero."
                        total <= 0 -> message = "Cadastre os preços antes de registrar o pedido."
                        payment == "Dinheiro" && (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) < total ->
                            message = "O valor recebido não cobre o total."
                        else -> {
                            val items = buildList {
                                if (w > 0) add("$w Água")
                                if (g > 0) add("$g Gás")
                            }.joinToString(", ")
                            val cash = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                            val order = Order(System.currentTimeMillis(), customer.trim(), phone.trim(), if (newOrderDocNumber.isBlank()) "" else newOrderDocType, normalizeDocument(newOrderDocNumber), address.trim(),
                                items, total, payment, if (payment == "Dinheiro") cash else 0.0, "Pendente", false,
                                waterQty = w, gasQty = g, orderNumber = nextOrderNumber(), companyId = companyId, originAddress = prefs.getString("company_origin_address", "") ?: "")
                            persistOrders((orders + order).sortedBy { it.id })
                            message = ""; page = Page.ORDERS
                        }
                    }
                }
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.TRACKING -> {
                val trackedOrder = orders.firstOrNull { it.id == trackingOrderId }
                Header("Acompanhar entrega", "📍")
                if (trackedOrder == null) {
                    Text("Não foi possível localizar este pedido. Volte e atualize a tela.", color = Color.White, textAlign = TextAlign.Center)
                } else {
                    Text("Pedido #${trackedOrder.orderNumber.toString().padStart(2, '0')} • ${trackedOrder.customer}", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(if (trackedOrder.trackingLat != null && trackedOrder.trackingLng != null) "Localização atualizada pelo entregador" else "Aguardando o entregador iniciar o compartilhamento da localização…", color = LightBlue, textAlign = TextAlign.Center)
                    TrackingMapView(trackedOrder)
                    Text("O mapa atualiza quando o aparelho do entregador envia uma nova posição. O GPS e a conexão com a internet precisam estar ativos.", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                BackButton { page = trackingReturnPage }
            }
            Page.ORDERS, Page.DELIVERY -> {
                val deliveryMode = page == Page.DELIVERY
                val visibleOrders = if (deliveryMode) {
                    orders.filter { !it.demo && it.companyId == companyId && it.assignedDriver == selectedDeliveryDriver && it.status in listOf("Autorizado", "Em entrega", "Chegou ao endereço") }
                } else orders.filter { !it.demo && it.status == "Pendente" }
                Header(if (deliveryMode) "Entregas" else "Pedidos recebidos", if (deliveryMode) "🚚" else "📥")
                if (deliveryMode && (message.startsWith("Entrega concluída") || message.startsWith("Chegada registrada"))) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9F8E4)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF126B36),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (deliveryMode) {
                    val onRouteCount = orders.count { it.status == "Em entrega" }
                    val arrivedCount = orders.count { it.status == "Chegou ao endereço" }
                    val waitingCount = orders.count { it.status == "Autorizado" }
                    Text("A caminho: $onRouteCount  •  Chegou: $arrivedCount  •  Aguardando saída: $waitingCount", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (deliveryMode) {
                    Text("Marque os pedidos autorizados e envie todos de uma vez para a rota.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    val readyCount = visibleOrders.count { it.status == "Autorizado" }
                    if (readyCount > 0) MainButton("ENVIAR SELECIONADOS (${selectedOrders.size}) PARA ENTREGA") {
                        val ids = selectedOrders.toSet()
                        if (ids.isNotEmpty()) {
                            persistOrders(orders.map {
                                if (it.id in ids && it.status == "Autorizado") it.copy(status = "Em entrega") else it
                            })
                            selectedOrders.clear()
                        }
                    }
                } else {
                    Text("Confira o pagamento primeiro. Depois autorize o pedido para a tela de entregas.", color = Color.White, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                if (visibleOrders.isEmpty()) {
                    Text(if (deliveryMode) "Nenhuma entrega aguardando ou em andamento." else "Nenhum pedido pendente.", color = Color.White, textAlign = TextAlign.Center)
                } else {
                    visibleOrders.sortedBy { if (deliveryMode) it.id else -it.id }.forEach { order ->
                        val cardColor = when {
                            !deliveryMode && order.status == "Cancelado" -> Color(0xFFFFE0E0)
                            deliveryMode && order.status == "Chegou ao endereço" -> Color(0xFFD9F8E4)
                            deliveryMode && order.status == "Em entrega" -> Color(0xFFFFE0E0)
                            deliveryMode && order.status == "Autorizado" -> Color(0xFFFFF0D5)
                            else -> Color.White
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor),
                            shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                if (deliveryMode && order.status == "Autorizado") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = order.id in selectedOrders, onCheckedChange = { checked ->
                                            if (checked) { if (order.id !in selectedOrders) selectedOrders.add(order.id) }
                                            else selectedOrders.remove(order.id)
                                        })
                                        Text("Selecionar para esta rota", color = Ink, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "PEDIDO #${order.orderNumber.toString().padStart(2, '0')}",
                                        color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
                                    )
                                    if (!deliveryMode && order.status == "Pendente") {
                                        Button(
                                            onClick = { removeOrder(order, false) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.heightIn(min = 34.dp)
                                        ) { Text("CANCELAR PEDIDO", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                                Text(order.customer, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                if (order.assignedDriver in 1..6) Text("🚚 Entregador ${order.assignedDriver}", color = Blue, fontWeight = FontWeight.ExtraBold)
                                Text("Telefone: ${order.phone}", color = Ink)
                                Text("Endereço: ${order.address}", color = Ink)
                                val orderItemLabels = order.items.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                if (orderItemLabels.isNotEmpty()) {
                                    Text("ITENS DO PEDIDO", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                                    orderItemLabels.chunked(2).forEach { itemRow ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            itemRow.forEach { itemLabel ->
                                                val isGas = itemLabel.contains("gás", ignoreCase = true) || itemLabel.contains("gas", ignoreCase = true)
                                                val tileColor = if (isGas) Color(0xFFDBEAFE) else if (itemRow.indexOf(itemLabel) % 2 == 0) Color(0xFFDCFCE7) else Color(0xFFDBEAFE)
                                                val tileText = if (isGas || tileColor == Color(0xFFDBEAFE)) Color(0xFF1D4ED8) else Color(0xFF166534)
                                                Surface(Modifier.weight(1f), color = tileColor, shape = RoundedCornerShape(8.dp)) {
                                                    Text(itemLabel, Modifier.padding(horizontal = 8.dp, vertical = 7.dp), color = tileText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                            if (itemRow.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                Text("Total: ${money(order.total)}", color = Ink, fontWeight = FontWeight.Bold)
                                Text("Pagamento: ${order.payment}", color = Ink)
                                if (order.payment == "PIX") {
                                    Text(
                                        "Pix para: ${if (pixRecipientName.isBlank()) "Nome não cadastrado" else pixRecipientName} • Chave: ${if (pixKey.isBlank()) "Nenhuma chave cadastrada" else pixKey}",
                                        color = Ink
                                    )
                                }
                                if (order.payment == "Dinheiro") {
                                    Text("Valor informado: ${money(order.cashGiven)}", color = Ink)
                                    Text("Troco previsto: ${money((order.cashGiven - order.total).coerceAtLeast(0.0))}", color = Ink)
                                }
                                Text(
                                    when {
                                        order.paid -> "Pagamento confirmado pela administração"
                                        order.pixReported && order.payment == "PIX" -> "Cliente informou que pagou Pix — conferir no banco"
                                        else -> "Pagamento aguardando conferência"
                                    },
                                    color = if (order.paid) Green else Orange, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (deliveryMode && order.status == "Em entrega") "🔴 A CAMINHO — AINDA NÃO ENTREGUE"
                                    else if (deliveryMode && order.status == "Chegou ao endereço") "🟢 CHEGOU AO ENDEREÇO — AVISO REGISTRADO"
                                    else if (deliveryMode && order.status == "Autorizado") "🟠 AGUARDANDO SAÍDA"
                                    else "Status: ${order.status}",
                                    color = when {
                                        deliveryMode && order.status == "Chegou ao endereço" -> Green
                                        deliveryMode && order.status == "Em entrega" -> Color(0xFFB00020)
                                        deliveryMode && order.status == "Autorizado" -> Color(0xFF9A5700)
                                        order.status == "Concluído" -> Green
                                        order.status == "Cancelado" -> Color(0xFFB71C1C)
                                        else -> Blue
                                    },
                                    fontWeight = FontWeight.ExtraBold
                                )
                                if (order.status in listOf("Pendente", "Autorizado", "Em entrega", "Chegou ao endereço")) {
                                }
                                Spacer(Modifier.height(8.dp))
                                if (order.status == "Cancelado") {
                                    Text("PEDIDO CANCELADO — não será enviado para entregas nem para a rota.", color = Color(0xFFB71C1C), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                } else if (!deliveryMode) {
                                    if (!order.paid) {
                                        Button(onClick = { updateOrder(order.copy(paid = true, receiptGeneratedAt = if (order.receiptGeneratedAt == 0L) System.currentTimeMillis() else order.receiptGeneratedAt, paymentConfirmedAt = if (order.paymentConfirmedAt == 0L) System.currentTimeMillis() else order.paymentConfirmedAt)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                                            Text(if (order.payment == "PIX") "CONFIRMAR PIX RECEBIDO" else "CONFIRMAR DINHEIRO RECEBIDO")
                                        }
                                    } else {
                                        Button(onClick = { driverSelectionOrderId = order.id },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                                            Text("AUTORIZAR E ESCOLHER ENTREGADOR")
                                        }
                                    }
                                } else {
                                    if (order.status == "Autorizado" || order.status == "Em entrega") {
                                        Button(onClick = {
                                            val activity = context as? Activity
                                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                            if (!fine && !coarse && activity is MainActivity) {
                                                activity.requestLocationPermission(order.id.toString(), order.address, order.originAddress.ifBlank { prefs.getString("company_origin_address", "") ?: "" })
                                            } else {
                                                if (order.status == "Autorizado") updateOrder(order.copy(status = "Em entrega", deliveryStartedAt = if (order.deliveryStartedAt == 0L) System.currentTimeMillis() else order.deliveryStartedAt, trackingActive = true, trackingPath = emptyList()))
                                                startDeliveryLocationService(context, order.id.toString())
                                                openMap(order)
                                            }
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                                            Text(if (order.status == "Autorizado") "🚚 INICIAR ENTREGA E COMPARTILHAR LOCALIZAÇÃO" else "📍 INICIAR/RETOMAR RASTREAMENTO E ABRIR MAPS")
                                        }
                                    }
                                    if (order.status == "Em entrega") {
                                        Button(onClick = { trackingOrderId = order.id; trackingReturnPage = Page.DELIVERY; page = Page.TRACKING }, modifier = Modifier.fillMaxWidth()) {
                                            Text("📍 ACOMPANHAR ENTREGA NESTE APLICATIVO")
                                        }
                                    }
                                    if (order.status == "Em entrega") {
                                        Button(
                                            onClick = {
                                                updateOrder(order.copy(status = "Chegou ao endereço", arrivalAt = if (order.arrivalAt == 0L) System.currentTimeMillis() else order.arrivalAt))
                                                message = "Chegada registrada neste aparelho. O aviso no celular do cliente depende da conexão online entre os aparelhos."
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Orange)
                                        ) {
                                            Text("🔔 AVISAR CLIENTE QUE CHEGUEI")
                                        }
                                    }
                                    if (order.status == "Autorizado" || order.status == "Em entrega" || order.status == "Chegou ao endereço") {
                                        Button(
                                            onClick = {
                                                val nextOrder = orders.filter { it.status == "Em entrega" && it.id != order.id }.minByOrNull { it.id }
                                                firestore.collection("orders").document(order.id.toString()).set(mapOf("trackingActive" to false), SetOptions.merge())
                                                completeOrderAndCount(order.copy(trackingActive = false)) {
                                                    selectedOrders.remove(order.id)
                                                    if (nextOrder != null) {
                                                        message = "Entrega concluída! Totais atualizados. Abrindo a próxima rota: ${nextOrder.customer}."
                                                        startDeliveryLocationService(context, nextOrder.id.toString())
                                                        openMap(nextOrder)
                                                    } else {
                                                        message = "Entrega concluída! Totais atualizados; pedido removido."
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                                        ) {
                                            Text("FINALIZAR ENTREGA")
                                        }
                                    }
                                    if (order.status == "Autorizado" || order.status == "Em entrega" || order.status == "Chegou ao endereço") {
                                        Spacer(Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                removeOrder(order, false)
                                                selectedOrders.remove(order.id)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                                        ) {
                                            Text("CANCELAR ENTREGA")
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (driverSelectionOrderId != null) {
                    val target = orders.firstOrNull { it.id == driverSelectionOrderId }
                    AlertDialog(
                        onDismissRequest = { driverSelectionOrderId = null },
                        title = { Text("Escolher entregador") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Pedido #${target?.orderNumber?.toString()?.padStart(2, '0') ?: ""}")
                                Text("Selecione quem fará esta entrega:")
                                (1..6).forEach { driver ->
                                    Button(onClick = {
                                        if (target != null) {
                                            updateOrder(target.copy(status = "Autorizado", assignedDriver = driver, approvalSentAt = if (target.approvalSentAt == 0L) System.currentTimeMillis() else target.approvalSentAt))
                                            message = "Pedido aprovado e enviado para o Entregador $driver. O cliente será avisado automaticamente."
                                        }
                                        driverSelectionOrderId = null
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (target?.assignedDriver == driver) Green else Blue)) {
                                        Text("🚚 ENTREGADOR $driver")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { driverSelectionOrderId = null }) { Text("CANCELAR") }
                        }
                    )
                }
                MainButton("VOLTAR") {
                    selectedOrders.clear()
                    message = ""
                    page = if (deliveryMode) Page.HOME else Page.ADMIN
                }
            }

            Page.RECOVERY -> {
                Header("Recuperar senha", "📱")
                Text("A recuperação por telefone e SMS ainda não está configurada. Esta tela não redefine a senha.",
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                MainButton("VOLTAR") { page = Page.LOGIN }
            }
        }
    }
}

@Composable
private fun Header(title: String, emoji: String) {
    Text(emoji, fontSize = 40.sp)
    Spacer(Modifier.height(8.dp))
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ModeCard(title: String, subtitle: String, emoji: String, accent: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 32.sp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(subtitle, color = Color(0xFF5E6E82), fontSize = 12.sp)
            }
            Text("›", color = accent, fontSize = 32.sp)
        }
    }
}

@Composable
private fun MainButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(20.dp), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InputField(label: String, value: String, numeric: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
            cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}

@Composable
private fun ErrorText(value: String) {
    if (value.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(value, color = Color(0xFFFFB4A8), textAlign = TextAlign.Center)
    }
}

@Composable
private fun TrackingMapView(order: Order) {
    val context = LocalContext.current
    var destination by remember(order.address) { mutableStateOf<Pair<Double, Double>?>(null) }

    // Converte o endereço do pedido em coordenadas uma vez para colocar o destino
    // fixo no mapa. O trajeto/posição do entregador continua vindo do Firestore.
    LaunchedEffect(order.address) {
        destination = withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent() || order.address.isBlank()) return@withContext null
                @Suppress("DEPRECATION")
                val results = Geocoder(context, Locale("pt", "BR"))
                    .getFromLocationName(order.address, 1)
                val point = results?.firstOrNull() ?: return@withContext null
                point.latitude to point.longitude
            } catch (_: Exception) {
                null
            }
        }
    }

    val pathJson = remember(order.trackingPath) {
        JSONArray().apply {
            order.trackingPath.forEach { point ->
                put(JSONObject().put("lat", point["lat"] ?: 0.0).put("lng", point["lng"] ?: 0.0))
            }
        }.toString()
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().height(420.dp),
        factory = { contextView ->
            WebView(contextView).apply {
                setBackgroundColor(android.graphics.Color.rgb(232, 238, 245))
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        (view.tag as? String)?.let { view.evaluateJavascript(it, null) }
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                loadDataWithBaseURL("https://www.openstreetmap.org/", """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css" onerror="this.onerror=null;this.href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'"/>
                    <script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js" onerror="this.onerror=null;this.src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'"></script>
                    <style>
                      html,body{height:100%;width:100%;margin:0;padding:0;background:#e8eef5}
                      #map{height:420px;width:100%;background:#e8eef5}
                      .fallback{position:absolute;z-index:1000;top:45%;left:5%;right:5%;padding:12px;border-radius:10px;background:#fff;color:#18253a;text-align:center;font:16px sans-serif}
                    </style>
                    </head><body><div id="map"></div><div id="fallback" class="fallback">Carregando mapa… verifique se há internet ativa.</div><script>
                    var map=null, marker=null, destinationMarker=null, line=null, hasFix=false, destination=null;
                    function initMap(){
                      if(!window.L){document.getElementById('fallback').innerText='Não foi possível carregar o mapa. Confira a conexão com a internet e tente novamente.';return false;}
                      map=L.map('map').setView([-8.05,-34.9],12);
                      var baseLayer=L.tileLayer('https://{s}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png',{maxZoom:20,attribution:'© OpenStreetMap contributors, OSM France'}).addTo(map);
                      baseLayer.on('tileerror',function(){
                        if(!window._mapFallbackUsed){
                          window._mapFallbackUsed=true;
                          map.removeLayer(baseLayer);
                          baseLayer=L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',{maxZoom:20,attribution:'© OpenStreetMap contributors © CARTO',subdomains:'abcd'}).addTo(map);
                        }
                      });
                      line=L.polyline([],{color:'#1688E8',weight:5}).addTo(map);
                      document.getElementById('fallback').style.display='none';
                      setTimeout(function(){map.invalidateSize(true)},300);
                      return true;
                    }
                    initMap();

                    function setDestination(lat,lng){
                      if(!map && !initMap()) return;
                      if(!Number.isFinite(lat)||!Number.isFinite(lng)) return;
                      destination=[lat,lng];
                      if(!destinationMarker){
                        destinationMarker=L.marker(destination).addTo(map).bindPopup('🏠 Destino do cliente');
                      }else{
                        destinationMarker.setLatLng(destination);
                      }
                      destinationMarker.openPopup();
                      fitEverything();
                    }

                    function fitEverything(){
                      if(!map) return;
                      var bounds=L.latLngBounds([]);
                      var hasBounds=false;
                      if(marker){bounds.extend(marker.getLatLng());hasBounds=true;}
                      if(destinationMarker){bounds.extend(destinationMarker.getLatLng());hasBounds=true;}
                      if(line && line.getLatLngs().length>1){bounds.extend(line.getBounds());hasBounds=true;}
                      if(hasBounds){map.fitBounds(bounds,{padding:[35,35],maxZoom:16});}
                    }

                    function updateDistance(){
                      if(!marker||!destination) return;
                      var meters=map.distance(marker.getLatLng(),L.latLng(destination[0],destination[1]));
                      var text=meters<1000 ? Math.round(meters)+' m' : (meters/1000).toFixed(1).replace('.',',')+' km';
                      marker.bindPopup('🚚 Entregador<br>📏 Aproximadamente '+text+' do destino');
                    }

                    function updateTrack(lat,lng,pathText){
                      if(!map && !initMap()) return;
                      var pts=[];try{pts=JSON.parse(pathText||'[]')}catch(e){}
                      var ll=pts.map(function(p){return [Number(p.lat),Number(p.lng)]});
                      if(lat!==null&&lng!==null&&Number.isFinite(lat)&&Number.isFinite(lng)){
                        var current=[lat,lng];
                        if(ll.length===0||ll[ll.length-1][0]!==lat||ll[ll.length-1][1]!==lng)ll.push(current);
                        if(!marker)marker=L.marker(current).addTo(map).bindPopup('🚚 Entregador');else marker.setLatLng(current);
                        updateDistance();
                      }
                      line.setLatLngs(ll);
                      if(destination || ll.length>1){fitEverything();}
                    }
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        },
        update = { web ->
            val lat = order.trackingLat?.toString() ?: "null"
            val lng = order.trackingLng?.toString() ?: "null"
            val destinationScript = destination?.let { (dLat, dLng) ->
                "if(typeof setDestination==='function'){setDestination($dLat,$dLng);}"
            } ?: ""
            val script = destinationScript +
                    "if(typeof updateTrack==='function'){updateTrack($lat,$lng,${JSONObject.quote(pathJson)});}"
            web.tag = script
            if (web.progress >= 100) web.evaluateJavascript(script, null)
        }
    )
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("Voltar", color = LightBlue) }
}
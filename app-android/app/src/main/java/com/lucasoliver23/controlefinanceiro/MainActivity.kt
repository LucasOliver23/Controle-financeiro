package com.lucasoliver23.controlefinanceiro

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * App Android do Controle Financeiro Pro: mostra o site dentro de um WebView, em tela cheia,
 * sem barra de endereço e sem o aviso do Chrome. As versões novas do site chegam sozinhas.
 *
 * O que o WebView não faz sozinho, o app faz pela ponte "AndroidApp" (usada pelo index.html):
 *  - login com Google (o Google bloqueia login dentro de WebView): Credential Manager nativo
 *  - salvar e compartilhar arquivos (CSV, resumo do mês)
 *  - lembretes de vencimento como notificação no celular (Lembretes.kt)
 *  - desbloqueio do app com a digital (BiometricPrompt)
 */
class MainActivity : FragmentActivity() {

    companion object {
        private const val SITE = "https://controlo-financeiro-pro-3.vercel.app/"
        private val HOSTS_DO_APP = setOf(
            "controlo-financeiro-pro-3.vercel.app",
            "controle-financeiro-e5936.firebaseapp.com"
        )
        // ID do cliente Web do Firebase (Authentication -> Google -> Web SDK configuration)
        private const val GOOGLE_WEB_CLIENT_ID =
            "243116699910-sqa670ifhp6685vdg0o3bb4gc0tepgu5.apps.googleusercontent.com"
    }

    private lateinit var web: WebView
    private var arquivoCallback: ValueCallback<Array<Uri>>? = null
    private var fotoCamera: Uri? = null

    // Android 13+: pede permissão para mostrar as notificações dos lembretes
    private val pedirNotificacao =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val escolherArquivo =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            var resultado = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            // Foto tirada pela câmera: o app de câmera grava no arquivo que passamos e não devolve dados
            val foto = fotoCamera
            if (resultado == null && r.resultCode == RESULT_OK && foto != null) resultado = arrayOf(foto)
            arquivoCallback?.onReceiveValue(resultado)
            arquivoCallback = null
            fotoCamera = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 desenha por trás das barras do sistema: afasta o conteúdo delas (e do teclado)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val raiz = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        web = WebView(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        raiz.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, raiz).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString ControleFinanceiroApp"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Ponte(), "AndroidApp")
        web.webViewClient = Cliente()
        web.webChromeClient = Chrome()

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(SITE)
    }

    // Avisa o site quando o app sai/volta da tela (para pedir o PIN de novo depois de 1 minuto)
    override fun onStop() { super.onStop(); if (::web.isInitialized) js("window.onAppBackground && window.onAppBackground()") }
    override fun onStart() { super.onStart(); if (::web.isInitialized) js("window.onAppForeground && window.onAppForeground()") }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // ---------- Navegação: o site fica no app, links de fora abrem no navegador/app certo ----------
    private inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if ((uri.scheme == "https" || uri.scheme == "http") && uri.host in HOSTS_DO_APP) return false
            abrirFora(uri.toString())
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val html = """
                <html><body style="background:#000;color:#fff;font-family:sans-serif;display:flex;
                flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
                <p style="font-size:18px">Sem conexão com a internet</p>
                <button onclick="location.href='$SITE'" style="margin-top:16px;padding:12px 28px;border:0;
                border-radius:24px;background:#00d084;color:#000;font-size:16px;font-weight:bold">Tentar de novo</button>
                </body></html>
            """.trimIndent()
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    private fun abrirFora(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app para abrir este link", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o link", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Avisos (alert/confirm/prompt) sem mostrar o endereço do site ----------
    private inner class Chrome : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
        ): Boolean {
            val campo = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(campo)
                .setPositiveButton("OK") { _, _ -> result.confirm(campo.text.toString()) }
                .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        // <input type="file">: foto do perfil e importar extrato
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            arquivoCallback?.onReceiveValue(null)
            arquivoCallback = callback
            val soImagem = params.acceptTypes.isNotEmpty() && params.acceptTypes.all { it.startsWith("image/") }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (soImagem) "image/*" else "*/*"
            }
            val escolha = Intent.createChooser(intent, if (soImagem) "Foto do comprovante" else "Escolher arquivo")
            // Para imagens (comprovante, foto do perfil) também oferece tirar a foto na hora
            if (soImagem) {
                try {
                    val pasta = File(cacheDir, "compartilhar").apply { mkdirs() }
                    val arquivo = File(pasta, "foto_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.arquivos", arquivo)
                    val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (camera.resolveActivity(packageManager) != null) {
                        fotoCamera = uri
                        escolha.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
                    }
                } catch (e: Exception) { fotoCamera = null }
            }
            return try {
                escolherArquivo.launch(escolha)
                true
            } catch (e: Exception) {
                arquivoCallback = null
                false
            }
        }
    }

    // ---------- Ponte com o site (window.AndroidApp) ----------
    private inner class Ponte {
        @JavascriptInterface
        fun googleSignIn() = runOnUiThread { entrarComGoogle() }

        @JavascriptInterface
        fun googleSignOut() = runOnUiThread {
            lifecycleScope.launch {
                try { CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest()) }
                catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun saveFile(nome: String, mime: String, base64: String) = runOnUiThread { salvarArquivo(nome, mime, base64) }

        @JavascriptInterface
        fun scheduleReminders(json: String) = runOnUiThread {
            Lembretes.criarCanal(this@MainActivity)
            Lembretes.salvarEAgendar(this@MainActivity, json)
            val temAviso = json.trim().length > 2
            if (temAviso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Lembretes.podeNotificar(this@MainActivity)) {
                val prefs = getSharedPreferences("lembretes", Context.MODE_PRIVATE)
                // Pergunta uma vez; se a pessoa negar, o Android não deixa perguntar de novo
                if (!prefs.getBoolean("pediu_permissao", false)) {
                    prefs.edit().putBoolean("pediu_permissao", true).apply()
                    pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        @JavascriptInterface
        fun biometricAvailable(): Boolean =
            BiometricManager.from(this@MainActivity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

        @JavascriptInterface
        fun biometricAuth() = runOnUiThread { pedirDigital() }

        @JavascriptInterface
        fun notificationsAllowed(): Boolean = Lembretes.podeNotificar(this@MainActivity)

        @JavascriptInterface
        fun shareFile(nome: String, mime: String, base64: String) = runOnUiThread { compartilharArquivo(nome, mime, base64) }
    }

    private fun entrarComGoogle() {
        val opcao = GetSignInWithGoogleOption.Builder(GOOGLE_WEB_CLIENT_ID).build()
        val pedido = GetCredentialRequest.Builder().addCredentialOption(opcao).build()
        lifecycleScope.launch {
            try {
                val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, pedido).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    js("window.onNativeGoogleLogin && window.onNativeGoogleLogin(${JSONObject.quote(token)})")
                } else {
                    erroLogin("resposta inesperada do Google")
                }
            } catch (e: GetCredentialCancellationException) {
                erroLogin("cancelado")
            } catch (e: NoCredentialException) {
                erroLogin("nenhuma conta Google neste celular")
            } catch (e: GetCredentialException) {
                erroLogin(e.message ?: e.type)
            } catch (e: Exception) {
                erroLogin(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun pedirDigital() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                js("window.onBiometricResult && window.onBiometricResult(true)")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                js("window.onBiometricResult && window.onBiometricResult(false)")
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Controle Financeiro")
            .setSubtitle("Use a digital para abrir")
            .setNegativeButtonText("Usar PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try { prompt.authenticate(info) } catch (e: Exception) { js("window.onBiometricResult && window.onBiometricResult(false)") }
    }

    private fun erroLogin(msg: String) =
        js("window.onNativeGoogleLoginError && window.onNativeGoogleLoginError(${JSONObject.quote(msg)})")

    private fun js(codigo: String) = web.evaluateJavascript(codigo, null)

    private fun nomeSeguro(nome: String) = nome.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "arquivo" }

    private fun salvarArquivo(nome: String, mime: String, base64: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { compartilharArquivo(nome, mime, base64); return }
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val valores = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nomeSeguro(nome))
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IllegalStateException("sem acesso a Downloads")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "Salvo em Downloads: ${nomeSeguro(nome)}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            compartilharArquivo(nome, mime, base64)
        }
    }

    private fun compartilharArquivo(nome: String, mime: String, base64: String) {
        try {
            val pasta = File(cacheDir, "compartilhar").apply { mkdirs() }
            val arquivo = File(pasta, nomeSeguro(nome))
            arquivo.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            val uri = FileProvider.getUriForFile(this, "$packageName.arquivos", arquivo)
            val enviar = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(enviar, "Compartilhar"))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível compartilhar o arquivo", Toast.LENGTH_SHORT).show()
        }
    }
}

package com.lucasoliver23.controlefinanceiro

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
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
import androidx.activity.ComponentActivity
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
 */
class MainActivity : ComponentActivity() {

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

    private val escolherArquivo =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            arquivoCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            arquivoCallback = null
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
            return try {
                escolherArquivo.launch(Intent.createChooser(intent, "Escolher arquivo"))
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

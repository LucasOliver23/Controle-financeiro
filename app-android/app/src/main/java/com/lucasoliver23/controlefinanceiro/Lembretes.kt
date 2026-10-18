package com.lucasoliver23.controlefinanceiro

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Lembretes de vencimento: o site manda a lista de avisos (JSON) pela ponte AndroidApp.scheduleReminders
 * e aqui cada aviso vira um alarme do Android, que mostra a notificação na hora marcada.
 * A lista fica guardada para ser agendada de novo quando o celular reinicia.
 */
object Lembretes {
    private const val PREFS = "lembretes"
    private const val CHAVE_LISTA = "lista"
    private const val CHAVE_QTD = "qtd"
    const val CANAL = "vencimentos"
    const val ACAO = "com.lucasoliver23.controlefinanceiro.LEMBRETE"

    fun salvarEAgendar(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE_LISTA, json).apply()
        agendar(ctx)
    }

    fun agendar(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarmes = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancela os alarmes da lista anterior
        repeat(prefs.getInt(CHAVE_QTD, 0)) { i ->
            PendingIntent.getBroadcast(ctx, i, Intent(ctx, ReceptorLembrete::class.java).setAction(ACAO),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarmes.cancel(it); it.cancel() }
        }

        val lista = try { JSONArray(prefs.getString(CHAVE_LISTA, "[]")) } catch (e: Exception) { JSONArray() }
        val agora = System.currentTimeMillis()
        var qtd = 0
        for (i in 0 until lista.length()) {
            val item = lista.optJSONObject(i) ?: continue
            val quando = item.optLong("at", 0L)
            if (quando <= agora) continue
            val intent = Intent(ctx, ReceptorLembrete::class.java).setAction(ACAO)
                .putExtra("titulo", item.optString("title"))
                .putExtra("texto", item.optString("text"))
                .putExtra("id", item.optString("id").hashCode())
            val pi = PendingIntent.getBroadcast(ctx, qtd, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Não exige a permissão de "alarme exato": o Android pode atrasar alguns minutos, o que basta para um lembrete
            alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            qtd++
        }
        prefs.edit().putInt(CHAVE_QTD, qtd).apply()
    }

    fun criarCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL, "Vencimentos", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avisos de contas, faturas e parcelas que vão vencer" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    fun podeNotificar(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}

/** Celular reiniciou ou o app foi atualizado: os alarmes somem, então agenda tudo de novo. */
class ReceptorReinicio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Lembretes.agendar(ctx)
    }
}

/** Mostra a notificação na hora marcada (só recebe os alarmes do próprio app). */
class ReceptorLembrete : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Lembretes.ACAO) mostrar(ctx, intent)
    }

    private fun mostrar(ctx: Context, intent: Intent) {
        if (!Lembretes.podeNotificar(ctx)) return
        Lembretes.criarCanal(ctx)
        val abrir = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val texto = intent.getStringExtra("texto") ?: ""
        val notificacao = NotificationCompat.Builder(ctx, Lembretes.CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setColor(0xFF00D084.toInt())
            .setContentTitle(intent.getStringExtra("titulo") ?: "Conta vencendo")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(intent.getIntExtra("id", 1), notificacao)
        } catch (e: SecurityException) { }
    }
}

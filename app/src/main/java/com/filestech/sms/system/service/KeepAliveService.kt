package com.filestech.sms.system.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import com.filestech.sms.system.notifications.NotificationChannelInitializer
import timber.log.Timber

/**
 * v1.3.10 — Service permanent **opt-in** dont l'unique rôle est de **maintenir le
 * processus SMS Tech vivant** sur les ROM agressives qui tuent les apps en arrière-plan
 * (Xiaomi MIUI / HyperOS, Huawei EMUI / HarmonyOS, certaines OnePlus OxygenOS antérieures,
 * Realme realmeUI).
 *
 * **Pourquoi** : sur ces ROMs, même avec le rôle SMS officiel + les 4 réglages manuels
 * (auto-start, notifications, batterie, lock), le système tue silencieusement le processus
 * entre 2 SMS — les `BroadcastReceiver` ne sont jamais ré-instanciés, les MMS download
 * tombent dans le vide, les notifications ne sont jamais posées. C'est par design Xiaomi
 * (whitelist Google Messages + Mi Messages, discrimine tout le reste). Aucune permission
 * runtime ni configuration utilisateur ne peut contourner ce comportement sur HyperOS 2024+.
 *
 * La seule technique connue pour bypasser ce kill est : **forcer le processus à être visible
 * comme "service au premier plan"** via `startForeground` + une notification persistante
 * dans le shade. C'est exactement ce que font WhatsApp ("WhatsApp se reconnecte..."),
 * Signal, Telegram, et toutes les apps anti-spam SMS. Le service ne fait RIEN d'autre :
 * pas de logique métier, pas de polling, pas de réseau, pas de wakelock — juste être
 * là pour que MIUI/HarmonyOS classent SMS Tech parmi les apps "user-active".
 *
 * **Opt-in strict** : ce service n'est PAS démarré par défaut. L'utilisateur doit activer
 * explicitement le toggle "Mode résistant Xiaomi/Huawei" dans Réglages → Avancé. La notif
 * persistante (canal [NotificationChannelInitializer.CHANNEL_KEEP_ALIVE], importance LOW
 * = silencieuse, masquable depuis les réglages OS) reste visible tant que le mode est ON ;
 * désactiver le toggle stoppe le service et la notif disparaît immédiatement.
 *
 * **Coût** : ~5-10 Mo RAM permanents (le processus est gardé en heap), batterie négligeable
 * (service idle, aucun work). Sur ROMs non-agressives (Pixel, Samsung One UI, Sony, Fairphone,
 * Nothing OS, OnePlus récent OxygenOS), ce mode est INUTILE — laisser OFF par défaut.
 *
 * **Démarrage / arrêt** :
 * - Démarré par [com.filestech.sms.MainApplication] au boot de l'app si le flag DataStore
 *   `AdvancedSettings.keepAliveService` est `true`.
 * - Démarré par [com.filestech.sms.system.receiver.BootReceiver] au boot du device si flag ON.
 * - Démarré par [SettingsViewModel] quand l'utilisateur active le toggle.
 * - Arrêté par les mêmes call-sites quand le flag passe à `false`.
 *
 * **Compat Android 14+ (API 34+)** : `ServiceCompat.startForeground` est utilisé pour
 * passer explicitement le type déclaré au manifeste. Sans ce type explicite, Android 14+
 * tuerait le service avec `ForegroundServiceTypeNotAllowedException` au démarrage.
 *
 * ## v1.27.10 — pourquoi le type est `specialUse` et non `dataSync`
 *
 * La revue externe GitLab !38458 a relevé deux règles d'Android 15 que ce service enfreignait
 * sous `targetSdk = 35`, chacune le neutralisant **en silence** :
 *  1. un service `dataSync` est borné à 6 h cumulées par tranche de 24 h ; passé ce délai la
 *     plateforme appelle [onTimeout] et tue l'application si le service ne s'arrête pas. Un
 *     service dont le seul rôle est de rester vivant en permanence ne peut pas être de ce
 *     type : il mourait toutes les 6 h sans jamais revenir.
 *  2. démarrer un service `dataSync` depuis `BOOT_COMPLETED` est interdit. L'appel venu de
 *     [com.filestech.sms.system.receiver.BootReceiver] levait donc une exception, avalée par le
 *     catch défensif ci-dessous : le mode résistant ne reprenait pas après un redémarrage.
 *
 * `specialUse` échappe aux deux, et surtout il **décrit ce que ce service fait vraiment** —
 * rien, sinon exister. `dataSync` était une déclaration inexacte autant qu'un piège : ce
 * service n'a jamais synchronisé la moindre donnée.
 *
 * [onTimeout] reste implémenté malgré tout : si une politique future ou une ROM appliquait
 * malgré tout un minuteur, ne pas s'arrêter à temps vaut un plantage
 * (`ForegroundServiceDidNotStopInTimeException`), pas une simple perte de fonction.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildPersistentNotification()
        // v1.3.10 — défense en profondeur contre les 3 modes d'échec connus de
        // `startForeground` sur Android moderne :
        //
        //   1. **POST_NOTIFICATIONS révoquée (Android 13+)** : Samsung Auto Blocker, MIUI
        //      Sécurité, ou simple révocation user → `SecurityException` ou exception
        //      silencieuse. Sans catch, le processus crashe systématiquement à chaque
        //      tentative de démarrage (ANR au boot device si flag ON).
        //   2. **ForegroundServiceStartNotAllowedException (Android 12+)** : démarrage
        //      depuis un contexte background (BootReceiver sur Android 12+) sans
        //      permission `SYSTEM_ALERT_WINDOW` ni cas dérogatoire ROLE_SMS. Pour SMS
        //      Tech le rôle SMS doit théoriquement déroger, mais MIUI/HyperOS ignore
        //      parfois cette dérogation.
        //   3. **MissingForegroundServiceTypeException (Android 14+)** : si l'appel
        //      ServiceCompat.startForeground n'a pas le `foregroundServiceType` valide
        //      ou si la permission `FOREGROUND_SERVICE_<TYPE>` manque. Couvert ici par
        //      le branchement `UPSIDE_DOWN_CAKE` + manifest `FOREGROUND_SERVICE_SPECIAL_USE`.
        //
        // Sur toutes ces exceptions, on log et on retourne `START_NOT_STICKY` : pas la
        // peine de re-tenter automatiquement un démarrage qui vient d'échouer pour
        // raison configuration utilisateur — il faudrait que l'utilisateur corrige côté
        // OS d'abord. Le crash silencieux préserve le reste de l'app (les SMS texte
        // continuent d'arriver via les BroadcastReceiver, qui ne dépendent pas du
        // KeepAliveService).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // Couvre SecurityException, ForegroundServiceStartNotAllowedException
            // (Android 12+, NoClassDefFoundError sur API < 31), et autres exceptions
            // ROM-spécifiques (MIUI peut lancer des sous-classes propriétaires).
            Timber.w(
                t,
                "KeepAliveService.startForeground failed — likely POST_NOTIFICATIONS revoked or background restriction. Service will not stay alive.",
            )
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY : si le système tue le service malgré tout (très rare avec une notif
        // foreground), Android le redémarre automatiquement dès que possible. C'est ceinture +
        // bretelles pour les ROMs les plus agressives.
        return START_STICKY
    }

    /**
     * v1.27.10 — filet de securite contre le minuteur de service au premier plan.
     *
     * Ce service est declare `specialUse`, type auquel Android 15 n'applique PAS le plafond de
     * 6 h des services `dataSync`. Cette redefinition ne devrait donc jamais s'executer. Elle
     * existe parce que la sanction, si elle s'executait sans nous, n'est pas une perte de
     * fonction mais un plantage : la plateforme leve
     * `ForegroundServiceDidNotStopInTimeException` quand un service notifie ne s'arrete pas
     * dans les secondes qui suivent. On s'arrete donc proprement, et on le trace — le journal
     * est le seul indice qu'aurait un utilisateur dont le mode resistant s'est tu.
     *
     * `stopSelf()` et non `stopForeground` : la notification persistante doit disparaitre avec
     * le service, sinon elle promet une protection qui ne court plus.
     */
    override fun onTimeout(startId: Int) {
        Timber.w("KeepAliveService: foreground-service timeout (startId=%d) — stopping", startId)
        stopSelf(startId)
    }

    private fun buildPersistentNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // v1.27.11 (revue externe GitLab !38458, constat 4) — canal dedie en `IMPORTANCE_LOW`,
        // et `PRIORITY_LOW` pour l'accompagner. Android demande au moins `LOW` pour la
        // notification d'un service au premier plan ; on postait en `MIN` sur `MIN`. Le canal a
        // du changer d'identifiant : l'importance d'un canal existant ne se releve pas.
        return NotificationCompat.Builder(this, NotificationChannelInitializer.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            // Tap ouvre l'app. Pas d'action delete (la notif disparaîtra naturellement
            // quand l'utilisateur désactive le toggle dans Réglages → Avancé).
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4B41 // 'KA'

        /**
         * Démarre le service en mode foreground. Sans-op si le service est déjà actif
         * (Android dédoublonne via `Service.onStartCommand` sur la même intent). Pour
         * démarrer proprement avant Android 14+, `Context.startForegroundService` est requis.
         *
         * **v1.3.10** — try/catch défensif : sur Android 12+ (API 31+), `startForegroundService`
         * peut throw `ForegroundServiceStartNotAllowedException` AVANT même que le service
         * soit instancié si l'app est en background sans dérogation applicable (cas rare car
         * SMS Tech détient `ROLE_SMS` qui déroge, mais MIUI/HyperOS contourne parfois). On
         * log et on continue silencieusement plutôt que de crasher l'app appelante (peut
         * être MainApplication observer, BootReceiver, ou SettingsScreen onChange — un crash
         * dans l'un de ces 3 chemins serait visible utilisateur).
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Timber.w(
                    t,
                    "KeepAliveService.start failed — likely Android 12+ background restriction. User must re-trigger from Settings.",
                )
            }
        }

        /**
         * Arrête le service et retire la notification persistante. Idempotent —
         * `stopService` est no-op si le service n'est pas démarré. try/catch défensif au cas
         * où une ROM exotique throw sur un service non démarré.
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (t: Throwable) {
                Timber.w(t, "KeepAliveService.stop failed — service likely not running.")
            }
        }
    }
}

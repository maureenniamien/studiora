package com.maureen.studiora;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * Widget d'écran d'accueil : mascotte + conseil du jour.
 * Taille adaptative (voir studiora_widget_info.xml) — l'utilisateur peut le
 * redimensionner librement. Tap n'importe où sur le widget = ouvre l'app
 * (aucune autre action, comme demandé).
 */
public class StudioraWidgetProvider extends AppWidgetProvider {

    // Liste volontairement statique et embarquée (pas d'appel réseau depuis
    // un widget : ça doit rester instantané et fonctionner hors-ligne). Le
    // conseil du jour est choisi par un index basé sur le jour de l'année,
    // donc stable toute la journée et identique pour tous les widgets posés,
    // et change automatiquement le lendemain sans action de l'utilisateur.
    private static final String[] TIPS = {
        "Révise 15 minutes par jour plutôt que 3h une seule fois : la mémoire aime la répétition.",
        "Avant d'ouvrir ton cours, rappelle-toi ce que tu sais déjà sur le sujet — ça ancre les nouvelles infos.",
        "Un exercice raté aujourd'hui, c'est un point compris pour de bon demain.",
        "Explique un concept à voix haute comme si tu l'enseignais : c'est le meilleur test de compréhension.",
        "Fais tes révisions les plus difficiles quand tu es le plus concentré, pas en fin de journée.",
        "Note tes questions au lieu de les laisser filer — reviens-y avec le Coach IA.",
        "Une pause de 5 minutes toutes les 25 minutes vaut mieux que 2h d'affilée sans respirer.",
        "Refais un exercice déjà réussi la semaine dernière : si tu galères, c'est qu'il faut le revoir.",
        "Le doute n'est pas un échec, c'est le signal que ton cerveau est en train d'apprendre.",
        "Avant un quiz, relis juste tes erreurs précédentes plutôt que tout le cours.",
        "Range ton téléphone hors de vue pendant les sessions de révision — la concentration s'use vite.",
        "Un petit pas chaque jour bat un grand effort une fois par mois.",
        "Si un exercice te bloque plus de 10 minutes, passe au suivant et reviens-y après.",
        "Écrire à la main ce que tu apprends aide à mieux le retenir qu'en tapant.",
        "Compare toujours ta progression d'aujourd'hui à celle d'hier, jamais à celle des autres.",
        "La régularité compte plus que l'intensité : viens ne serait-ce que 10 minutes aujourd'hui.",
        "Teste-toi avant de relire le cours : essayer de se souvenir renforce la mémoire.",
        "Une bonne nuit de sommeil vaut souvent mieux qu'une heure de révision en plus.",
        "Découpe un grand chapitre en petites parties : c'est moins intimidant et plus efficace.",
        "Célèbre tes séries de jours consécutifs — la constance se construit petit à petit."
    };

    private static String tipOfToday() {
        int dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return TIPS[dayOfYear % TIPS.length];
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateOneWidget(context, appWidgetManager, appWidgetId);
        }
    }

    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.studiora_widget);
        views.setTextViewText(R.id.widget_tip, tipOfToday());

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, appWidgetId, launchIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}

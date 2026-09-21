package com.maureen.studiora;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * Widget d'écran d'accueil : mascotte + conseil du jour.
 * Taille adaptative (voir studiora_widget_info.xml) — l'utilisateur peut le
 * redimensionner librement. Tap n'importe où sur le widget = ouvre l'app
 * (aucune autre action, comme demandé).
 *
 * MISE À JOUR (2026-09-17) : la mascotte affiche désormais l'un de 4 états
 * du jour, poussés par la WebView via le pont "AndroidWidgetSync"
 * (WidgetSyncInterface dans MainActivity.java) puisqu'un widget ne peut pas
 * lire le localStorage/IndexedDB de la page :
 *  - "todo"  : rien fait aujourd'hui -> mascotte au bureau, pas encore arrosé
 *  - "bravo" : juste après une validation -> célébration, redescend seule
 *              vers "done" au bout de BRAVO_DURATION_MS
 *  - "done"  : session du jour terminée -> jeune pousse arrosée
 *  - "sick"  : série interrompue -> arbre fané qu'on ré-arrose (convalescence)
 */
public class StudioraWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "studiora_widget";
    public static final String KEY_STATE = "state"; // "todo" | "done" | "sick" | "bravo"
    public static final String KEY_DATE = "state_date"; // "yyyy-DDD" (année-jour de l'année)
    public static final String KEY_BRAVO_TS = "bravo_ts";
    private static final long BRAVO_DURATION_MS = 20 * 60 * 1000; // 20 minutes

    private static final String[] TIPS = {
        "Ton arbre de connaissance attend d'être arrosé — viens faire un cours aujourd'hui !",
        "Révise 15 minutes par jour plutôt que 3h une seule fois : la mémoire aime la répétition.",
        "Chaque quiz fait grandir ton arbre un peu plus. On y va ?",
        "Un exercice raté aujourd'hui, c'est un point compris pour de bon demain.",
        "Explique un concept à voix haute comme si tu l'enseignais : c'est le meilleur test de compréhension.",
        "Ta série est en jeu — une petite session aujourd'hui pour la garder vivante.",
        "Note tes questions au lieu de les laisser filer — reviens-y avec le Coach IA.",
        "Une pause de 5 minutes toutes les 25 minutes vaut mieux que 2h d'affilée sans respirer.",
        "Refais un exercice déjà réussi la semaine dernière : si tu galères, c'est qu'il faut le revoir.",
        "Le doute n'est pas un échec, c'est le signal que ton cerveau est en train d'apprendre.",
        "Ton arbre a besoin de toi aujourd'hui — un petit cours suffit pour le faire évoluer.",
        "Un petit pas chaque jour bat un grand effort une fois par mois.",
        "Si un exercice te bloque plus de 10 minutes, passe au suivant et reviens-y après.",
        "Écrire à la main ce que tu apprends aide à mieux le retenir qu'en tapant.",
        "Compare toujours ta progression d'aujourd'hui à celle d'hier, jamais à celle des autres.",
        "La régularité compte plus que l'intensité : viens ne serait-ce que 10 minutes aujourd'hui.",
        "Teste-toi avant de relire le cours : essayer de se souvenir renforce la mémoire.",
        "Un chapitre à la fois — ouvre l'app et continue là où tu t'es arrêté(e).",
        "Découpe un grand chapitre en petites parties : c'est moins intimidant et plus efficace.",
        "Célèbre tes séries de jours consécutifs — la constance se construit petit à petit."
    };

    private static String tipOfToday() {
        int dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return TIPS[dayOfYear % TIPS.length];
    }

    public static String todayDateStr() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    // Appelé par WidgetSyncInterface juste après avoir écrit le nouvel état,
    // pour rafraîchir le widget tout de suite au lieu d'attendre le prochain
    // cycle périodique du système (qui peut prendre jusqu'à 30 minutes).
    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, StudioraWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            new StudioraWidgetProvider().onUpdate(context, mgr, ids);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateOneWidget(context, appWidgetManager, appWidgetId);
        }
    }

    // Décide quelle scène afficher, et gère les deux transitions automatiques
    // (nouveau jour -> "todo", "bravo" expiré -> "done") pour que le widget
    // reste cohérent même si l'app n'est pas réouverte entre deux passages.
    private int sceneDrawableFor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String state = prefs.getString(KEY_STATE, "todo");
        String storedDate = prefs.getString(KEY_DATE, "");
        String today = todayDateStr();
        if (!today.equals(storedDate) && !"sick".equals(state)) {
            // Nouveau jour : on repart sur "todo" tant que rien n'a encore été
            // validé aujourd'hui. Si la série est cassée, c'est la WebView qui
            // repositionnera "sick" elle-même dès qu'elle sera rouverte.
            state = "todo";
            prefs.edit().putString(KEY_STATE, state).putString(KEY_DATE, today).apply();
        }
        if ("bravo".equals(state)) {
            long ts = prefs.getLong(KEY_BRAVO_TS, 0);
            if (System.currentTimeMillis() - ts > BRAVO_DURATION_MS) {
                state = "done";
                prefs.edit().putString(KEY_STATE, state).apply();
            }
        }
        switch (state) {
            case "sick": return R.drawable.studiora_widget_scene_sick;
            case "done": return R.drawable.studiora_widget_scene_done;
            case "bravo": return R.drawable.studiora_widget_scene_bravo;
            default: return R.drawable.studiora_widget_scene_todo;
        }
    }

    private void updateOneWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.studiora_widget);
        views.setTextViewText(R.id.widget_tip, tipOfToday());
        views.setImageViewResource(R.id.widget_scene, sceneDrawableFor(context));

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, appWidgetId, launchIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}


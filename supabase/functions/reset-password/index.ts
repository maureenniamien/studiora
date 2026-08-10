// supabase/functions/reset-password/index.ts
//
// Gere la reinitialisation de mot de passe en deux etapes :
//   action: "request" -> genere un code a 6 chiffres, l'envoie par email (Resend)
//   action: "confirm" -> verifie le code et enregistre le nouveau mot de passe
//
// Variables d'environnement requises (Supabase Dashboard -> Edge Functions -> Secrets) :
//   SUPABASE_URL          (deja presente par defaut dans l'environnement Supabase)
//   SUPABASE_SERVICE_ROLE_KEY  (cle service_role, PAS la cle anon -- necessaire
//                                pour lire/ecrire la table users sans restriction RLS)
//   RESEND_API_KEY        = re_xxx
//
// Deploiement : supabase functions deploy reset-password

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const CODE_TTL_MINUTES = 15;
const FROM_EMAIL = "Studiora <onboarding@resend.dev>"; // a remplacer par un domaine verifie une fois configure sur Resend

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }

  try {
    const SUPABASE_URL = Deno.env.get("SUPABASE_URL");
    const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const RESEND_API_KEY = Deno.env.get("RESEND_API_KEY");

    if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
      return json({ success: false, error: "SERVER_MISCONFIGURED: SUPABASE_URL/SERVICE_ROLE_KEY manquants" }, 500);
    }

    const body = await req.json();
    const { action, email } = body ?? {};

    if (!email || typeof email !== "string") {
      return json({ success: false, error: "Email requis" }, 422);
    }

    const dbHeaders = {
      "Content-Type": "application/json",
      apikey: SERVICE_ROLE_KEY,
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
    };

    if (action === "request") {
      // Toujours repondre pareil, que le compte existe ou non (evite de reveler
      // quels emails sont inscrits).
      const findRes = await fetch(
        `${SUPABASE_URL}/rest/v1/users?email=eq.${encodeURIComponent(email)}&select=email&limit=1`,
        { headers: dbHeaders }
      );
      const rows = await findRes.json();

      if (Array.isArray(rows) && rows.length > 0) {
        const code = String(Math.floor(100000 + Math.random() * 900000)); // 6 chiffres
        const codeHash = await sha256(code);
        const expires = new Date(Date.now() + CODE_TTL_MINUTES * 60 * 1000).toISOString();

        await fetch(`${SUPABASE_URL}/rest/v1/users?email=eq.${encodeURIComponent(email)}`, {
          method: "PATCH",
          headers: { ...dbHeaders, Prefer: "return=minimal" },
          body: JSON.stringify({ reset_code_hash: codeHash, reset_code_expires: expires }),
        });

        if (RESEND_API_KEY) {
          await fetch("https://api.resend.com/emails", {
            method: "POST",
            headers: {
              Authorization: `Bearer ${RESEND_API_KEY}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              from: FROM_EMAIL,
              to: email,
              subject: "Code de réinitialisation Studiora",
              html: `
                <div style="font-family:sans-serif;max-width:420px;margin:0 auto;padding:24px">
                  <h2 style="color:#0B6E4F">Réinitialise ton mot de passe</h2>
                  <p>Voici ton code de vérification, valable ${CODE_TTL_MINUTES} minutes :</p>
                  <p style="font-size:32px;font-weight:800;letter-spacing:6px;background:#E3F2EB;color:#0B6E4F;padding:16px;text-align:center;border-radius:12px">${code}</p>
                  <p style="color:#666;font-size:13px">Si tu n'as pas demandé cette réinitialisation, ignore cet email — ton compte reste sécurisé.</p>
                </div>
              `,
            }),
          });
        }
      }

      return json({ success: true, message: "Si un compte existe avec cet email, un code a été envoyé." });
    }

    if (action === "confirm") {
      const { code, pwd_hash, pwd_salt } = body ?? {};
      if (!code || !pwd_hash || !pwd_salt) {
        return json({ success: false, error: "Code et nouveau mot de passe requis" }, 422);
      }

      const findRes = await fetch(
        `${SUPABASE_URL}/rest/v1/users?email=eq.${encodeURIComponent(email)}&select=reset_code_hash,reset_code_expires&limit=1`,
        { headers: dbHeaders }
      );
      const rows = await findRes.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        return json({ success: false, error: "Code invalide ou expiré" }, 422);
      }

      const record = rows[0];
      if (!record.reset_code_hash || !record.reset_code_expires) {
        return json({ success: false, error: "Aucune demande de réinitialisation en cours" }, 422);
      }
      if (new Date(record.reset_code_expires).getTime() < Date.now()) {
        return json({ success: false, error: "Code expiré, redemande-en un nouveau" }, 422);
      }

      const codeHash = await sha256(code);
      if (codeHash !== record.reset_code_hash) {
        return json({ success: false, error: "Code invalide" }, 422);
      }

      await fetch(`${SUPABASE_URL}/rest/v1/users?email=eq.${encodeURIComponent(email)}`, {
        method: "PATCH",
        headers: { ...dbHeaders, Prefer: "return=minimal" },
        body: JSON.stringify({
          pwd_hash,
          pwd_salt,
          reset_code_hash: null,
          reset_code_expires: null,
        }),
      });

      return json({ success: true, message: "Mot de passe mis à jour." });
    }

    return json({ success: false, error: "Action inconnue" }, 400);
  } catch (e) {
    return json({ success: false, error: String(e) }, 500);
  }
});

async function sha256(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

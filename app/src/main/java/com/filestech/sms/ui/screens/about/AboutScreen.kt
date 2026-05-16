package com.filestech.sms.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.BuildConfig
import com.filestech.sms.R

/**
 * v1.2.6: re-skinned to mirror the PDF Tech About screen design — centered icon header with a
 * version pill, a privacy "badges card", grouped Material cards for features, an author card,
 * help recipes, then the SMS-Tech-specific permissions list and the legal/credits block at
 * the bottom. Visual identity unified across the Files Tech apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            HeaderBlock(
                onCheckUpdate = {
                    safeStartActivity(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                    )
                },
                onVersionTap = { tapCount++ },
                tapCount = tapCount,
            )

            Spacer(Modifier.size(28.dp))
            SectionTitle("Confidentialité")
            Spacer(Modifier.size(8.dp))
            PrivacyCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle("Fonctionnalités")
            Spacer(Modifier.size(8.dp))
            FEATURES.forEach { f -> FeatureRow(icon = f.icon, label = f.label, desc = f.desc) }

            Spacer(Modifier.size(24.dp))
            SectionTitle("Auteur")
            Spacer(Modifier.size(8.dp))
            AuthorCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle("Aide rapide")
            Spacer(Modifier.size(8.dp))
            HELP_RECIPES.forEach { h -> HelpCard(title = h.title, steps = h.steps) }

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_security_title))
            Spacer(Modifier.size(8.dp))
            SecurityCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_permissions_title))
            Spacer(Modifier.size(8.dp))
            PermissionLine("SEND_SMS / RECEIVE_SMS / READ_SMS / WRITE_SMS", "Required for any SMS app.")
            PermissionLine("RECEIVE_MMS / RECEIVE_WAP_PUSH", "Receive incoming MMS.")
            PermissionLine("READ_CONTACTS", "Show contact names instead of bare numbers.")
            PermissionLine("READ_PHONE_STATE / READ_PHONE_NUMBERS", "Multi-SIM support (sending from the correct SIM).")
            PermissionLine("POST_NOTIFICATIONS", "Show new-message notifications.")
            PermissionLine("USE_BIOMETRIC", "Optional biometric unlock.")
            PermissionLine("SCHEDULE_EXACT_ALARM", "Send scheduled messages at the exact time.")
            PermissionLine("INTERNET", "MMS transport via your carrier MMSC. No analytics.")
            PermissionLine("RECORD_AUDIO", "Record audio messages attached to outgoing MMS.")
            PermissionLine("FOREGROUND_SERVICE", "Long-running migration / backup.")

            Spacer(Modifier.size(24.dp))
            SectionTitle("Liens")
            Spacer(Modifier.size(8.dp))
            LinkItem(
                icon = Icons.Outlined.Code,
                label = stringResource(R.string.about_source_code),
                trailing = Icons.Outlined.Public,
                onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) },
            )
            LinkItem(
                icon = Icons.Outlined.BugReport,
                label = stringResource(R.string.about_report_issue),
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:contact@files-tech.com")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "SMS Tech ${BuildConfig.VERSION_NAME}")
                    }
                    safeStartActivity(context, intent)
                },
            )
            LinkItem(
                icon = Icons.Outlined.Language,
                label = stringResource(R.string.about_website),
                supporting = WEBSITE_URL,
                onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))) },
            )
            LinkItem(
                icon = Icons.Outlined.Gavel,
                label = stringResource(R.string.about_license),
                supporting = "Apache License 2.0",
                onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL))) },
            )
            LinkItem(
                icon = Icons.Outlined.PrivacyTip,
                label = stringResource(R.string.about_privacy),
                onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) },
            )

            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.about_credits_body, AUTHOR_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.about_legal_body, "2026", AUTHOR_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderBlock(
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    tapCount: Int,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.sms_tech_icon),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.size(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primaryContainer)
                .clickable { onVersionTap() }
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = cs.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        if (tapCount >= 7) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.about_easter_egg),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.about_tagline_short),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(14.dp))
        FilledTonalButton(onClick = onCheckUpdate) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Voir les mises à jour")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Privacy card with coloured badges
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PrivacyCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = Color(0xFF43A047),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "100 % privé — zéro surveillance",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.size(12.dp))
            BadgesFlow(
                badges = PRIVACY_BADGES,
            )
        }
    }
}

@Composable
private fun BadgesFlow(badges: List<PrivacyBadge>) {
    // Compose's FlowRow is in foundation since 1.4 — use a manual two-row layout that splits
    // the badges roughly evenly. Keeps the dependency surface flat (no new artifact).
    val mid = (badges.size + 1) / 2
    val rows = listOf(badges.take(mid), badges.drop(mid))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b -> Badge(icon = b.icon, label = b.label, color = b.color) }
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.3f), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Features
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(icon: ImageVector, label: String, desc: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            },
            headlineContent = {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
            },
            supportingContent = {
                Text(desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = 0.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Author card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AuthorCard() {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Contactless,
                        contentDescription = null,
                        tint = cs.primary,
                    )
                }
            },
            headlineContent = { Text(AUTHOR_NAME) },
            supportingContent = { Text("Développeur") },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Help recipes
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HelpCard(title: String, steps: List<String>) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp))) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = cs.onSurface,
            )
            Spacer(Modifier.size(6.dp))
            steps.forEachIndexed { i, step ->
                Row(
                    modifier = Modifier.padding(bottom = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${i + 1}. ",
                        fontSize = 12.sp,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = step,
                        fontSize = 12.sp,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Security card (SMS / MMS unencrypted at the protocol level)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SecurityCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = cs.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.about_security_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.about_security_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Permissions list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionLine(name: String, why: String) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { Text(why, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

// ─────────────────────────────────────────────────────────────────────────────
//  Links (Source / Issue / Website / License / Privacy)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LinkItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    supporting: String? = null,
    trailing: ImageVector? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing?.let { { Icon(it, contentDescription = null) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    // v1.2.6 polish : aligné sur les titres de Réglages (titleMedium SemiBold + primary blue).
    // Visuellement le même style entre les deux écrans, ce qui donne une identité Files Tech
    // cohérente.
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp),
    )
}

/** Audit U4: never crash if no Activity is registered for the intent. */
private fun safeStartActivity(context: android.content.Context, intent: android.content.Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { timber.log.Timber.w(it, "safeStartActivity: no handler for %s", intent.action) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Static data
// ─────────────────────────────────────────────────────────────────────────────

private const val AUTHOR_NAME = "Patrice Haltaya"
private const val REPO_URL = "https://github.com/gitubpatrice/sms_tech"
private const val RELEASES_URL = "https://github.com/gitubpatrice/SMS-TECH/releases/latest"
private const val WEBSITE_URL = "https://files-tech.com"
private const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
private const val PRIVACY_URL = "https://github.com/gitubpatrice/sms_tech/blob/main/PRIVACY.md"

private data class PrivacyBadge(val icon: ImageVector, val label: String, val color: Color)

private val PRIVACY_BADGES = listOf(
    PrivacyBadge(Icons.Outlined.Block, "Aucune publicité", Color(0xFFE53935)),
    PrivacyBadge(Icons.Outlined.VisibilityOff, "Aucun tracker", Color(0xFFFF7043)),
    PrivacyBadge(Icons.Outlined.Security, "Aucune collecte", Color(0xFF1976D2)),
    PrivacyBadge(Icons.Outlined.PrivacyTip, "Zéro partage", Color(0xFF7B1FA2)),
)

private data class Feature(val icon: ImageVector, val label: String, val desc: String)

private val FEATURES = listOf(
    Feature(Icons.Outlined.ChatBubbleOutline, "SMS & MMS", "App SMS par défaut Android 8 → 15+. Import historique complet, sans duplication."),
    Feature(Icons.Outlined.Mic, "Messages vocaux", "Push-to-talk type Google Messages. Cap 60 s / 300 Ko pour passer chez tous les opérateurs FR."),
    Feature(Icons.Outlined.Security, "Coffre chiffré", "Conversations sensibles dans un coffre SQLCipher, clé enveloppée dans le Keystore Android."),
    Feature(Icons.Outlined.Shield, "Code panique", "Second code qui ouvre l'app en mode leurre — coffre invisible et inaccessible."),
    Feature(Icons.Outlined.Fingerprint, "Biométrie", "Empreinte ou visage pour déverrouiller, avec PIN de secours obligatoire."),
    Feature(Icons.AutoMirrored.Outlined.Reply, "Réponse contextuelle", "Mini-cartouche au-dessus du composer + bulle réponse avec quote dans le thread."),
    Feature(Icons.Outlined.Translate, "Traduction locale", "Modèles ML Kit on-device (~30 Mo par paire). Aucun texte n'est envoyé sur le réseau."),
    Feature(Icons.Outlined.Block, "Blocage synchronisé", "Import auto des numéros bloqués Téléphone / Samsung Messages. Purge rétroactive."),
    Feature(Icons.Outlined.Search, "Recherche FTS", "Plein texte SQLite FTS4 sur corps, numéros et noms de contact."),
    Feature(Icons.Outlined.PictureAsPdf, "Export PDF", "Exporter une conversation en PDF (date, expéditeur, corps) pour archive ou preuve."),
    Feature(Icons.Outlined.Backup, "Backup chiffré", "Sauvegarde locale AES-256-GCM via passphrase distincte du PIN."),
    Feature(Icons.Outlined.DarkMode, "Thèmes", "Clair, sombre, AMOLED, Dark Tech (slate-blue + accent bleu). Coins arrondis personnalisables."),
    Feature(Icons.Outlined.GraphicEq, "UI fluide", "Compose + recomposition isolée par bulle. Tri des conversations dans le menu 3 points."),
    Feature(Icons.Outlined.QuestionAnswer, "Zéro pub, zéro traceur", "Aucune analytics. Seule connexion réseau : ton MMSC opérateur et ML Kit pour la traduction."),
)

private data class HelpRecipe(val title: String, val steps: List<String>)

private val HELP_RECIPES = listOf(
    HelpRecipe(
        title = "Définir comme app SMS par défaut",
        steps = listOf(
            "Bouton bleu \"Définir par défaut\" en haut de la liste des conversations",
            "Ou Réglages Android → Apps par défaut → SMS → SMS Tech",
        ),
    ),
    HelpRecipe(
        title = "Envoyer un message vocal",
        steps = listOf(
            "Appui long sur le micro à droite du composer",
            "Parle, puis relâche pour envoyer (slide à gauche = annuler)",
            "Cap 60 s — au-delà l'enregistrement s'arrête automatiquement",
        ),
    ),
    HelpRecipe(
        title = "Mettre une conversation au coffre",
        steps = listOf(
            "Long press sur la conversation dans la liste → \"Coffre\"",
            "Déverrouillage : PIN, passphrase ou biométrie selon ton réglage",
            "Réglages → Sécurité pour configurer le coffre",
        ),
    ),
    HelpRecipe(
        title = "Bloquer un numéro",
        steps = listOf(
            "Dans une conversation : menu ⋮ → Bloquer (= bloque + supprime la conv)",
            "Long press sur une conversation dans la liste → Bloquer (conserve l'historique)",
            "Le numéro est ajouté à la blocklist système, partagée avec Téléphone",
        ),
    ),
    HelpRecipe(
        title = "Traduire un message reçu",
        steps = listOf(
            "Long press sur la bulle → \"Traduire\"",
            "Si le modèle ML Kit n'est pas téléchargé, ça se fait en ~30 s",
            "Re-tap dessus pour masquer la traduction",
        ),
    ),
    HelpRecipe(
        title = "Vérifier une mise à jour",
        steps = listOf(
            "Bouton \"Voir les mises à jour\" en haut de cette page",
            "Ouvre la page Releases GitHub de SMS Tech",
            "L'app ne tente jamais d'auto-update sans ton accord",
        ),
    ),
)

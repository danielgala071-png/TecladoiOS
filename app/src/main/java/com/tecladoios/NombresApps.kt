package com.tecladoios

/**
 * Nombres de aplicaciones escritos como son: «whatsapp» → «WhatsApp», «tiktok» → «TikTok».
 * Entran al diccionario (así «whatsap» o «instragram» se corrigen) y al dar espacio se
 * ponen con sus mayúsculas. No están las que son palabras comunes (Notas, Claro, Word, Zoom,
 * Drive, Teams…) para que no te cambie esas palabras cuando no hablas de la app.
 */
object NombresApps {
    val LISTA: List<String> = listOf(
        // Mensajería y redes
        "WhatsApp", "Telegram", "Messenger", "Instagram", "Facebook", "TikTok", "YouTube",
        "Snapchat", "Threads", "LinkedIn", "Pinterest", "Reddit", "Discord", "Twitch", "Tumblr",
        "Quora", "Viber", "WeChat", "Weibo", "Bluesky", "Mastodon", "Clubhouse", "BeReal",
        "Threema", "Nekogram", "Truecaller", "Skype", "Slack", "Kick",
        // Citas
        "Tinder", "Bumble", "Badoo", "Happn", "Hinge", "Grindr",
        // Video, música y lectura
        "Netflix", "Spotify", "Deezer", "Crunchyroll", "Tubi", "ViX", "Mubi", "Vimeo",
        "Dailymotion", "SoundCloud", "Shazam", "Wattpad", "Kindle", "Audible", "Kodi", "Plex",
        "Jellyfin", "Disney", "Paramount",
        // Fotos y edición
        "CapCut", "InShot", "Canva", "Picsart", "Snapseed", "Lightroom", "Photoshop", "VSCO",
        // Trabajo, estudio y productividad
        "Gmail", "Outlook", "Hotmail", "Dropbox", "OneDrive", "PowerPoint", "Trello", "Evernote",
        "Todoist", "TickTick", "Duolingo", "Quizlet", "Brainly", "Photomath", "Symbolab",
        "GeoGebra", "Desmos", "Zotero", "Mendeley", "Feedly", "Inoreader", "Anki", "ChatGPT",
        "Copilot", "Gemini", "Perplexity",
        // Transporte, viajes y delivery
        "Uber", "Cabify", "inDrive", "Waze", "Moovit", "Airbnb", "Booking", "Latam", "Rappi",
        "PedidosYa", "Glovo", "Oltursa",
        // Pagos y bancos (Perú y otros)
        "Yape", "Plin", "Tunki", "Interbank", "Scotiabank", "Izipay", "Niubiz", "Culqi",
        "Kambista", "Rextie", "PayPal", "Payoneer", "Binance", "Revolut", "Nubank", "Ualá", "Tenpo",
        // Compras y tiendas (Perú)
        "AliExpress", "Amazon", "Shein", "Temu", "Falabella", "Ripley", "Tottus", "Sodimac",
        "Promart", "Oechsle", "Mifarma", "Inkafarma", "Cineplanet", "Cinemark", "Joinnus",
        "Teleticket", "Ticketmaster", "Urbania", "Adondevivir", "Linio", "Juntoz",
        // Operadoras (Perú)
        "Movistar", "Entel", "Bitel",
        // Juegos
        "Roblox", "Minecraft", "Fortnite", "Genshin", "Brawlhalla",
        // Salud y deporte
        "Strava", "Komoot", "Fitbit", "MyFitnessPal", "Headspace",
        // Herramientas y Android
        "Shareit", "Xender", "Zapya", "Tasker", "Termux", "Aptoide", "APKPure", "Uptodown",
        "MiXplorer", "Bitwarden", "LastPass", "Authy", "ProtonMail", "Tutanota",
    )

    /** Clave sin tildes en minúscula → nombre bien escrito */
    val MAPA: Map<String, String> = LISTA.associateBy { Texto.normal(it) }
}

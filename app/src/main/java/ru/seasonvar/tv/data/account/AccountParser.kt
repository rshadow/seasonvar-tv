package ru.seasonvar.tv.data.account

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ru.seasonvar.tv.data.AccountMark
import ru.seasonvar.tv.data.AccountProgress
import ru.seasonvar.tv.data.SeasonEpisodeProgress
import ru.seasonvar.tv.data.EntrySelectionParser
import ru.seasonvar.tv.data.MarkupChangedException
import ru.seasonvar.tv.data.SeasonvarParser
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.parseEntries

data class AccountUser(val id: String, val name: String, val avatarUrl: String?,
    val premium: AccountPremium = AccountPremium.Unknown)
data class AccountPage(val user: AccountUser, val entries: List<SeriesEntry>, val nextPage: String? = null)
class SessionExpiredException : Exception("Session expired")
class LoginRejectedException : Exception("Login rejected")

object AccountParser {
    private val seasonMessage = Regex("""Сезон\s+[0-9]+\s+из\s+[0-9]+""", RegexOption.IGNORE_CASE)
    fun user(html: String): AccountUser? {
        org.jsoup.parser.StreamParser(org.jsoup.parser.Parser.htmlParser()).use { parser ->
            parser.parse(html.reader(), "https://seasonvar.ru/")
            while (true) {
                val element = parser.selectNext(".headblock-menu, a[data-id]") ?: throw MarkupChangedException()
                if (element.hasClass("headblock-menu")) return user(Jsoup.parse(element.outerHtml(), "https://seasonvar.ru/"))
                element.remove()
            }
        }
    }
    internal fun user(doc: Document): AccountUser? {
        val header = doc.selectFirst(".headblock-menu") ?: throw MarkupChangedException()
        val links = header.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href").toHttpUrlOrNull()?.takeIf { siteOrigin(it) } ?: return@mapNotNull null
            a to url
        }
        val profile = links.firstOrNull { (_, u) -> u.encodedPath.matches(Regex("/profile/[1-9][0-9]*/?")) }
        if (profile == null && links.none { (_, u) -> u.queryParameter("mod") in setOf("logout", "synch") }) return null
        val id = profile?.second?.pathSegments?.getOrNull(1) ?: "session"
        val image = header.selectFirst("img[src]")
        val avatar = image?.absUrl("src")?.let(::imageUrl)
        val menuName = header.select("[data-menur] li").firstNotNullOfOrNull { row ->
            if (row.select("a, button").isNotEmpty()) null
            else row.ownText().ifBlank { row.text() }.trim().takeIf { it.isNotEmpty() }
        }
        val profileName = profile?.first?.text()?.trim()?.takeIf {
            it.isNotEmpty() && it.lowercase(java.util.Locale.ROOT) !in setOf("профиль", "мой профиль", "profile", "my profile")
        }
        val name = menuName ?: profileName ?: "Аккаунт Seasonvar"
        val premiumLabels = links.filter { (_, url) -> url.encodedPath.trimEnd('/') == "/premium" && url.query == null }
            .map { (link, _) -> link.text().replace('\u00a0', ' ').trim() }
        return AccountUser(id, name, avatar, premium(premiumLabels))
    }

    private fun premium(labels: List<String>): AccountPremium {
        if (labels.isEmpty()) return AccountPremium.Unknown
        val pattern = Regex("""Премиум(?:\s+аккаунт)?(?:\s+активен)?\s+до\s+([0-9]{2}\.[0-9]{2}\.[0-9]{4})""", RegexOption.IGNORE_CASE)
        val format = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu", java.util.Locale.ROOT)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val dates = labels.mapNotNull { label ->
            val text = pattern.matchEntire(label)?.groupValues?.get(1) ?: return@mapNotNull null
            try { java.time.LocalDate.parse(text, format) } catch (_: java.time.DateTimeException) { return AccountPremium.Unknown }
        }.distinct()
        if (dates.size == 1 && labels.all { pattern.matches(it) || it.equals("Премиум", true) || it.equals("Премиум аккаунт", true) })
            return AccountPremium.Until(dates.single())
        if (dates.isEmpty() && labels.all { it.equals("Премиум", true) || it.equals("Премиум аккаунт", true) })
            return AccountPremium.Inactive
        return AccountPremium.Unknown
    }
    fun resume(html: String): AccountPage = personalList(html, PersonalFeed.RESUME)

    fun personalList(html: String, feed: PersonalFeed, pageUrl: String = feed.url, windowed: Boolean = false): AccountPage {
        if (feed == PersonalFeed.HISTORY) return HistoryParser.parse(html, pageUrl, windowed)
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        val user = user(doc) ?: throw SessionExpiredException()
        if (doc.selectFirst(".pgs-marks") == null) throw MarkupChangedException()
        return snapshot(doc, user).page(feed, windowed)
    }

    fun filteredPersonalList(html: String, expectedUser: AccountUser, feed: PersonalFeed, windowed: Boolean = false): AccountPage {
        return filteredSnapshot(html, expectedUser).page(feed, windowed)
    }

    internal fun filteredSnapshot(html: String, expectedUser: AccountUser): ResumeSnapshot {
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        if (doc.selectFirst(".headblock-menu") != null && user(doc)?.id != expectedUser.id) throw SessionExpiredException()
        val tabs = doc.select("[data-tabr]")
        if (tabs.size != 4 || tabs.map { it.attr("data-tabr") }.toSet() !=
            setOf("marks-new", "marks-nonew", "marks-want", "marks-watched")) throw MarkupChangedException()
        return snapshot(doc, expectedUser)
    }

    internal fun snapshot(html: String): ResumeSnapshot {
        val doc = Jsoup.parse(html, "https://seasonvar.ru/")
        val user = user(doc) ?: throw SessionExpiredException()
        if (doc.selectFirst(".pgs-marks") == null) throw MarkupChangedException()
        return snapshot(doc, user)
    }

    private fun snapshot(doc: Document, user: AccountUser): ResumeSnapshot {
        val groups = doc.select("[data-tabr]").groupBy { it.attr("data-tabr") }
        val tabs = groups.mapValues { (tab, elements) ->
            elements.flatMap { it.select(".pgs-marks-el") }.mapIndexed { index, row ->
                val anchor = row.selectFirst("a[href*=serial-]")
                val href = anchor?.absUrl("href")
                val removeId = row.selectFirst("[data-click=mark-remove][data-id]")?.attr("data-id")
                val name = row.selectFirst(".pgs-marks-name")?.text()?.trim().orEmpty()
                val seasonText = row.selectFirst(".pgs-marks-seas")?.text()
                val current = row.selectFirst(".pgs-marks-current")?.text()?.trim().orEmpty()
                val message = row.selectFirst(".pgs-marks-mess")?.text()?.trim()?.takeUnless(seasonMessage::matches).orEmpty()
                val markId = row.attr("data-marksel")
                lazy {
                    val url = href?.let { SeasonvarParser.pageUrl(it.substringBefore('#')) } ?: throw MarkupChangedException()
                    val id = seasonId(url)
                    if (removeId != null && removeId != id || name.isBlank()) throw MarkupChangedException()
                    val season = seasonText?.let { Regex("[0-9]+").find(it)?.value?.toIntOrNull() } ?: seasonNumber(name)
                    val counter = AccountProgressParser.episodes(current)
                    val complete = tab == "marks-watched" || (counter != null && counter.marked == counter.total)
                    val mark = markId.takeIf { it.matches(Regex("[0-9]+")) } ?: "$id:$index"
                    SeriesEntry(id, url, title(name), season, "", "https://cdn.svstat.net/oblojka/$id.jpg",
                        sourceKey = "resume:$tab:$mark",
                        accountMark = if (current.isNotBlank() || message.isNotBlank() || complete) AccountMark(current, message, complete) else null,
                        accountProgress = counter?.let { AccountProgress(episodes = SeasonEpisodeProgress(id, it)) },
                        initialSelection = EntrySelectionParser.markLink(href), accountListMark = AccountListMark(id, url))
                }
            }
        }
        val legacy = doc.selectFirst(".pgs-marks-bn")?.select("a[href*=serial-]")?.map { anchor ->
            val href = anchor.absUrl("href")
            val name = anchor.selectFirst(".pgs-marks-name")?.text() ?: anchor.text()
            lazy {
                val url = SeasonvarParser.pageUrl(href.substringBefore('#')) ?: throw MarkupChangedException()
                val id = seasonId(url)
                if (name.isBlank()) throw MarkupChangedException()
                SeriesEntry(id, url, title(name), seasonNumber(name), "", "https://cdn.svstat.net/oblojka/$id.jpg",
                    initialSelection = EntrySelectionParser.markLink(href), accountListMark = AccountListMark(id, url))
            }
        }.orEmpty()
        return ResumeSnapshot(user, tabs, legacy, runCatching { PersonalFilterParser.options(doc) },
            groups.filterValues { it.size != 1 }.keys)
    }
    private fun seasonId(url: String) = Regex("/serial-([0-9]+)").find(url)?.groupValues?.get(1) ?: throw MarkupChangedException()
    private fun seasonNumber(name: String) = Regex("\\((\\d+)\\s*сезон\\)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
    private fun title(name: String) = name.replace(Regex("\\s*\\(\\d+\\s*сезон\\)", RegexOption.IGNORE_CASE), "").trim()

    private fun imageUrl(raw: String): String? {
        val url = raw.replaceFirst("http:", "https:").toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString()
    }
}

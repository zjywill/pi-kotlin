package works.earendil.pi.codingagent

import java.nio.file.Path
import works.earendil.pi.codingagent.session.SessionManager

fun main(args: Array<String>) {
    val sessionPath =
        Path
            .of(args.getOrElse(0) { "migration/fixtures/html-export/session.jsonl" })
            .toAbsolutePath()
            .normalize()
    val themePath =
        Path
            .of(args.getOrElse(1) { "migration/fixtures/html-export/oracle-theme.json" })
            .toAbsolutePath()
            .normalize()
    val session = SessionManager.open(sessionPath)
    val theme = loadThemeFromPath(themePath, ThemeColorMode.TRUECOLOR)
    print(
        generateSessionHtml(
            session,
            SessionHtmlExportOptions(theme = theme),
        ),
    )
}

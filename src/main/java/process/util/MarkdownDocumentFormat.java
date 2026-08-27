package process.util;

import org.jodconverter.core.document.DocumentFamily;
import org.jodconverter.core.document.DocumentFormat;

/**
 * Markdown support for the document converter.
 *
 * LibreOffice 26.2 added a "Markdown" filter for the Writer family, but JODConverter's own
 * DefaultDocumentFormatRegistry has no entry for it (jodconverter/jodconverter#444 is still
 * open), so getFormatByExtension("md") returns null and a conversion involving Markdown fails
 * before it reaches LibreOffice at all. This supplies the format definition that registry is
 * missing.
 *
 * Verified against LibreOffice 26.2.5.2 in both directions: importing a .md file parses
 * headings, bold/italic, lists and tables into real Writer structure, and exporting to .md
 * reproduces them. The filter is registered for the TEXT family only -- an HTML source loads
 * as Writer/Web instead and fails with "no export filter", which is why the family below is
 * TEXT rather than the input's own family.
 *
 * @author Nabeel Ahmed
 */
public final class MarkdownDocumentFormat {

    public static final String EXTENSION = "md";
    public static final String MEDIA_TYPE = "text/markdown";
    private static final String FILTER_NAME = "Markdown";

    private static final DocumentFormat FORMAT = DocumentFormat.builder()
        .name("Markdown")
        .extension(EXTENSION)
        .mediaType(MEDIA_TYPE)
        .inputFamily(DocumentFamily.TEXT)
        .loadFilterName(FILTER_NAME)
        .storeFilterName(DocumentFamily.TEXT, FILTER_NAME)
        .build();

    private MarkdownDocumentFormat() {
    }

    public static boolean isMarkdown(String extension) {
        return EXTENSION.equalsIgnoreCase(extension == null ? null : extension.trim());
    }

    public static DocumentFormat get() {
        return FORMAT;
    }

}

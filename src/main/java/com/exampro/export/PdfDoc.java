package com.exampro.export;

import com.exampro.exception.ExportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A small top-to-bottom PDF writer: lines of text that wrap, and new pages
 * when the current one fills. Enough for result sheets and question papers,
 * which are text and simple tables; not a layout engine.
 *
 * Only the standard Helvetica fonts are used, so no font files ship with the
 * app. They cover Latin-1 only, which {@link #safe} enforces: any other
 * character becomes '?' rather than throwing in the middle of a document.
 */
final class PdfDoc implements AutoCloseable {

    static final float MARGIN = 50;
    private static final float BOTTOM = 60;

    private final PDDocument doc = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream out;
    private float y;

    PdfDoc() {
        newPage();
    }

    int pageCount() {
        return pages.size();
    }

    float width() {
        return PDRectangle.A4.getWidth() - 2 * MARGIN;
    }

    /** Wraps text to the page width at the given indent and writes it, one line at a time. */
    void paragraph(String text, float size, boolean isBold, float indent) {
        PDType1Font font = isBold ? bold : regular;
        for (String line : wrap(safe(text), font, size, width() - indent)) {
            ensureSpace(size * 1.4f);
            y -= size;   // drop by this line's own height first, so a big line after a small one can't overlap it
            write(line, MARGIN + indent, size, font);
            y -= size * 0.4f;
        }
    }

    /** One line of a table: each cell is written at its own x offset, truncated to its column. */
    void row(String[] cells, float[] xs, float size, boolean isBold) {
        PDType1Font font = isBold ? bold : regular;
        ensureSpace(size * 1.5f);
        y -= size;
        for (int i = 0; i < cells.length; i++) {
            float room = (i + 1 < xs.length ? xs[i + 1] : width()) - xs[i] - 6;
            write(fit(safe(cells[i]), font, size, room), MARGIN + xs[i], size, font);
        }
        y -= size * 0.5f;
    }

    void gap(float points) {
        y -= points;
    }

    void rule() {
        ensureSpace(8);
        try {
            out.moveTo(MARGIN, y + 3);
            out.lineTo(MARGIN + width(), y + 3);
            out.stroke();
        } catch (IOException e) {
            throw new ExportException("Could not draw to the PDF", e);
        }
        y -= 6;
    }

    void ensureSpace(float needed) {
        if (y - needed < BOTTOM) {
            newPage();
        }
    }

    void save(Path target) {
        try {
            finishPage();
            doc.save(target.toFile());
        } catch (IOException e) {
            throw new ExportException("Could not write " + target, e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        try {
            doc.close();
        } catch (IOException ignored) {
            // nothing useful to do if closing a file we are discarding fails
        }
    }

    // ------------------------------------------------------------------

    private void newPage() {
        try {
            if (out != null) {
                finishPage();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            pages.add(page);
            out = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        } catch (IOException e) {
            throw new ExportException("Could not add a PDF page", e);
        }
    }

    private void finishPage() throws IOException {
        write("Page " + pages.size(), MARGIN + width() - 40, 9, regular, 30);
        out.close();
    }

    private void write(String text, float x, float size, PDType1Font font) {
        write(text, x, size, font, y);
    }

    private void write(String text, float x, float size, PDType1Font font, float atY) {
        try {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, atY);
            out.showText(text);
            out.endText();
        } catch (IOException e) {
            throw new ExportException("Could not write to the PDF", e);
        }
    }

    /** Greedy word wrap; a single word wider than the line is left to overflow rather than split. */
    private List<String> wrap(String text, PDType1Font font, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && widthOf(candidate, font, size) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String fit(String text, PDType1Font font, float size, float room) {
        if (widthOf(text, font, size) <= room) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && widthOf(cut + "...", font, size) > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private float widthOf(String text, PDType1Font font, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException e) {
            throw new ExportException("Could not measure PDF text", e);
        }
    }

    /** Latin-1 only; tabs become spaces; newlines are kept for {@link #wrap}. */
    static String safe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\t') {
                b.append("    ");
            } else if (c == '\n' || c == '\r' || (c >= 32 && c <= 255 && c != 127)) {
                b.append(c);
            } else {
                b.append('?');
            }
        }
        return b.toString();
    }
}

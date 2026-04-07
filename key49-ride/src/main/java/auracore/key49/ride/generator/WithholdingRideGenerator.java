package auracore.key49.ride.generator;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generador del RIDE (PDF) para comprobantes de retención electrónicos.
 * Produce un PDF conforme al formato exigido por el SRI de Ecuador.
 */
public final class WithholdingRideGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final Color HEADER_BG = new Color(220, 220, 220);
    private static final Color BORDER_COLOR = new Color(180, 180, 180);

    private static final float MARGIN = 30f;

    private static final Font FONT_TITLE = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font FONT_SUBTITLE = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font FONT_NORMAL = new Font(Font.HELVETICA, 8, Font.NORMAL);
    private static final Font FONT_BOLD = new Font(Font.HELVETICA, 8, Font.BOLD);
    private static final Font FONT_SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL);
    private static final Font FONT_HEADER = new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE);

    private WithholdingRideGenerator() {
    }

    /**
     * Genera el RIDE (PDF) de un comprobante de retención.
     *
     * @param data datos del comprobante para el RIDE
     * @return bytes del PDF generado
     */
    public static byte[] generate(WithholdingRideData data) {
        try {
            var outputStream = new ByteArrayOutputStream();
            var document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN);
            var writer = PdfWriter.getInstance(document, outputStream);
            document.open();

            addHeader(document, data);
            addSubjectSection(document, data);
            addWithholdingsTable(document, data);
            addTotalsSection(document, data);
            addAdditionalInfo(document, data);

            if (!data.authorized()) {
                addWatermark(writer);
            }

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new RideGenerationException("Failed to generate withholding RIDE PDF", e);
        }
    }

    private static void addHeader(Document document, WithholdingRideData data) throws DocumentException {
        var headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{55, 45});
        headerTable.setSpacingAfter(10);

        var leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.BOX);
        leftCell.setBorderColor(BORDER_COLOR);
        leftCell.setPadding(8);

        if (data.logo() != null && data.logo().length > 0) {
            try {
                var logo = Image.getInstance(data.logo());
                logo.scaleToFit(120, 60);
                leftCell.addElement(logo);
                leftCell.addElement(new Paragraph(" ", FONT_SMALL));
            } catch (Exception e) {
                // Logo can't be loaded — skip silently
            }
        }

        leftCell.addElement(new Paragraph(data.issuer().legalName(), FONT_TITLE));
        if (data.issuer().tradeName() != null && !data.issuer().tradeName().isBlank()) {
            leftCell.addElement(new Paragraph(data.issuer().tradeName(), FONT_SUBTITLE));
        }
        leftCell.addElement(new Paragraph(" ", FONT_SMALL));
        leftCell.addElement(createLabelValue("Dirección Matriz:", data.issuer().mainAddress()));
        if (data.issuer().establishmentAddress() != null
                && !data.issuer().establishmentAddress().isBlank()) {
            leftCell.addElement(createLabelValue("Dirección Sucursal:",
                    data.issuer().establishmentAddress()));
        }
        if (data.issuer().requiredAccounting()) {
            leftCell.addElement(createLabelValue("Obligado a llevar contabilidad:", "SÍ"));
        }
        if (data.issuer().specialTaxpayer() != null
                && !data.issuer().specialTaxpayer().isBlank()) {
            leftCell.addElement(createLabelValue("Contribuyente Especial Nro:",
                    data.issuer().specialTaxpayer()));
        }
        if (data.issuer().withholdingAgent() != null
                && !data.issuer().withholdingAgent().isBlank()) {
            leftCell.addElement(createLabelValue("Agente de Retención:",
                    data.issuer().withholdingAgent()));
        }
        if (data.issuer().rimpeContributor() != null
                && !data.issuer().rimpeContributor().isBlank()) {
            leftCell.addElement(createLabelValue("Contribuyente Régimen RIMPE:",
                    data.issuer().rimpeContributor()));
        }

        headerTable.addCell(leftCell);

        var rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(8);

        rightCell.addElement(createLabelValue("R.U.C.:", data.issuer().ruc()));
        rightCell.addElement(new Paragraph(" ", FONT_SMALL));
        rightCell.addElement(new Paragraph("COMPROBANTE DE RETENCIÓN", FONT_SUBTITLE));
        rightCell.addElement(new Paragraph("No. " + data.formattedDocumentNumber(), FONT_SUBTITLE));
        rightCell.addElement(new Paragraph(" ", FONT_SMALL));

        if (data.authorizationNumber() != null && !data.authorizationNumber().isBlank()) {
            rightCell.addElement(createLabelValue("NÚMERO DE AUTORIZACIÓN:", ""));
            rightCell.addElement(new Paragraph(data.authorizationNumber(), FONT_SMALL));
        }
        if (data.authorizationDate() != null) {
            rightCell.addElement(createLabelValue("FECHA Y HORA DE AUTORIZACIÓN:",
                    data.authorizationDate().format(DATETIME_FMT)));
        }

        rightCell.addElement(createLabelValue("AMBIENTE:", resolveEnvironment(data.environment())));
        rightCell.addElement(createLabelValue("EMISIÓN:", resolveEmissionType(data.emissionType())));
        rightCell.addElement(new Paragraph(" ", FONT_SMALL));

        rightCell.addElement(createLabelValue("CLAVE DE ACCESO:", ""));
        rightCell.addElement(new Paragraph(data.accessKey(), FONT_SMALL));

        rightCell.addElement(new Paragraph(" ", FONT_SMALL));
        try {
            byte[] qrBytes = QrCodeGenerator.generate(data.accessKey());
            var qrImage = Image.getInstance(qrBytes);
            qrImage.scaleToFit(100, 100);
            qrImage.setAlignment(Element.ALIGN_CENTER);
            rightCell.addElement(qrImage);
        } catch (Exception e) {
            rightCell.addElement(new Paragraph("[QR no disponible]", FONT_SMALL));
        }

        headerTable.addCell(rightCell);
        document.add(headerTable);
    }

    private static void addSubjectSection(Document document, WithholdingRideData data)
            throws DocumentException {
        var table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50, 50});
        table.setSpacingAfter(10);

        addRow(table, "Razón Social / Nombres y Apellidos:", data.subject().name(),
                "R.U.C. / C.I.:", data.subject().id());
        addRow(table, "Fecha de Emisión:", data.issueDate().format(DATE_FMT),
                "Tipo de Identificación:", resolveIdTypeName(data.subject().idType()));
        addRow(table, "Ejercicio Fiscal:", data.fiscalPeriod(),
                "Parte Relacionada:", data.relatedParty() ? "SÍ" : "NO");

        document.add(table);
    }

    private static void addWithholdingsTable(Document document, WithholdingRideData data)
            throws DocumentException {
        var table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{12, 12, 15, 20, 15, 13, 13});
        table.setSpacingAfter(10);

        addTableHeader(table, "Impuesto");
        addTableHeader(table, "Cod. Ret.");
        addTableHeader(table, "Doc. Sustento");
        addTableHeader(table, "Nro. Doc. Sustento");
        addTableHeader(table, "Base Imponible");
        addTableHeader(table, "% Retención");
        addTableHeader(table, "Valor Retenido");

        for (var sd : data.supportingDocuments()) {
            for (var wh : sd.withholdings()) {
                addTableCell(table, resolveRetentionTaxName(wh.code()), Element.ALIGN_LEFT);
                addTableCell(table, wh.retentionCode(), Element.ALIGN_CENTER);
                addTableCell(table, resolveDocumentTypeName(sd.documentCode()),
                        Element.ALIGN_LEFT);
                addTableCell(table, sd.documentNumber(), Element.ALIGN_CENTER);
                addTableCell(table, formatMoney(wh.taxableBase()), Element.ALIGN_RIGHT);
                addTableCell(table, formatMoney(wh.retentionRate()), Element.ALIGN_RIGHT);
                addTableCell(table, formatMoney(wh.retainedAmount()), Element.ALIGN_RIGHT);
            }
        }

        document.add(table);
    }

    private static void addTotalsSection(Document document, WithholdingRideData data)
            throws DocumentException {
        var table = new PdfPTable(2);
        table.setWidthPercentage(40);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingAfter(10);
        table.setWidths(new float[]{60, 40});

        addTotalRowBold(table, "TOTAL RETENIDO", data.totalRetained());

        document.add(table);
    }

    private static void addAdditionalInfo(Document document, WithholdingRideData data)
            throws DocumentException {
        if (data.additionalInfo() == null || data.additionalInfo().isEmpty()) {
            return;
        }

        document.add(new Paragraph("Información Adicional", FONT_SUBTITLE));
        document.add(new Paragraph(" ", FONT_SMALL));

        var table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{30, 70});
        table.setSpacingAfter(10);

        for (Map.Entry<String, String> entry : data.additionalInfo().entrySet()) {
            addTableCell(table, entry.getKey(), Element.ALIGN_LEFT);
            addTableCell(table, entry.getValue(), Element.ALIGN_LEFT);
        }

        document.add(table);
    }

    private static void addWatermark(PdfWriter writer) {
        var canvas = writer.getDirectContentUnder();
        try {
            var baseFont = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, false);
            canvas.saveState();
            canvas.beginText();
            canvas.setFontAndSize(baseFont, 52);
            canvas.setColorFill(new Color(200, 200, 200));
            canvas.showTextAligned(Element.ALIGN_CENTER, "SIN AUTORIZACIÓN",
                    PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2, 45);
            canvas.endText();
            canvas.restoreState();
        } catch (Exception e) {
            // Watermark is cosmetic — skip if fails
        }
    }

    // --- Helper methods ---

    private static Paragraph createLabelValue(String label, String value) {
        var paragraph = new Paragraph();
        paragraph.add(new Chunk(label + " ", FONT_BOLD));
        paragraph.add(new Chunk(value != null ? value : "", FONT_NORMAL));
        return paragraph;
    }

    private static void addRow(PdfPTable table, String label1, String value1,
            String label2, String value2) {
        var cell1 = new PdfPCell();
        cell1.setBorder(Rectangle.BOX);
        cell1.setBorderColor(BORDER_COLOR);
        cell1.setPadding(4);
        cell1.addElement(createLabelValue(label1, value1));

        var cell2 = new PdfPCell();
        cell2.setBorder(Rectangle.BOX);
        cell2.setBorderColor(BORDER_COLOR);
        cell2.setPadding(4);
        cell2.addElement(createLabelValue(label2, value2));

        table.addCell(cell1);
        table.addCell(cell2);
    }

    private static void addTableHeader(PdfPTable table, String text) {
        var cell = new PdfPCell(new Phrase(text, FONT_HEADER));
        cell.setBackgroundColor(new Color(60, 60, 60));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private static void addTableCell(PdfPTable table, String text, int alignment) {
        var cell = new PdfPCell(new Phrase(text != null ? text : "", FONT_NORMAL));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(3);
        cell.setBorderColor(BORDER_COLOR);
        table.addCell(cell);
    }

    private static void addTotalRowBold(PdfPTable table, String label, BigDecimal value) {
        var labelCell = new PdfPCell(new Phrase(label, FONT_BOLD));
        labelCell.setPadding(3);
        labelCell.setBorderColor(BORDER_COLOR);
        labelCell.setBackgroundColor(HEADER_BG);
        table.addCell(labelCell);

        var valueCell = new PdfPCell(new Phrase(formatMoney(value), FONT_BOLD));
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setPadding(3);
        valueCell.setBorderColor(BORDER_COLOR);
        valueCell.setBackgroundColor(HEADER_BG);
        table.addCell(valueCell);
    }

    private static String formatMoney(BigDecimal value) {
        if (value == null) return "0.00";
        return String.format("%.2f", value);
    }

    private static String resolveRetentionTaxName(String code) {
        return switch (code) {
            case "1" -> "RENTA";
            case "2" -> "IVA";
            case "6" -> "ISD";
            default -> "IMP " + code;
        };
    }

    private static String resolveDocumentTypeName(String code) {
        return switch (code) {
            case "01" -> "FACTURA";
            case "03" -> "LIQ. COMPRA";
            case "04" -> "NOTA CRÉDITO";
            case "05" -> "NOTA DÉBITO";
            case "06" -> "GUÍA REMISIÓN";
            case "07" -> "COMP. RETENCIÓN";
            default -> "DOC " + code;
        };
    }

    private static String resolveEnvironment(String code) {
        return switch (code) {
            case "1" -> "PRUEBAS";
            case "2" -> "PRODUCCIÓN";
            default -> code;
        };
    }

    private static String resolveEmissionType(String code) {
        return switch (code) {
            case "1" -> "NORMAL";
            default -> code;
        };
    }

    private static String resolveIdTypeName(String code) {
        return switch (code) {
            case "04" -> "RUC";
            case "05" -> "CÉDULA";
            case "06" -> "PASAPORTE";
            case "07" -> "CONSUMIDOR FINAL";
            case "08" -> "IDENTIFICACIÓN EXTERIOR";
            default -> code;
        };
    }
}

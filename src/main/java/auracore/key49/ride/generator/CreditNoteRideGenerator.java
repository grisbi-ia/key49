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
import java.util.List;
import java.util.Map;

/**
 * Generador del RIDE (PDF) para notas de crédito electrónicas. Produce un PDF
 * conforme al formato exigido por el SRI de Ecuador.
 *
 * <p>
 * Incluye: datos del emisor, datos del receptor, documento modificado, motivo,
 * detalle de ítems, impuestos, totales, información adicional, código QR y
 * marca de agua.</p>
 */

public final class CreditNoteRideGenerator {

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

    private CreditNoteRideGenerator() {}

    /**
     * Genera el RIDE (PDF) de una nota de crédito a partir de los datos proporcionados.
     *
     * @param data datos del comprobante para el RIDE
     * @return bytes del PDF generado
     */
    public static byte[] generate(CreditNoteRideData data) {
        try {
            var outputStream = new ByteArrayOutputStream();
            var document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN);
            var writer = PdfWriter.getInstance(document, outputStream);
            document.open();

            addHeader(document, data);
            addRecipientSection(document, data);
            addModifiedDocumentSection(document, data);
            addItemsTable(document, data);
            addTotalsSection(document, data);
            addAdditionalInfo(document, data);

            if (!data.authorized()) {
                addWatermark(writer);
            }

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException e) {
            throw new RideGenerationException("Failed to generate credit note RIDE PDF", e);
        }
    }

    private static void addHeader(Document document, CreditNoteRideData data) throws DocumentException {
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
        if (data.issuer().establishmentAddress() != null && !data.issuer().establishmentAddress().isBlank()) {
            leftCell.addElement(createLabelValue("Dirección Sucursal:", data.issuer().establishmentAddress()));
        }
        if (data.issuer().requiredAccounting()) {
            leftCell.addElement(createLabelValue("Obligado a llevar contabilidad:", "SÍ"));
        }
        if (data.issuer().specialTaxpayer() != null && !data.issuer().specialTaxpayer().isBlank()) {
            leftCell.addElement(createLabelValue("Contribuyente Especial Nro:", data.issuer().specialTaxpayer()));
        }
        if (data.issuer().withholdingAgent() != null && !data.issuer().withholdingAgent().isBlank()) {
            leftCell.addElement(createLabelValue("Agente de Retención:", data.issuer().withholdingAgent()));
        }
        if (data.issuer().rimpeContributor() != null && !data.issuer().rimpeContributor().isBlank()) {
            leftCell.addElement(createLabelValue("Contribuyente Régimen RIMPE:", data.issuer().rimpeContributor()));
        }

        headerTable.addCell(leftCell);

        var rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(8);

        rightCell.addElement(createLabelValue("R.U.C.:", data.issuer().ruc()));
        rightCell.addElement(new Paragraph(" ", FONT_SMALL));
        rightCell.addElement(new Paragraph("NOTA DE CRÉDITO", FONT_SUBTITLE));
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
            byte[] barCodeBytes = BarCodeGenerator.generate(data.accessKey());
            var barCodeImage = Image.getInstance(barCodeBytes);
            barCodeImage.scaleToFit(200, 40);
            barCodeImage.setAlignment(Element.ALIGN_CENTER);
            rightCell.addElement(barCodeImage);
        } catch (Exception e) {
            rightCell.addElement(new Paragraph("[Código de barras no disponible]", FONT_SMALL));
        }

        headerTable.addCell(rightCell);
        document.add(headerTable);
    }

    private static void addRecipientSection(Document document, CreditNoteRideData data) throws DocumentException {
        var table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50, 50});
        table.setSpacingAfter(10);

        addRow(table, "Razón Social / Nombres y Apellidos:", data.recipient().name(),
                "R.U.C. / C.I.:", data.recipient().id());
        addRow(table, "Fecha de Emisión:", data.issueDate().format(DATE_FMT),
                "Tipo de Identificación:", resolveIdTypeName(data.recipient().idType()));

        document.add(table);
    }

    private static void addModifiedDocumentSection(Document document, CreditNoteRideData data) throws DocumentException {
        var table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50, 50});
        table.setSpacingAfter(5);

        addRow(table, "Comprobante que se modifica:",
                resolveDocumentTypeName(data.modifiedDocumentCode()),
                "Número:", data.modifiedDocumentNumber());
        addRow(table, "Fecha de emisión (doc. sustento):",
                data.modifiedDocumentDate() != null ? data.modifiedDocumentDate().format(DATE_FMT) : "",
                "", "");

        document.add(table);

        if (data.reason() != null && !data.reason().isBlank()) {
            var reasonTable = new PdfPTable(1);
            reasonTable.setWidthPercentage(100);
            reasonTable.setSpacingAfter(10);
            var cell = new PdfPCell();
            cell.setBorder(Rectangle.BOX);
            cell.setBorderColor(BORDER_COLOR);
            cell.setPadding(4);
            cell.addElement(createLabelValue("Motivo:", data.reason()));
            reasonTable.addCell(cell);
            document.add(reasonTable);
        }
    }

    private static void addItemsTable(Document document, CreditNoteRideData data) throws DocumentException {
        var table = new PdfPTable(6);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{12, 34, 8, 14, 12, 14});
        table.setSpacingAfter(10);

        addTableHeader(table, "Cod. Interno");
        addTableHeader(table, "Descripción");
        addTableHeader(table, "Cant.");
        addTableHeader(table, "P. Unitario");
        addTableHeader(table, "Descuento");
        addTableHeader(table, "P. Total");

        for (var item : data.items()) {
            addTableCell(table, item.internalCode(), Element.ALIGN_LEFT);
            addTableCell(table, item.description(), Element.ALIGN_LEFT);
            addTableCell(table, formatNumber(item.quantity()), Element.ALIGN_RIGHT);
            addTableCell(table, formatMoney(item.unitPrice()), Element.ALIGN_RIGHT);
            addTableCell(table, formatMoney(item.discount()), Element.ALIGN_RIGHT);
            addTableCell(table, formatMoney(item.subtotal()), Element.ALIGN_RIGHT);
        }

        document.add(table);
    }

    private static void addTotalsSection(Document document, CreditNoteRideData data) throws DocumentException {
        var outerTable = new PdfPTable(2);
        outerTable.setWidthPercentage(100);
        outerTable.setWidths(new float[]{60, 40});
        outerTable.setSpacingAfter(10);

        var taxCell = new PdfPCell();
        taxCell.setBorder(Rectangle.NO_BORDER);
        taxCell.setPadding(0);

        if (!data.totalTaxes().isEmpty()) {
            var taxTable = new PdfPTable(3);
            taxTable.setWidthPercentage(100);
            taxTable.setWidths(new float[]{40, 30, 30});

            addTableHeader(taxTable, "Impuesto");
            addTableHeader(taxTable, "Base Imponible");
            addTableHeader(taxTable, "Valor");

            for (var tax : data.totalTaxes()) {
                addTableCell(taxTable, resolveTaxDescription(tax), Element.ALIGN_LEFT);
                addTableCell(taxTable, formatMoney(tax.taxableBase()), Element.ALIGN_RIGHT);
                addTableCell(taxTable, formatMoney(tax.amount()), Element.ALIGN_RIGHT);
            }
            taxCell.addElement(taxTable);
        }
        outerTable.addCell(taxCell);

        var totalsCell = new PdfPCell();
        totalsCell.setBorder(Rectangle.NO_BORDER);
        totalsCell.setPadding(0);

        var totalsTable = new PdfPTable(2);
        totalsTable.setWidthPercentage(100);
        totalsTable.setWidths(new float[]{60, 40});

        addTotalRow(totalsTable, "SUBTOTAL SIN IMPUESTOS", data.subtotalBeforeTax());

        for (var tax : data.totalTaxes()) {
            addTotalRow(totalsTable, resolveTaxDescription(tax), tax.amount());
        }

        addTotalRowBold(totalsTable, "VALOR TOTAL", data.modificationValue());

        totalsCell.addElement(totalsTable);
        outerTable.addCell(totalsCell);

        document.add(outerTable);
    }

    private static void addAdditionalInfo(Document document, CreditNoteRideData data) throws DocumentException {
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

    private static void addTotalRow(PdfPTable table, String label, BigDecimal value) {
        addTableCell(table, label, Element.ALIGN_LEFT);
        addTableCell(table, formatMoney(value), Element.ALIGN_RIGHT);
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

    private static String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static String resolveDocumentTypeName(String code) {
        return switch (code) {
            case "01" -> "FACTURA";
            case "03" -> "LIQUIDACIÓN DE COMPRA";
            case "04" -> "NOTA DE CRÉDITO";
            case "05" -> "NOTA DE DÉBITO";
            case "06" -> "GUÍA DE REMISIÓN";
            case "07" -> "COMPROBANTE DE RETENCIÓN";
            default -> "DOCUMENTO ELECTRÓNICO";
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
            default -> code;
        };
    }

    private static String resolveTaxDescription(RideData.TotalTax tax) {
        String taxName = switch (tax.taxCode()) {
            case "2" -> "IVA";
            case "3" -> "ICE";
            case "5" -> "IRBPNR";
            default -> "Impuesto " + tax.taxCode();
        };

        if ("2".equals(tax.taxCode())) {
            String rateSuffix = tax.rate() != null
                    ? " " + tax.rate().stripTrailingZeros().toPlainString() + "%"
                    : "";
            return taxName + rateSuffix;
        }

        return taxName;
    }
}

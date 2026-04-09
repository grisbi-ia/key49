#!/usr/bin/env bash
# ============================================================================
# Key49 — Scripts de prueba CURL para todos los tipos de documento electrónico
# ============================================================================
# Uso:
#   chmod +x test-curls.sh
#   ./test-curls.sh factura
#   ./test-curls.sh nota_credito
#   ./test-curls.sh nota_debito
#   ./test-curls.sh liquidacion
#   ./test-curls.sh guia_remision
#   ./test-curls.sh retencion
#   ./test-curls.sh todos
# ============================================================================

BASE_URL="http://localhost:8080/v1"
AUTH="Authorization: Bearer fec_test_DemoKey49DevLocalTest00"
CT="Content-Type: application/json"
TODAY=$(date +%Y-%m-%d)
FISCAL_PERIOD=$(date +%m/%Y)

# ── 01. Factura ──────────────────────────────────────────────────────────────
factura() {
  echo "═══ 01. FACTURA ═══"
  curl -s -X POST "$BASE_URL/invoices" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: inv-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000005",
      "issue_date": "'"$TODAY"'",
      "recipient": {
        "id_type": "04",
        "id": "1792198569001",
        "name": "SIVASA",
        "address": "Quito, Av. Principal 123",
        "email": "test@example.com"
      },
      "items": [{
        "main_code": "SRV-001",
        "description": "Servicio de prueba",
        "quantity": 1,
        "unit_price": 100.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.0 }]
      }],
      "payments": [{
        "payment_method": "01",
        "total": 115.00
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── 04. Nota de Crédito ─────────────────────────────────────────────────────
nota_credito() {
  echo "═══ 04. NOTA DE CRÉDITO ═══"
  curl -s -X POST "$BASE_URL/credit-notes" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: cn-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000002",
      "issue_date": "'"$TODAY"'",
      "recipient": {
        "id_type": "04",
        "id": "1792198569001",
        "name": "SIVASA",
        "email": "test@example.com"
      },
      "modified_document_code": "01",
      "modified_document_number": "001-999-000000003",
      "modified_document_date": "'"$TODAY"'",
      "reason": "Devolución parcial de servicio",
      "items": [{
        "internal_code": "SRV-001",
        "description": "Servicio de prueba (devolución)",
        "quantity": 1,
        "unit_price": 50.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.0 }]
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── 05. Nota de Débito ──────────────────────────────────────────────────────
nota_debito() {
  echo "═══ 05. NOTA DE DÉBITO ═══"
  curl -s -X POST "$BASE_URL/debit-notes" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: dn-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000002",
      "issue_date": "'"$TODAY"'",
      "recipient": {
        "id_type": "04",
        "id": "1792198569001",
        "name": "SIVASA",
        "email": "test@example.com"
      },
      "modified_document_code": "01",
      "modified_document_number": "001-999-000000003",
      "modified_document_date": "'"$TODAY"'",
      "reasons": [{
        "description": "Intereses por mora en pago",
        "amount": 25.00
      }],
      "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.0 }],
      "payments": [{
        "payment_method": "01",
        "total": 28.75
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── 03. Liquidación de Compra ────────────────────────────────────────────────
liquidacion() {
  echo "═══ 03. LIQUIDACIÓN DE COMPRA ═══"
  curl -s -X POST "$BASE_URL/purchase-clearances" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: pc-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000002",
      "issue_date": "'"$TODAY"'",
      "supplier": {
        "id_type": "05",
        "id": "1712345675",
        "name": "Juan Pérez (proveedor informal)",
        "address": "Quito, Barrio La Floresta"
      },
      "items": [{
        "main_code": "PROD-001",
        "description": "Producto agrícola",
        "unit_of_measure": "kg",
        "quantity": 10,
        "unit_price": 5.00,
        "discount": 0.00,
        "taxes": [{ "code": "2", "rate_code": "4", "rate": 15.0 }]
      }],
      "payments": [{
        "payment_method": "01",
        "total": 57.50
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── 06. Guía de Remisión ────────────────────────────────────────────────────
guia_remision() {
  echo "═══ 06. GUÍA DE REMISIÓN ═══"
  curl -s -X POST "$BASE_URL/waybills" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: wb-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000002",
      "issue_date": "'"$TODAY"'",
      "departure_address": "Quito, Av. Principal 123",
      "carrier": {
        "id_type": "04",
        "id": "1792198569001",
        "name": "TRANSPORTE SEGURO S.A."
      },
      "transport_start_date": "'"$TODAY"'",
      "transport_end_date": "'"$TODAY"'",
      "license_plate": "PBA-1234",
      "addressees": [{
        "id": "1792198569001",
        "name": "SIVASA",
        "address": "Guayaquil, Av. del Puerto 456",
        "transfer_reason": "Venta de mercadería",
        "route": "Quito - Guayaquil",
        "support_document_code": "01",
        "support_document_number": "001-999-000000003",
        "support_document_auth_number": "0000000000000000000000000000000000000000000000000",
        "support_document_issue_date": "'"$TODAY"'",
        "items": [{
          "main_code": "PROD-001",
          "description": "Producto agrícola",
          "quantity": 10
        }]
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── 07. Comprobante de Retención ─────────────────────────────────────────────
retencion() {
  echo "═══ 07. COMPROBANTE DE RETENCIÓN ═══"
  curl -s -X POST "$BASE_URL/withholdings" \
    -H "$AUTH" \
    -H "$CT" \
    -H "X-Idempotency-Key: wh-$(date +%s)" \
    -d '{
      "establishment": "001",
      "issue_point": "999",
      "sequence_number": "000000002",
      "issue_date": "'"$TODAY"'",
      "subject": {
        "id_type": "04",
        "id": "1792198569001",
        "name": "SIVASA",
        "email": "test@example.com"
      },
      "fiscal_period": "'"$FISCAL_PERIOD"'",
      "related_party": false,
      "supporting_documents": [{
        "support_code": "01",
        "document_code": "01",
        "document_number": "001-999-000000003",
        "issue_date": "'"$TODAY"'",
        "authorization_number": "0000000000000000000000000000000000000000000000000",
        "payment_locality": "01",
        "total_without_tax": 100.00,
        "total_amount": 115.00,
        "taxes": [{
          "tax_code": "2",
          "rate_code": "4",
          "taxable_base": 100.00,
          "rate": 15.0,
          "amount": 15.00
        }],
        "withholdings": [{
          "code": "1",
          "retention_code": "312",
          "taxable_base": 100.00,
          "retention_rate": 2.0,
          "retained_amount": 2.00
        }],
        "payments": [{
          "payment_method": "01",
          "total": 115.00
        }]
      }]
    }' | python3 -m json.tool
  echo ""
}

# ── Ejecutor ─────────────────────────────────────────────────────────────────
case "${1:-todos}" in
  factura)       factura ;;
  nota_credito)  nota_credito ;;
  nota_debito)   nota_debito ;;
  liquidacion)   liquidacion ;;
  guia_remision) guia_remision ;;
  retencion)     retencion ;;
  todos)
    factura
    nota_credito
    nota_debito
    liquidacion
    guia_remision
    retencion
    ;;
  *)
    echo "Uso: $0 {factura|nota_credito|nota_debito|liquidacion|guia_remision|retencion|todos}"
    exit 1
    ;;
esac
